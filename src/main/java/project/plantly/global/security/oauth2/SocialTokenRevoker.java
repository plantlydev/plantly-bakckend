package project.plantly.global.security.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import project.plantly.domain.auth.social.SocialProvider;

import java.util.Map;

// 제공자 연동 해제(회원 탈퇴). 우리 앱이 받은 동의(토큰)를 제공자 쪽에서도 끊는다.
//  - 네이버: 토큰 엔드포인트에 grant_type=delete (https://nid.naver.com/oauth2.0/token). 실패해도 HTTP 200 이고
//    본문의 result 가 "success" 가 아니다.
//  - 구글: https://oauth2.googleapis.com/revoke 에 token. 성공 200, 실패 4xx.
// 토큰은 탈퇴 직전 재인가로 받은 것이다(DB 에 보관하지 않는다).
//
// 실패해도 던지지 않는다(false). 탈퇴는 이미 커밋된 뒤이고, 결정 사항이 '해제 실패해도 탈퇴 진행 + 로그'다.
// 빈으로 꺼낸 이유는 OAuth2ClientConfig 의 다른 통신 부품과 같다 — 흐름 테스트가 이것만 가짜로 바꾼다.
@Slf4j
public class SocialTokenRevoker {

    static final String NAVER_TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    static final String GOOGLE_REVOKE_URI = "https://oauth2.googleapis.com/revoke";

    private final RestClient restClient;

    public SocialTokenRevoker(RestClient restClient) {
        this.restClient = restClient;
    }

    public boolean revoke(SocialProvider provider, OAuth2AuthorizedClient client) {
        try {
            switch (provider) {
                case NAVER -> revokeNaver(client);
                case GOOGLE -> revokeGoogle(client);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("소셜 연동 해제 실패 provider={}: {}", provider, e.getMessage());
            return false;
        }
    }

    private void revokeNaver(OAuth2AuthorizedClient client) {
        ClientRegistration registration = client.getClientRegistration();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "delete");
        form.add("client_id", registration.getClientId());
        form.add("client_secret", registration.getClientSecret());
        form.add("access_token", client.getAccessToken().getTokenValue());
        form.add("service_provider", "NAVER");

        Map<?, ?> body = restClient.post()
                .uri(NAVER_TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (body == null || !"success".equals(body.get("result"))) {
            throw new IllegalStateException("네이버 연동 해제 응답이 성공이 아니다: error="
                    + (body == null ? null : body.get("error")));
        }
    }

    private void revokeGoogle(OAuth2AuthorizedClient client) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", client.getAccessToken().getTokenValue());

        restClient.post()
                .uri(GOOGLE_REVOKE_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
