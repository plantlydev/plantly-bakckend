package project.plantly.global.security.oauth2;

import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;

import java.util.Map;

// 제공자의 사용자 정보 응답을 공통 SocialProfile 로 바꾼다. 제공자별 응답 모양의 차이는 여기에만 가둔다.
// 제공자를 하나 늘릴 때는 구현체(@Component)와 설정만 추가한다.
public interface SocialProfileExtractor {

    SocialProvider provider();

    // 식별자·이메일이 없으면 SocialLoginErrorCode.SOCIAL_LOGIN_FAILED 예외를 던진다.
    SocialProfile extract(Map<String, Object> attributes);
}
