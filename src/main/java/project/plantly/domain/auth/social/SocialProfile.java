package project.plantly.domain.auth.social;

import java.util.Objects;

// 외부 제공자(네이버·구글)가 준 사용자 정보를 제공자와 무관한 모양으로 옮긴 값.
// 번역층(global/security/oauth2 의 extractor)이 만들고, 회원 도메인층(SocialLoginService)이 받는다.
// OAuth2 타입을 담지 않는다 — 도메인층이 Spring Security OAuth2 를 모르게 하는 경계다.
//
// name 은 비어 있을 수 있다. 로그인 판정에는 쓰지 않고, 가입 화면(3단계)에서 미리 채우는 용도다.
public record SocialProfile(
        SocialProvider provider,
        String providerUserId,
        String email,
        String name
) {
    public SocialProfile {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerUserId, "providerUserId");
        Objects.requireNonNull(email, "email");
    }
}
