package project.plantly.authTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.consent.ConsentType;
import project.plantly.domain.user.consent.UserConsent;
import project.plantly.domain.user.consent.repository.UserConsentRepository;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.security.oauth2.PendingSocialSignupStore;
import project.plantly.global.security.oauth2.PendingSocialSignupStore.PendingSocialSignup;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 네이버 신규 가입 전체 흐름: 콜백(가입 필요) → 가입 대기 조회 → 동의·가입 → 재인가로 로그인.
// NaverOAuth2LoginFlowTest 와 같이 네이버와 통신하는 두 호출만 가짜로 바꾸고 나머지는 실제 보안 설정 그대로 돈다.
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class NaverSocialSignupFlowTest {

    private static final String FRONT = "http://localhost:3000";
    private static final String NAVER_ID = "naver-1";
    private static final String EMAIL = "member@naver.com";
    private static final List<String> REQUIRED_ONLY = List.of("AGE_OVER_14", "TERMS_OF_SERVICE", "PRIVACY_COLLECTION");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SocialAccountRepository socialAccountRepository;
    @Autowired
    private UserConsentRepository userConsentRepository;
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
        given(userInfoClient.loadUser(any())).willReturn(naverUser("홍길동"));
    }

    @AfterEach
    void cleanUp() {
        databaseCleanup.execute();
    }

    @Test
    @DisplayName("처음 온 사람은 가입 화면으로 가고, 세션에 가입 대기 정보가 생긴다 — 로그인은 아니다")
    void newcomer_redirectedToSignup() throws Exception {
        MockHttpSession session = new MockHttpSession();
        loginWithNaver(session).andExpect(redirectedUrl(FRONT + "/signup/social"));

        assertThat(session.getAttribute(PendingSocialSignupStore.SESSION_ATTRIBUTE)).isNotNull();
        assertNotLoggedIn(session);
        assertThat(userRepository.count()).isZero();

        // 가입 대기는 인증이 아니다 — 보호된 API 는 여전히 401.
        mockMvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("가입 대기 조회: 네이버가 준 이메일·이름과 동의 항목(화면 순서·필수 여부·버전)")
    void pending_showsProfileAndConsents() throws Exception {
        MockHttpSession session = pendingSession();

        mockMvc.perform(get("/api/v1/auth/social/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("NAVER"))
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.consents.length()").value(4))
                .andExpect(jsonPath("$.data.consents[0].type").value("AGE_OVER_14"))
                .andExpect(jsonPath("$.data.consents[0].required").value(true))
                .andExpect(jsonPath("$.data.consents[0].version").value("v1"))
                .andExpect(jsonPath("$.data.consents[3].type").value("MARKETING"))
                .andExpect(jsonPath("$.data.consents[3].required").value(false));
    }

    @Test
    @DisplayName("가입 대기 정보가 없으면 401 SIGNUP_SESSION_EXPIRED")
    void pending_withoutPending_401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/social/pending"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNUP_SESSION_EXPIRED"));
    }

    @Test
    @DisplayName("가입 대기 유효 시간이 지났으면 401 SIGNUP_SESSION_EXPIRED — 가입도 막힌다")
    void pending_expired_401() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingSocialSignupStore.SESSION_ATTRIBUTE, new PendingSocialSignup(
                new SocialProfile(SocialProvider.NAVER, NAVER_ID, EMAIL, "홍길동"), Instant.now().minusSeconds(1)));

        mockMvc.perform(get("/api/v1/auth/social/pending").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNUP_SESSION_EXPIRED"));
        signup(session, REQUIRED_ONLY, null)
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("가입 → 재인가로 로그인: 회원·연결·동의 기록이 생기고, 같은 네이버 계정으로 다시 오면 로그인된다")
    void signup_thenReauthorize_logsIn() throws Exception {
        MockHttpSession session = pendingSession();

        signup(session, REQUIRED_ONLY, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loginUrl").value("/oauth2/authorization/naver"));

        // 회원: 네이버 이메일·이름, 휴대폰 없음.
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getName()).isEqualTo("홍길동");
        assertThat(user.getPhone()).isNull();
        // 연결
        assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, NAVER_ID))
                .get().extracting(SocialAccount::getUserId).isEqualTo(user.getId());
        // 동의 기록: 모든 항목, 마케팅은 미동의로.
        assertThat(userConsentRepository.findByUserIdOrderByIdAsc(user.getId()))
                .extracting(UserConsent::getConsentType, UserConsent::isAgreed, UserConsent::getVersion)
                .containsExactly(
                        tuple(ConsentType.AGE_OVER_14, true, "v1"),
                        tuple(ConsentType.TERMS_OF_SERVICE, true, "v1"),
                        tuple(ConsentType.PRIVACY_COLLECTION, true, "v1"),
                        tuple(ConsentType.MARKETING, false, "v1"));

        // 가입 API 는 로그인시키지 않고, 가입 대기 정보를 지운다.
        assertNotLoggedIn(session);
        assertThat(session.getAttribute(PendingSocialSignupStore.SESSION_ATTRIBUTE)).isNull();

        // 재인가: 이번엔 연결된 회원이라 로그인된다.
        loginWithNaver(session).andExpect(redirectedUrl(FRONT + "/"));
        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    @DisplayName("마케팅에 동의하면 agreed=true 로 남는다")
    void signup_withMarketing() throws Exception {
        MockHttpSession session = pendingSession();

        signup(session, List.of("AGE_OVER_14", "TERMS_OF_SERVICE", "PRIVACY_COLLECTION", "MARKETING"), null)
                .andExpect(status().isCreated());

        Long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        assertThat(userConsentRepository.findByUserIdOrderByIdAsc(userId)).allMatch(UserConsent::isAgreed);
    }

    @Test
    @DisplayName("필수 항목이 빠지면 400 REQUIRED_CONSENT_MISSING — 아무것도 만들지 않고, 가입 대기는 남아 다시 시도할 수 있다")
    void signup_requiredConsentMissing() throws Exception {
        MockHttpSession session = pendingSession();

        signup(session, List.of("TERMS_OF_SERVICE", "PRIVACY_COLLECTION"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUIRED_CONSENT_MISSING"));

        assertThat(userRepository.count()).isZero();
        assertThat(socialAccountRepository.count()).isZero();
        assertThat(userConsentRepository.count()).isZero();
        assertThat(session.getAttribute(PendingSocialSignupStore.SESSION_ATTRIBUTE)).isNotNull();
    }

    @Test
    @DisplayName("모르는 동의 항목 이름은 400")
    void signup_unknownConsentType() throws Exception {
        MockHttpSession session = pendingSession();

        signup(session, List.of("AGE_OVER_14", "TERMS_OF_SERVICE", "PRIVACY_COLLECTION", "SOMETHING"), null)
                .andExpect(status().isBadRequest());
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("네이버가 이름을 주지 않았으면 가입 화면에서 받은 이름을 쓴다. 그것도 없으면 400 NAME_REQUIRED")
    void signup_nameFromRequestWhenNaverHasNone() throws Exception {
        given(userInfoClient.loadUser(any())).willReturn(naverUser(null));
        MockHttpSession session = pendingSession();

        mockMvc.perform(get("/api/v1/auth/social/pending").session(session))
                .andExpect(jsonPath("$.data.name").doesNotExist());

        signup(session, REQUIRED_ONLY, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NAME_REQUIRED"));

        signup(session, REQUIRED_ONLY, "김철수")
                .andExpect(status().isCreated());
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("대기 중에 같은 이메일의 회원이 생겼으면 409 EMAIL_IN_USE")
    void signup_emailTakenMeanwhile() throws Exception {
        MockHttpSession session = pendingSession();
        userRepository.save(User.create(EMAIL, "encoded", "다른사람", "01012345678"));

        signup(session, REQUIRED_ONLY, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        assertThat(socialAccountRepository.count()).isZero();
    }

    @Test
    @DisplayName("가입을 두 번 보내면 두 번째는 401 — 첫 가입이 가입 대기를 지웠다. 회원은 하나다")
    void signup_twice() throws Exception {
        MockHttpSession session = pendingSession();

        signup(session, REQUIRED_ONLY, null).andExpect(status().isCreated());
        signup(session, REQUIRED_ONLY, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNUP_SESSION_EXPIRED"));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("가입은 변경 요청이라 CSRF 토큰 없이는 403")
    void signup_requiresCsrf() throws Exception {
        MockHttpSession session = pendingSession();

        mockMvc.perform(post("/api/v1/auth/social/signup").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUIRED_ONLY, null)))
                .andExpect(status().isForbidden());
        assertThat(userRepository.count()).isZero();
    }

    // ---- 헬퍼 ----

    // 인가 시작 → 네이버 콜백. 같은 세션을 이어 쓴다(가입 뒤 재인가가 같은 브라우저 세션에서 일어나는 것과 같다).
    private ResultActions loginWithNaver(MockHttpSession session) throws Exception {
        MvcResult authorization = mockMvc.perform(get("/oauth2/authorization/naver").session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String state = UriComponentsBuilder.fromUriString(authorization.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state");
        return mockMvc.perform(get("/login/oauth2/code/naver")
                .param("code", "test-code")
                .param("state", URLDecoder.decode(state, StandardCharsets.UTF_8))
                .session(session));
    }

    private MockHttpSession pendingSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        loginWithNaver(session).andExpect(redirectedUrl(FRONT + "/signup/social"));
        return session;
    }

    private ResultActions signup(MockHttpSession session, List<String> agreements, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/social/signup").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(agreements, name)));
    }

    private String body(List<String> agreements, String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("agreements", agreements);
        if (name != null) {
            body.put("name", name);
        }
        return objectMapper.writeValueAsString(body);
    }

    private static void assertNotLoggedIn(MockHttpSession session) {
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    private static DefaultOAuth2User naverUser(String name) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", NAVER_ID);
        response.put("email", EMAIL);
        if (name != null) {
            response.put("name", name);
        }
        return new DefaultOAuth2User(Set.of(), Map.of("resultcode", "00", "message", "success", "response", response),
                "response");
    }
}
