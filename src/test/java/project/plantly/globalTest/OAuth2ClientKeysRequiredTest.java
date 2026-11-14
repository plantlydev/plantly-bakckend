package project.plantly.globalTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import project.plantly.global.config.OAuth2ClientConfig;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 네이버 키 환경변수가 없으면 부팅이 실패해야 한다. 2026-11-13 실제 사고: 설정이 기본값 없는 ${NAVER_CLIENT_ID}
// 였을 때 환경변수가 없어도 앱이 떴고, "${NAVER_CLIENT_ID}" 글자가 client_id 로 나가 네이버가 오류 화면을 띄웠다.
// 스프링 컨텍스트를 새로 띄우지 않는다(공유 H2 와 엇갈림) — 운영 application.properties 를 환경변수 없이
// 부트의 Binder 로 직접 묶어, 실제 부팅과 같은 경로(파일 → 바인딩 → 검증)를 본다.
@DisplayName("OAuth2 클라이언트 키: 환경변수가 없으면 부팅 실패")
class OAuth2ClientKeysRequiredTest {

    @Test
    @DisplayName("환경변수가 없으면 키는 '${...}' 글자가 아니라 빈 값으로 묶인다")
    void missingEnvBindsToEmptyNotPlaceholderText() throws IOException {
        OAuth2ClientProperties.Registration naver = bindWithoutEnv().getRegistration().get("naver");

        assertThat(naver.getClientId()).isEmpty();
        assertThat(naver.getClientSecret()).isEmpty();
    }

    @Test
    @DisplayName("client-id 가 비면 부트 검증이 부팅을 막는다")
    void emptyClientIdFailsBootValidation() throws IOException {
        OAuth2ClientProperties properties = bindWithoutEnv();

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("naver");
    }

    @Test
    @DisplayName("client-secret 이 비면 OAuth2ClientConfig 가 부팅을 막는다")
    void emptyClientSecretFailsConfig() throws IOException {
        OAuth2ClientProperties properties = bindWithoutEnv();

        assertThatThrownBy(() -> OAuth2ClientConfig.requireClientSecrets(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("naver")
                .hasMessageContaining("client-secret");
    }

    @Test
    @DisplayName("키가 모두 있으면 통과한다")
    void presentKeysPass() throws IOException {
        OAuth2ClientProperties properties = bindWithoutEnv();
        OAuth2ClientProperties.Registration naver = properties.getRegistration().get("naver");
        naver.setClientId("id");
        naver.setClientSecret("secret");

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThatCode(() -> OAuth2ClientConfig.requireClientSecrets(properties)).doesNotThrowAnyException();
    }

    // 운영 application.properties 만 남기고 시스템 환경변수·시스템 속성을 뺀다 —
    // 개발 PC 나 CI 에 NAVER_CLIENT_ID 가 실제로 있어도 결과가 달라지지 않게.
    private static OAuth2ClientProperties bindWithoutEnv() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new ResourcePropertySource(new ClassPathResource("application.properties")));

        return Binder.get(environment)
                .bind("spring.security.oauth2.client", OAuth2ClientProperties.class)
                .get();
    }
}
