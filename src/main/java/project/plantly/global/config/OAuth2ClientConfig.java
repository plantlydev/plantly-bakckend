package project.plantly.global.config;

import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.util.StringUtils;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;
import project.plantly.global.security.oauth2.SocialTokenRevoker;

import java.time.Duration;

// 소셜 로그인이 제공자와 통신하는 부품. 빈으로 꺼내 둔 이유는 테스트가 이 호출들(토큰 교환·사용자 정보·탈퇴 연동 해제)만
// 가짜로 바꿔 실제 필터 흐름(인가 요청 → 콜백 → 판정 → 세션 저장, 탈퇴 재인가)을 그대로 돌리기 위해서다.
@Configuration
public class OAuth2ClientConfig {

    // 키가 빠진 채로 뜨면 로그인 버튼을 눌러야 비로소 제공자 쪽에서 엉뚱한 화면으로 실패한다.
    // 부팅 시점에 죽이는 편이 낫다(국세청 키와 같은 원칙).
    public OAuth2ClientConfig(OAuth2ClientProperties properties) {
        requireClientSecrets(properties);
    }

    // client-id 가 빈 경우는 부트의 OAuth2ClientProperties.validate() 가 이미 부팅을 막는다.
    // client-secret 은 부트가 보지 않으므로 여기서 막는다. 설정은 ${NAVER_CLIENT_SECRET:} 처럼
    // 빈 기본값을 둬야 한다 — 기본값 없는 ${X} 는 환경변수가 없을 때 실패하지 않고 "${X}" 글자가 그대로 들어간다.
    public static void requireClientSecrets(OAuth2ClientProperties properties) {
        properties.getRegistration().forEach((id, registration) -> {
            if (!StringUtils.hasText(registration.getClientSecret())) {
                throw new IllegalStateException(
                        "소셜 로그인 '%s' 의 client-secret 이 비어 있습니다. 환경변수(예: NAVER_CLIENT_SECRET)를 주입하세요. "
                                .formatted(id)
                                + "로컬은 프로젝트 루트 .env 에 넣으면 application-local.properties 가 읽습니다.");
            }
        });
    }

    // 인가 코드 → 액세스 토큰 교환. Security 7 의 기본값인 RestClient 구현을 미리 쓴다
    // (6.5 의 기본 DefaultAuthorizationCodeTokenResponseClient 는 deprecated).
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient() {
        return new RestClientAuthorizationCodeTokenResponseClient();
    }

    // 액세스 토큰 → 사용자 정보 호출. PlantlyOAuth2UserService 가 이 결과를 받아 회원 판정을 한다.
    @Bean
    public DefaultOAuth2UserService oauth2UserInfoClient() {
        return new DefaultOAuth2UserService();
    }

    // 로그인 때 받은 액세스 토큰 보관소. 부트 기본값(InMemoryOAuth2AuthorizedClientService 기반)은
    // 로그인할 때마다 토큰을 메모리에 쌓고 지우지 않는다. 세션에 두면 세션과 함께 사라진다.
    // 토큰을 쓰는 곳은 회원 탈퇴의 연동 해제뿐이고, 그것도 탈퇴 직전 재인가로 막 받은 토큰을 쓴다(WithdrawalReauthHandler).
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    // 회원 탈퇴 때 제공자 연동 해제 호출. 탈퇴 요청(브라우저 콜백) 안에서 동기로 부르므로 짧은 타임아웃을 둔다 —
    // 제공자가 느려도 사용자가 오래 기다리지 않고, 실패는 로그만 남긴다(SocialTokenRevoker).
    @Bean
    public SocialTokenRevoker socialTokenRevoker() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(5));
        return new SocialTokenRevoker(RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build());
    }
}
