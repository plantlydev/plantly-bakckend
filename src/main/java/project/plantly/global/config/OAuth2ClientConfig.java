package project.plantly.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

// 소셜 로그인이 제공자와 통신하는 부품. 빈으로 꺼내 둔 이유는 테스트가 이 두 호출만 가짜로 바꿔
// 실제 필터 흐름(인가 요청 → 콜백 → 판정 → 세션 저장)을 그대로 돌리기 위해서다.
@Configuration
public class OAuth2ClientConfig {

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
    // 지금은 로그인 뒤 토큰을 쓰는 곳이 없다. 회원탈퇴 때 네이버 연동 해제에 쓸지는 탈퇴 작업에서 정한다.
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }
}
