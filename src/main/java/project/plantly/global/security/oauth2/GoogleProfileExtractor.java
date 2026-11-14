package project.plantly.global.security.oauth2;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;

import java.util.Map;

// 구글 사용자 정보(https://www.googleapis.com/oauth2/v3/userinfo) 응답 변환.
// 응답은 감싸지 않은 평평한 모양이다: {"sub":…,"email":…,"email_verified":true,"name":…,"picture":…}.
// scope 는 profile·email 만 쓴다(openid 제외) — openid 가 있으면 OIDC 경로(OidcUserService)로 빠져
// PlantlyOAuth2UserService 를 거치지 않는다.
//
// email_verified 가 true 가 아니면 거절한다. 회원 충돌(EMAIL_IN_USE)을 이메일 전체 문자열로 판정하므로,
// 확인 안 된 이메일을 받으면 남의 이메일로 먼저 가입해 그 사람의 가입을 막을 수 있다.
@Component
public class GoogleProfileExtractor implements SocialProfileExtractor {

    @Override
    public SocialProvider provider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public SocialProfile extract(Map<String, Object> attributes) {
        String id = text(attributes.get("sub"));
        String email = text(attributes.get("email"));
        if (id == null || email == null) {
            // email scope 를 요청하므로 정상 흐름에서는 빠지지 않는다. 빠졌다면 scope 설정을 확인한다.
            throw SocialLoginErrorCode.SOCIAL_LOGIN_FAILED.exception("구글 응답에 sub 또는 email 이 없다");
        }
        if (!isTrue(attributes.get("email_verified"))) {
            throw SocialLoginErrorCode.SOCIAL_LOGIN_FAILED.exception("구글 이메일이 확인되지 않은 계정이다");
        }
        return new SocialProfile(SocialProvider.GOOGLE, id, email, text(attributes.get("name")));
    }

    // v3 userinfo 는 JSON boolean 을 주지만, 문자열 "true" 로 오는 경우도 받아 준다.
    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof String s && Boolean.parseBoolean(s.trim()));
    }

    private static String text(Object value) {
        return (value instanceof String s && StringUtils.hasText(s)) ? s.trim() : null;
    }
}
