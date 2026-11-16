package project.plantly.authTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.global.security.oauth2.GoogleProfileExtractor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleProfileExtractorTest {

    private final GoogleProfileExtractor extractor = new GoogleProfileExtractor();

    @Test
    @DisplayName("평평한 응답에서 sub·email·name 을 꺼낸다")
    void extractsFlatAttributes() {
        SocialProfile profile = extractor.extract(google("google-1", "member@gmail.com", true, "홍길동"));

        assertThat(profile).isEqualTo(
                new SocialProfile(SocialProvider.GOOGLE, "google-1", "member@gmail.com", "홍길동"));
    }

    @Test
    @DisplayName("Gmail 이 아닌 구글 계정 이메일도 그대로 받는다")
    void acceptsNonGmailEmail() {
        SocialProfile profile = extractor.extract(google("google-1", "hong@plantly.co.kr", true, null));

        assertThat(profile.email()).isEqualTo("hong@plantly.co.kr");
    }

    @Test
    @DisplayName("이름이 없으면 null — 가입 화면에서 입력받는다")
    void nameIsOptional() {
        SocialProfile profile = extractor.extract(google("google-1", "member@gmail.com", true, null));

        assertThat(profile.name()).isNull();
    }

    @Test
    @DisplayName("email_verified 가 문자열 \"true\" 여도 받는다")
    void acceptsStringVerified() {
        Map<String, Object> attributes = google("google-1", "member@gmail.com", true, null);
        attributes.put("email_verified", "true");

        assertThat(extractor.extract(attributes).email()).isEqualTo("member@gmail.com");
    }

    @Test
    @DisplayName("이메일이 확인되지 않은 계정은 SOCIAL_LOGIN_FAILED")
    void unverifiedEmail_fails() {
        assertThatThrownBy(() -> extractor.extract(google("google-1", "member@gmail.com", false, "홍길동")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo("SOCIAL_LOGIN_FAILED"));
    }

    @Test
    @DisplayName("email_verified 가 없으면 확인되지 않은 것으로 보고 SOCIAL_LOGIN_FAILED")
    void missingVerified_fails() {
        Map<String, Object> attributes = google("google-1", "member@gmail.com", true, null);
        attributes.remove("email_verified");

        assertThatThrownBy(() -> extractor.extract(attributes))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("이메일이 없거나 비어 있으면 SOCIAL_LOGIN_FAILED")
    void missingEmail_fails() {
        assertThatThrownBy(() -> extractor.extract(google("google-1", " ", true, null)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo("SOCIAL_LOGIN_FAILED"));
    }

    @Test
    @DisplayName("sub 가 없으면 SOCIAL_LOGIN_FAILED")
    void missingSub_fails() {
        assertThatThrownBy(() -> extractor.extract(google(null, "member@gmail.com", true, null)))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private static Map<String, Object> google(String sub, String email, boolean verified, String name) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", sub);
        attributes.put("email", email);
        attributes.put("email_verified", verified);
        attributes.put("name", name);
        attributes.put("picture", "https://lh3.googleusercontent.com/a/photo");
        return attributes;
    }
}
