package project.plantly.authTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.global.security.oauth2.NaverProfileExtractor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverProfileExtractorTest {

    private final NaverProfileExtractor extractor = new NaverProfileExtractor();

    @Test
    @DisplayName("response 안의 id·email·name 을 꺼낸다")
    void extractsFromResponse() {
        SocialProfile profile = extractor.extract(naver(Map.of(
                "id", "naver-1", "email", "member@naver.com", "name", "홍길동")));

        assertThat(profile).isEqualTo(
                new SocialProfile(SocialProvider.NAVER, "naver-1", "member@naver.com", "홍길동"));
    }

    @Test
    @DisplayName("이메일은 네이버 메일이 아니어도 그대로 받는다(연락처 이메일)")
    void acceptsNonNaverEmail() {
        SocialProfile profile = extractor.extract(naver(Map.of("id", "naver-1", "email", "hong@plantly.co.kr")));

        assertThat(profile.email()).isEqualTo("hong@plantly.co.kr");
    }

    @Test
    @DisplayName("이름이 없으면 null — 로그인 판정에는 쓰지 않는다")
    void nameIsOptional() {
        SocialProfile profile = extractor.extract(naver(Map.of("id", "naver-1", "email", "member@naver.com")));

        assertThat(profile.name()).isNull();
    }

    @Test
    @DisplayName("이메일이 없거나 비어 있으면 SOCIAL_LOGIN_FAILED")
    void missingEmail_fails() {
        Map<String, Object> response = new HashMap<>();
        response.put("id", "naver-1");
        response.put("email", " ");

        assertThatThrownBy(() -> extractor.extract(naver(response)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo("SOCIAL_LOGIN_FAILED"));
    }

    @Test
    @DisplayName("id 가 없으면 SOCIAL_LOGIN_FAILED")
    void missingId_fails() {
        assertThatThrownBy(() -> extractor.extract(naver(Map.of("email", "member@naver.com"))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("response 로 감싸져 있지 않으면 SOCIAL_LOGIN_FAILED")
    void missingResponse_fails() {
        assertThatThrownBy(() -> extractor.extract(Map.of("id", "naver-1", "email", "member@naver.com")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private static Map<String, Object> naver(Map<String, Object> response) {
        return Map.of("resultcode", "00", "message", "success", "response", response);
    }
}
