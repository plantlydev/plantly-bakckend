package project.plantly.authTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;
import project.plantly.DatabaseCleanup;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.rejoin.RejoinRestriction;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.security.oauth2.PendingSocialSignupStore;
import project.plantly.global.security.oauth2.SocialTokenRevoker;
import project.plantly.global.security.oauth2.WithdrawalReauthStore;
import project.plantly.global.security.oauth2.WithdrawalReauthStore.WithdrawalReauth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회원 탈퇴 전체 흐름: 네이버 로그인 → 탈퇴 시작 API(의도 저장) → 재인가 → 콜백에서 탈퇴·연동 해제·세션 정리.
// 제공자와 통신하는 세 호출(토큰 교환, 사용자 정보, 연동 해제)만 가짜로 바꾸고 나머지는 실제 보안 설정 그대로 돈다.
// 다른 소셜 흐름 테스트와 같은 MockitoBean 구성이라 스프링 컨텍스트를 공유한다.
// 소유 회사 삭제(검색 도큐먼트 갱신이 Postgres 전용)는 WithdrawalServiceTest 가 실제 Postgres 로 본다 — 여기 회원은 회사가 없다.
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalFlowTest {

    private static final String FRONT = "http://localhost:3000";
    private static final String NAVER_ID = "naver-member";
    private static final String EMAIL = "member@naver.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SocialAccountRepository socialAccountRepository;
    @Autowired private RejoinRestriction rejoinRestriction;
    @Autowired private DatabaseCleanup databaseCleanup;

    @MockitoBean
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    @MockitoBean
    private DefaultOAuth2UserService userInfoClient;
    @MockitoBean
    private SocialTokenRevoker tokenRevoker;

    private User member;

    @BeforeEach
    void setUp() {
        given(tokenResponseClient.getTokenResponse(any())).willReturn(OAuth2AccessTokenResponse
                .withToken("naver-access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(3600)
                .build());
        given(userInfoClient.loadUser(any())).willReturn(naverUser(NAVER_ID, EMAIL));
        given(tokenRevoker.revoke(any(), any())).willReturn(true);

        member = userRepository.save(User.create(EMAIL, "encoded", "홍길동", null));
        socialAccountRepository.save(SocialAccount.link(member.getId(), SocialProvider.NAVER, NAVER_ID, EMAIL));
    }

    @AfterEach
    void cleanUp() {
        databaseCleanup.execute();
    }

    // ===== 확인 화면·시작 API =====

    @Test
    @DisplayName("미리보기: 함께 지워질 개수와 본인 확인 제공자를 준다")
    void preview() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/api/v1/users/me/withdrawal").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownedCompanyCount").value(0))
                .andExpect(jsonPath("$.data.draftCount").value(0))
                .andExpect(jsonPath("$.data.provider").value("NAVER"));
    }

    @Test
    @DisplayName("시작: 재인가 주소를 돌려주고 세션에 탈퇴 의도를 둔다 — 아직 아무것도 지우지 않는다")
    void start() throws Exception {
        MockHttpSession session = login();

        startWithdrawal(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reauthUrl").value("/oauth2/authorization/naver"));

        assertThat(session.getAttribute(WithdrawalReauthStore.SESSION_ATTRIBUTE)).isInstanceOf(WithdrawalReauth.class);
        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("시작: 관리자는 403 ADMIN_WITHDRAWAL_NOT_ALLOWED, 의도를 두지 않는다")
    void adminCannotStart() throws Exception {
        User admin = userRepository.save(User.builder()
                .email("admin@naver.com").password("encoded").name("관리자").userRole(UserRole.ADMIN).build());
        socialAccountRepository.save(SocialAccount.link(admin.getId(), SocialProvider.NAVER, "naver-admin", "admin@naver.com"));
        given(userInfoClient.loadUser(any())).willReturn(naverUser("naver-admin", "admin@naver.com"));
        MockHttpSession session = login();

        startWithdrawal(session)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_WITHDRAWAL_NOT_ALLOWED"));
        assertThat(session.getAttribute(WithdrawalReauthStore.SESSION_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("미리보기·시작 모두 로그인이 필요하다(401)")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/withdrawal")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/users/me/withdrawal").with(csrf())).andExpect(status().isUnauthorized());
    }

    // ===== 재인가 콜백 =====

    @Test
    @DisplayName("같은 회원으로 확인하면 탈퇴 → 방금 받은 토큰으로 연동 해제 → 세션 종료 → /withdrawn")
    void withdrawSucceeds() throws Exception {
        MockHttpSession session = login();
        startWithdrawal(session).andExpect(status().isOk());

        reauthenticate(session)
                .andExpect(redirectedUrl(FRONT + "/withdrawn"))
                .andExpect(cookie().maxAge("JSESSIONID", 0));

        User user = userRepository.findById(member.getId()).orElseThrow();
        assertThat(user.getUserStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(socialAccountRepository.findByUserId(member.getId())).isEmpty();
        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, NAVER_ID)).isTrue();

        verify(tokenRevoker).revoke(eq(SocialProvider.NAVER),
                org.mockito.ArgumentMatchers.<OAuth2AuthorizedClient>argThat(client ->
                        client.getAccessToken().getTokenValue().equals("naver-access-token")
                                && client.getClientRegistration().getRegistrationId().equals("naver")));

        // 세션은 끝났다: 무효화되고 세션 쿠키도 만료시킨다(로그아웃 API 와 같은 정리).
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("연동 해제가 실패해도 탈퇴는 유지되고, 완료 화면에 직접 해제 안내를 띄우도록 unlinkFailed 를 넘긴다")
    void revokeFailureKeepsWithdrawal() throws Exception {
        given(tokenRevoker.revoke(any(), any())).willReturn(false);
        MockHttpSession session = login();
        startWithdrawal(session).andExpect(status().isOk());

        reauthenticate(session)
                .andExpect(redirectedUrl(FRONT + "/withdrawn?unlinkFailed=NAVER"))
                .andExpect(cookie().maxAge("JSESSIONID", 0));

        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("다른 회원의 소셜 계정으로 확인하면 아무도 탈퇴하지 않고 로그아웃 → /login?error=WITHDRAWAL_REAUTH_MISMATCH")
    void otherMemberMismatch() throws Exception {
        User other = userRepository.save(User.create("other@naver.com", "encoded", "김철수", null));
        socialAccountRepository.save(SocialAccount.link(other.getId(), SocialProvider.NAVER, "naver-other", "other@naver.com"));
        MockHttpSession session = login();
        startWithdrawal(session).andExpect(status().isOk());

        given(userInfoClient.loadUser(any())).willReturn(naverUser("naver-other", "other@naver.com"));
        reauthenticate(session).andExpect(redirectedUrl(FRONT + "/login?error=WITHDRAWAL_REAUTH_MISMATCH"));

        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findById(other.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(tokenRevoker, never()).revoke(any(), any());
        // 세션이 다른 회원으로 바뀌었던 상태를 남기지 않는다.
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("가입 안 된 소셜 계정으로 확인하면 탈퇴하지 않고, 가입 대기도 만들지 않고, 로그인된 채 탈퇴 화면으로")
    void unlinkedAccountMismatch() throws Exception {
        MockHttpSession session = login();
        startWithdrawal(session).andExpect(status().isOk());

        given(userInfoClient.loadUser(any())).willReturn(naverUser("naver-stranger", "stranger@naver.com"));
        reauthenticate(session).andExpect(redirectedUrl(FRONT + "/account/withdraw?error=WITHDRAWAL_REAUTH_MISMATCH"));

        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(session.getAttribute(PendingSocialSignupStore.SESSION_ATTRIBUTE)).isNull();
        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    @DisplayName("제공자 화면에서 취소하면 로그인된 채 탈퇴 화면으로 — 의도는 1회용이라 다음 콜백은 보통 로그인이다")
    void cancelled() throws Exception {
        MockHttpSession session = login();
        startWithdrawal(session).andExpect(status().isOk());

        String state = startAuthorization(session);
        mockMvc.perform(get("/login/oauth2/code/naver")
                        .param("error", "access_denied")
                        .param("state", state)
                        .session(session))
                .andExpect(redirectedUrl(FRONT + "/account/withdraw?error=WITHDRAWAL_REAUTH_FAILED"));

        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isOk());

        // 의도가 지워졌으므로 다시 소셜 로그인하면 탈퇴가 아니라 로그인이다.
        reauthenticate(session).andExpect(redirectedUrl(FRONT + "/"));
        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("탈퇴 의도가 만료됐으면 탈퇴하지 않고 WITHDRAWAL_SESSION_EXPIRED")
    void expiredIntent() throws Exception {
        MockHttpSession session = login();
        session.setAttribute(WithdrawalReauthStore.SESSION_ATTRIBUTE,
                new WithdrawalReauth(member.getId(), SocialProvider.NAVER, Instant.now().minusSeconds(1)));

        reauthenticate(session).andExpect(redirectedUrl(FRONT + "/account/withdraw?error=WITHDRAWAL_SESSION_EXPIRED"));

        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(tokenRevoker, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("의도와 다른 제공자로 확인하면(같은 회원) 탈퇴하지 않고 WITHDRAWAL_REAUTH_MISMATCH — 로그인은 유지된다")
    void otherProviderMismatch() throws Exception {
        MockHttpSession session = login();
        session.setAttribute(WithdrawalReauthStore.SESSION_ATTRIBUTE,
                new WithdrawalReauth(member.getId(), SocialProvider.GOOGLE, Instant.now().plusSeconds(600)));

        reauthenticate(session).andExpect(redirectedUrl(FRONT + "/account/withdraw?error=WITHDRAWAL_REAUTH_MISMATCH"));

        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isOk());
    }

    // ---- 헬퍼 ----

    // 네이버 로그인(인가 시작 → 콜백)으로 세션을 만든다.
    private MockHttpSession login() throws Exception {
        MvcResult start = mockMvc.perform(get("/oauth2/authorization/naver")).andReturn();
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
        callback(session, state(start)).andExpect(redirectedUrl(FRONT + "/"));
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
        return session;
    }

    private ResultActions startWithdrawal(MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/api/v1/users/me/withdrawal").session(session).with(csrf()));
    }

    // 로그인된 세션 그대로 다시 인가를 받는다(탈퇴 본인 확인).
    private ResultActions reauthenticate(MockHttpSession session) throws Exception {
        return callback(session, startAuthorization(session));
    }

    private String startAuthorization(MockHttpSession session) throws Exception {
        return state(mockMvc.perform(get("/oauth2/authorization/naver").session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn());
    }

    private ResultActions callback(MockHttpSession session, String state) throws Exception {
        return mockMvc.perform(get("/login/oauth2/code/naver")
                .param("code", "test-code")
                .param("state", state)
                .session(session));
    }

    private static String state(MvcResult authorizationStart) {
        String value = UriComponentsBuilder.fromUriString(authorizationStart.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state");
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static DefaultOAuth2User naverUser(String id, String email) {
        Map<String, Object> attributes = Map.of(
                "resultcode", "00",
                "message", "success",
                "response", Map.of("id", id, "email", email, "name", "홍길동"));
        return new DefaultOAuth2User(Set.of(), attributes, "response");
    }
}
