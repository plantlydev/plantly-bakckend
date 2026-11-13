package project.plantly.global.security.oauth2;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;

import java.util.Map;

// 네이버 사용자 정보(/v1/nid/me) 응답 변환.
// 응답은 {"resultcode":"00","message":"success","response":{"id":…,"email":…,"name":…}} 처럼 response 로 감싸져 온다.
// 받는 항목은 이메일·이름뿐이다(휴대폰 미수집 — 개발자센터의 제공 정보 설정이 기준이고 scope 파라미터는 쓰지 않는다).
// email 은 네이버 메일이 아닐 수 있다(연락처 이메일). 우리 쪽은 전체 문자열로만 비교한다.
@Component
public class NaverProfileExtractor implements SocialProfileExtractor {

    @Override
    public SocialProvider provider() {
        return SocialProvider.NAVER;
    }

    @Override
    public SocialProfile extract(Map<String, Object> attributes) {
        if (!(attributes.get("response") instanceof Map<?, ?> response)) {
            throw SocialLoginErrorCode.SOCIAL_LOGIN_FAILED.exception("네이버 응답에 response 가 없다");
        }

        String id = text(response.get("id"));
        String email = text(response.get("email"));
        if (id == null || email == null) {
            // 이메일은 개발자센터에서 필수 항목이라 정상 흐름에서는 빠지지 않는다. 빠졌다면 앱 설정을 확인한다.
            throw SocialLoginErrorCode.SOCIAL_LOGIN_FAILED.exception("네이버 응답에 id 또는 email 이 없다");
        }
        return new SocialProfile(SocialProvider.NAVER, id, email, text(response.get("name")));
    }

    private static String text(Object value) {
        return (value instanceof String s && StringUtils.hasText(s)) ? s.trim() : null;
    }
}
