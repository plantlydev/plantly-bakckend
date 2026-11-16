package project.plantly.globalTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

// 소셜 로그인 키(네이버·구글) 환경변수가 없으면 부팅이 실패해야 한다. 2026-11-13 실제 사고: 설정이 기본값 없는 ${NAVER_CLIENT_ID}
// 였을 때 환경변수가 없어도 앱이 떴고, "${NAVER_CLIENT_ID}" 글자가 client_id 로 나가 네이버가 오류 화면을 띄웠다.
// 스프링 컨텍스트를 새로 띄우지 않는다(공유 H2 와 엇갈림) — 운영 application.properties 를 환경변수 없이
// 부트의 Binder 로 직접 묶어, 실제 부팅과 같은 경로(파일 → 바인딩 → 검증)를 본다.
@DisplayName("OAuth2 클라이언트 키: 환경변수가 없으면 부팅 실패")
class OAuth2ClientKeysRequiredTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"naver", "google"})
    @DisplayName("환경변수가 없으면 키는 '${...}' 글자가 아니라 빈 값으로 묶인다")
    void missingEnvBindsToEmptyNotPlaceholderText(String registrationId) throws IOException {
        OAuth2ClientProperties.Registration registration = bindWithoutEnv().getRegistration().get(registrationId);

        assertThat(registration.getClientId()).isEmpty();
        assertThat(registration.getClientSecret()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"naver", "google"})
    @DisplayName("client-id 가 비면 부트 검증이 부팅을 막는다")
    void emptyClientIdFailsBootValidation(String registrationId) throws IOException {
        OAuth2ClientProperties properties = bindWithoutEnv();
        fillAllExcept(properties, registrationId);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(registrationId);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"naver", "google"})
    @DisplayName("client-secret 이 비면 OAuth2ClientConfig 가 부팅을 막는다")
    void emptyClientSecretFailsConfig(String registrationId) throws IOException {
        OAuth2ClientProperties properties = bindWithoutEnv();
        fillAllExcept(properties, registrationId);
        properties.getRegistration().get(registrationId).setClientId("id");

        assertThatThrownBy(() -> OAuth2ClientConfig.requireClientSecrets(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(registrationId)
                .hasMessageContaining("client-secret");
    }

    @Test
    @DisplayName("모든 제공자의 키가 있으면 통과한다")
    void presentKeysPass() throws IOException {
        OAuth2ClientProperties properties = bindWithoutEnv();
        fillAllExcept(properties, null);

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThatCode(() -> OAuth2ClientConfig.requireClientSecrets(properties)).doesNotThrowAnyException();
    }

    // 검사할 제공자 하나만 빈 채로 두고 나머지는 채운다 — 실패 메시지가 정확히 그 제공자를 가리키는지 본다.
    private static void fillAllExcept(OAuth2ClientProperties properties, String emptyRegistrationId) {
        properties.getRegistration().forEach((id, registration) -> {
            if (!id.equals(emptyRegistrationId)) {
                registration.setClientId("id");
                registration.setClientSecret("secret");
            }
        });
    }

    // 운영 application.properties 만 남기고 시스템 환경변수·시스템 속성을 뺀다 —
    // 개발 PC 나 CI 에 NAVER_CLIENT_ID·GOOGLE_CLIENT_ID 가 실제로 있어도 결과가 달라지지 않게.
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
