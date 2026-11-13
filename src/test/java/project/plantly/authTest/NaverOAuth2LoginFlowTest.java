package project.plantly.authTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import project.plantly.DatabaseCleanup;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.security.UserPrincipal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 네이버 로그인 전체 필터 흐름: 인가 요청 → 콜백 → 토큰 교환 → 사용자 정보 → 판정 → 세션 저장 → 리다이렉트.
// 네이버와 통신하는 두 호출(토큰 교환, 사용자 정보)만 가짜로 바꾸고 나머지는 실제 보안 설정 그대로 돈다.
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class NaverOAuth2LoginFlowTest {

    private static final String FRONT = "http://localhost:3000";
    private static final String NAVER_ID = "naver-1";
    private static final String EMAIL = "member@naver.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private DatabaseCleanup databaseCleanup;

    @MockitoBean
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;

    @MockitoBean
    private DefaultOAuth2UserService userInfoClient;

    @BeforeEach
    void stubNaver() {
        given(tokenResponseClient.getTokenResponse(any())).willReturn(OAuth2AccessTokenResponse
                .withToken("naver-access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(3600)
                .build());
        given(userInfoClient.loadUser(any())).willReturn(naverUser(NAVER_ID, EMAIL));
    }

    @AfterEach
    void cleanUp() {
        databaseCleanup.execute();
    }

    @Test
    @DisplayName("인가 시작은 네이버 로그인 화면으로 보내고, redirect_uri 는 프론트 주소 기준이다")
    void authorizationRedirectsToNaver() throws Exception {
        UriComponents location = startAuthorization().location();

        assertThat(location.getHost()).isEqualTo("nid.naver.com");
        assertThat(location.getPath()).isEqualTo("/oauth2.0/authorize");
        assertThat(param(location, "response_type")).isEqualTo("code");
        assertThat(param(location, "client_id")).isEqualTo("test-naver-client-id");
        assertThat(param(location, "redirect_uri")).isEqualTo(FRONT + "/login/oauth2/code/naver");
        assertThat(param(location, "state")).isNotBlank();
    }

    @Test
    @DisplayName("연결된 활성 회원은 로그인되고 프론트로 이동한다 — 이후 요청의 컨트롤러는 UserPrincipal 을 받는다")
    void linkedMember_logsIn() throws Exception {
        User user = userRepository.save(User.create(EMAIL, "encoded", "홍길동", null));
        socialAccountRepository.save(SocialAccount.link(user.getId(), SocialProvider.NAVER, NAVER_ID, EMAIL));

        Authorization authorization = startAuthorization();
        String sessionIdBeforeLogin = authorization.session().getId();

        callback(authorization)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FRONT + "/"));

        MockHttpSession session = authorization.session();
        // 세션 고정 방지: 로그인하면서 세션 ID 가 바뀐다.
        assertThat(session.getId()).isNotEqualTo(sessionIdBeforeLogin);
        // 세션에 저장된 주체는 앱 회원 주체다.
        SecurityContext context = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(UserPrincipal.class);
        assertThat(context.getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_MEMBER");

        // 컨트롤러(@AuthenticationPrincipal UserPrincipal)가 그대로 동작한다.
        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    @DisplayName("액세스 토큰은 메모리 저장소가 아니라 세션에 둔다")
    void authorizedClientIsStoredInSession() throws Exception {
        User user = userRepository.save(User.create(EMAIL, "encoded", "홍길동", null));
        socialAccountRepository.save(SocialAccount.link(user.getId(), SocialProvider.NAVER, NAVER_ID, EMAIL));

        Authorization authorization = startAuthorization();
        callback(authorization).andExpect(redirectedUrl(FRONT + "/"));

        assertThat(authorization.session().getAttributeNames().asIterator())
                .toIterable()
                .anyMatch(name -> name.endsWith("HttpSessionOAuth2AuthorizedClientRepository.AUTHORIZED_CLIENTS"));
    }

    @Test
    @DisplayName("연결된 회원이 정지 상태면 로그인되지 않고 ACCOUNT_SUSPENDED 로 돌려보낸다")
    void suspendedMember_rejected() throws Exception {
        User user = userRepository.save(User.builder()
                .email(EMAIL).password("encoded").name("홍길동").userStatus(UserStatus.SUSPENDED).build());
        socialAccountRepository.save(SocialAccount.link(user.getId(), SocialProvider.NAVER, NAVER_ID, EMAIL));

        Authorization authorization = startAuthorization();
        callback(authorization).andExpect(redirectedUrl(FRONT + "/login?error=ACCOUNT_SUSPENDED"));

        assertNotLoggedIn(authorization.session());
    }

    @Test
    @DisplayName("연결 없이 같은 이메일의 회원이 있으면 EMAIL_IN_USE — 기존 회원은 그대로, 새 연결도 만들지 않는다")
    void emailAlreadyUsed_rejected() throws Exception {
        userRepository.save(User.create(EMAIL, "encoded", "홍길동", "01012345678"));

        Authorization authorization = startAuthorization();
        callback(authorization).andExpect(redirectedUrl(FRONT + "/login?error=EMAIL_IN_USE"));

        assertNotLoggedIn(authorization.session());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(socialAccountRepository.count()).isZero();
    }

    @Test
    @DisplayName("연결도 같은 이메일도 없으면 SIGNUP_REQUIRED — 회원을 만들지 않는다(가입은 3단계)")
    void newcomer_signupRequired() throws Exception {
        Authorization authorization = startAuthorization();
        callback(authorization).andExpect(redirectedUrl(FRONT + "/login?error=SIGNUP_REQUIRED"));

        assertNotLoggedIn(authorization.session());
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("네이버 화면에서 취소하면(access_denied) SOCIAL_LOGIN_FAILED, 토큰 교환도 하지 않는다")
    void userCancelled_failed() throws Exception {
        Authorization authorization = startAuthorization();

        mockMvc.perform(get("/login/oauth2/code/naver")
                        .param("error", "access_denied")
                        .param("error_description", "Canceled By User")
                        .param("state", authorization.state())
                        .session(authorization.session()))
                .andExpect(redirectedUrl(FRONT + "/login?error=SOCIAL_LOGIN_FAILED"));

        verify(tokenResponseClient, never()).getTokenResponse(any());
        assertNotLoggedIn(authorization.session());
    }

    @Test
    @DisplayName("사용자 정보 호출이 실패하면 SOCIAL_LOGIN_FAILED")
    void userInfoFailure_failed() throws Exception {
        given(userInfoClient.loadUser(any())).willThrow(
                new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response")));

        Authorization authorization = startAuthorization();
        callback(authorization).andExpect(redirectedUrl(FRONT + "/login?error=SOCIAL_LOGIN_FAILED"));
    }

    @Test
    @DisplayName("state 가 맞지 않는 콜백은 SOCIAL_LOGIN_FAILED")
    void stateMismatch_failed() throws Exception {
        Authorization authorization = startAuthorization();

        mockMvc.perform(get("/login/oauth2/code/naver")
                        .param("code", "test-code")
                        .param("state", "forged-state")
                        .session(authorization.session()))
                .andExpect(redirectedUrl(FRONT + "/login?error=SOCIAL_LOGIN_FAILED"));
    }

    @Test
    @DisplayName("미인증 API 는 그대로 401 JSON — oauth2Login 이 진입점을 네이버 리다이렉트로 바꾸지 않는다")
    void unauthenticatedApi_stays401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("백엔드에 스프링 기본 로그인 페이지가 생기지 않는다")
    void noDefaultLoginPage() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 헬퍼 ----

    private record Authorization(MockHttpSession session, UriComponents location, String state) {
    }

    private Authorization startAuthorization() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/naver"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        UriComponents location = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        return new Authorization(session, location, param(location, "state"));
    }

    private org.springframework.test.web.servlet.ResultActions callback(Authorization authorization) throws Exception {
        return mockMvc.perform(get("/login/oauth2/code/naver")
                .param("code", "test-code")
                .param("state", authorization.state())
                .session(authorization.session()));
    }

    private static String param(UriComponents uri, String name) {
        String value = uri.getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void assertNotLoggedIn(MockHttpSession session) {
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    private static DefaultOAuth2User naverUser(String id, String email) {
        Map<String, Object> attributes = Map.of(
                "resultcode", "00",
                "message", "success",
                "response", Map.of("id", id, "email", email, "name", "홍길동"));
        return new DefaultOAuth2User(Set.of(), attributes, "response");
    }
}
