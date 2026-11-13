package project.plantly.global.security.oauth2;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import project.plantly.domain.auth.social.SocialProfile;

// 판정이 '가입 필요'일 때 던진다. 로그인 필터는 이것도 인증 실패로 보고 실패 핸들러로 넘기는데,
// 실패 핸들러가 가입 대기 정보를 세션에 담을 수 있도록 제공자가 확인해 준 프로필을 싣고 간다.
public class SocialSignupRequiredException extends OAuth2AuthenticationException {

    private static final String DESCRIPTION = "연결된 회원이 없다";

    private final SocialProfile profile;

    public SocialSignupRequiredException(SocialProfile profile) {
        super(new OAuth2Error(SocialLoginErrorCode.SIGNUP_REQUIRED.name(), DESCRIPTION, null), DESCRIPTION);
        this.profile = profile;
    }

    public SocialProfile getProfile() {
        return profile;
    }
}
