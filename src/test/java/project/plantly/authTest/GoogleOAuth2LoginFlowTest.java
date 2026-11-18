package project.plantly.authTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import project.plantly.DatabaseCleanup;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.security.UserPrincipal;
import project.plantly.global.security.oauth2.SocialTokenRevoker;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 구글 로그인 필터 흐름. 공통 흐름(세션 고정 방지·정지·state·취소·401 진입점 등)은 NaverOAuth2LoginFlowTest·
// NaverSocialSignupFlowTest 가 이미 본다. 여기서는 구글에서만 달라지는 것을 본다:
//  - scope 에 openid 가 없어 OIDC 가 아니라 OAuth2 경로(PlantlyOAuth2UserService)를 탄다
//  - 구글 응답 변환·이메일 미확인 거절
//  - 제공자를 넘나드는 이메일 충돌(네이버로 가입한 이메일로 구글 로그인)
// 두 가짜 빈 구성이 네이버 테스트와 같아서 스프링 컨텍스트를 새로 띄우지 않는다.
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class GoogleOAuth2LoginFlowTest {

    private static final String FRONT = "http://localhost:3000";
    private static final String GOOGLE_SUB = "google-1";
    private static final String EMAIL = "member@gmail.com";
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
    private DatabaseCleanup databaseCleanup;

    @MockitoBean
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    @MockitoBean
    private DefaultOAuth2UserService userInfoClient;

    // 연동 해제(탈퇴) 호출. 이 테스트는 쓰지 않지만, 소셜 흐름 테스트끼리 MockitoBean 구성을 맞춰 컨텍스트를 공유한다.
    @MockitoBean
    private SocialTokenRevoker tokenRevoker;

    @BeforeEach
    void stubGoogle() {
        // id_token 없이 액세스 토큰만 준다 — OIDC 경로였다면 id_token 이 없어 로그인이 실패한다.
        given(tokenResponseClient.getTokenResponse(any())).willReturn(OAuth2AccessTokenResponse
                .withToken("google-access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(3600)
                .build());
        given(userInfoClient.loadUser(any())).willReturn(googleUser(GOOGLE_SUB, EMAIL, true));
    }

    @AfterEach
    void cleanUp() {
        databaseCleanup.execute();
    }

    @Test
    @DisplayName("인가 시작은 구글 로그인 화면으로 보낸다 — scope 는 profile·email 뿐(openid·nonce 없음), redirect_uri 는 프론트 기준")
    void authorizationRedirectsToGoogle() throws Exception {
        UriComponents location = startAuthorization(new MockHttpSession()).location();

        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        assertThat(location.getPath()).isEqualTo("/o/oauth2/v2/auth");
        assertThat(param(location, "response_type")).isEqualTo("code");
        assertThat(param(location, "client_id")).isEqualTo("test-google-client-id");
        assertThat(param(location, "redirect_uri")).isEqualTo(FRONT + "/login/oauth2/code/google");
        assertThat(param(location, "scope")).isEqualTo("profile email");
        assertThat(param(location, "nonce")).isNull();
        // refresh token 은 요청하지 않는다(탈퇴 작업에서 정한다).
        assertThat(param(location, "access_type")).isNull();
    }

    @Test
    @DisplayName("연결된 회원은 로그인된다 — 사용자 정보는 우리 OAuth2 경로가 구글 userinfo 로 불렀다")
    void linkedMember_logsIn() throws Exception {
        User user = userRepository.save(User.create(EMAIL, "encoded", "홍길동", null));
        socialAccountRepository.save(SocialAccount.link(user.getId(), SocialProvider.GOOGLE, GOOGLE_SUB, EMAIL));

        MockHttpSession session = new MockHttpSession();
        loginWithGoogle(session).andExpect(redirectedUrl(FRONT + "/"));

        SecurityContext context = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(UserPrincipal.class);
        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL));

        ArgumentCaptor<OAuth2UserRequest> request = ArgumentCaptor.forClass(OAuth2UserRequest.class);
        verify(userInfoClient).loadUser(request.capture());
        assertThat(request.getValue().getClientRegistration().getRegistrationId()).isEqualTo("google");
        assertThat(request.getValue().getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://www.googleapis.com/oauth2/v3/userinfo");
    }

    @Test
    @DisplayName("신규 → 가입 → 구글 재인가로 로그인: 연결은 GOOGLE·sub 로 생긴다")
    void newcomer_signup_thenReauthorize_logsIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        loginWithGoogle(session).andExpect(redirectedUrl(FRONT + "/signup/social"));

        mockMvc.perform(get("/api/v1/auth/social/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.email").value(EMAIL));

        mockMvc.perform(post("/api/v1/auth/social/signup").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("agreements", REQUIRED_ONLY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loginUrl").value("/oauth2/authorization/google"));

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, GOOGLE_SUB))
                .get().extracting(SocialAccount::getUserId).isEqualTo(user.getId());

        loginWithGoogle(session).andExpect(redirectedUrl(FRONT + "/"));
        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    @DisplayName("네이버로 가입한 이메일로 구글 로그인하면 EMAIL_IN_USE — 구글 연결을 만들지 않는다")
    void emailLinkedToNaver_rejected() throws Exception {
        User user = userRepository.save(User.create(EMAIL, "encoded", "홍길동", null));
        socialAccountRepository.save(SocialAccount.link(user.getId(), SocialProvider.NAVER, "naver-1", EMAIL));

        MockHttpSession session = new MockHttpSession();
        loginWithGoogle(session).andExpect(redirectedUrl(FRONT + "/login?error=EMAIL_IN_USE"));

        assertNotLoggedIn(session);
        assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, GOOGLE_SUB)).isEmpty();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이메일이 확인되지 않은 구글 계정은 SOCIAL_LOGIN_FAILED — 가입 화면으로도 보내지 않는다")
    void unverifiedEmail_failed() throws Exception {
        given(userInfoClient.loadUser(any())).willReturn(googleUser(GOOGLE_SUB, EMAIL, false));

        MockHttpSession session = new MockHttpSession();
        loginWithGoogle(session).andExpect(redirectedUrl(FRONT + "/login?error=SOCIAL_LOGIN_FAILED"));

        assertNotLoggedIn(session);
        mockMvc.perform(get("/api/v1/auth/social/pending").session(session))
                .andExpect(status().isUnauthorized());
    }

    // ---- 헬퍼 ----

    private record Authorization(UriComponents location, String state) {
    }

    private Authorization startAuthorization(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google").session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        UriComponents location = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build();
        return new Authorization(location, param(location, "state"));
    }

    // 인가 시작 → 구글 콜백. 같은 세션을 이어 쓴다.
    private ResultActions loginWithGoogle(MockHttpSession session) throws Exception {
        Authorization authorization = startAuthorization(session);
        return mockMvc.perform(get("/login/oauth2/code/google")
                .param("code", "test-code")
                .param("state", authorization.state())
                .session(session));
    }

    private static String param(UriComponents uri, String name) {
        String value = uri.getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void assertNotLoggedIn(MockHttpSession session) {
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    private static DefaultOAuth2User googleUser(String sub, String email, boolean verified) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", sub);
        attributes.put("email", email);
        attributes.put("email_verified", verified);
        attributes.put("name", "홍길동");
        attributes.put("picture", "https://lh3.googleusercontent.com/a/photo");
        return new DefaultOAuth2User(Set.of(), attributes, "sub");
    }
}
