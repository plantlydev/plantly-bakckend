package project.plantly.authTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.global.security.oauth2.SocialTokenRevoker;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 연동 해제의 실제 HTTP 형태(주소·폼 파라미터)와 응답 판정. 흐름 테스트는 이 빈을 가짜로 바꾸므로 HTTP 는 여기서만 본다.
// MockRestServiceServer 로 붙이므로 외부 네트워크는 타지 않는다.
@DisplayName("SocialTokenRevoker: 제공자 연동 해제 호출")
class SocialTokenRevokerTest {

    private MockRestServiceServer server;
    private SocialTokenRevoker revoker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        revoker = new SocialTokenRevoker(builder.build());
    }

    @Test
    @DisplayName("네이버: 토큰 엔드포인트에 grant_type=delete 와 클라이언트 키·토큰을 폼으로 보내고, result=success 면 성공")
    void naverSuccess() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "delete",
                        "client_id", "naver-id",
                        "client_secret", "naver-secret",
                        "access_token", "naver-token",
                        "service_provider", "NAVER")))
                .andRespond(withSuccess("{\"access_token\":\"naver-token\",\"result\":\"success\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(revoker.revoke(SocialProvider.NAVER, client("naver", "naver-id", "naver-secret", "naver-token")))
                .isTrue();
        server.verify();
    }

    @Test
    @DisplayName("네이버: HTTP 200 이어도 본문이 오류면 실패(false) — 예외를 던지지 않는다")
    void naverErrorBody() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
                .andRespond(withSuccess("{\"error\":\"invalid_request\",\"error_description\":\"no valid data\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(revoker.revoke(SocialProvider.NAVER, client("naver", "naver-id", "naver-secret", "naver-token")))
                .isFalse();
    }

    @Test
    @DisplayName("구글: revoke 엔드포인트에 token 을 폼으로 보내고, 200 이면 성공")
    void googleSuccess() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of("token", "google-token")))
                .andRespond(withSuccess());

        assertThat(revoker.revoke(SocialProvider.GOOGLE, client("google", "google-id", "google-secret", "google-token")))
                .isTrue();
        server.verify();
    }

    @Test
    @DisplayName("구글: 4xx 면 실패(false) — 예외를 던지지 않는다")
    void googleError() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_token\"}"));

        assertThat(revoker.revoke(SocialProvider.GOOGLE, client("google", "google-id", "google-secret", "google-token")))
                .isFalse();
    }

    private static OAuth2AuthorizedClient client(String registrationId, String clientId, String secret, String token) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId(clientId)
                .clientSecret(secret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:3000/login/oauth2/code/" + registrationId)
                .authorizationUri("https://provider.test/authorize")
                .tokenUri("https://provider.test/token")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                Instant.now(), Instant.now().plusSeconds(3600));
        return new OAuth2AuthorizedClient(registration, "principal", accessToken);
    }
}
