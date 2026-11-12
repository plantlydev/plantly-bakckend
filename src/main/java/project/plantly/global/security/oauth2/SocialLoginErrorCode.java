package project.plantly.global.security.oauth2;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

// 소셜 로그인이 실패했을 때 프론트로 넘기는 코드. 실패 핸들러가 {프론트}/login?error={상수명} 으로 보낸다.
// 프론트는 문구가 아니라 이 상수명으로 안내를 고른다(ErrorCode 상수명을 계약으로 쓰는 기존 규칙과 같다).
public enum SocialLoginErrorCode {

    // 연결도, 같은 이메일의 회원도 없다. 3단계에서 가입 대기 → /signup/social 로 바뀐다(지금은 임시로 로그인 화면).
    SIGNUP_REQUIRED,
    // 연결된 회원이 정지·탈퇴 상태다. 자체 로그인의 UserErrorCode.ACCOUNT_SUSPENDED 와 같은 이름이다.
    ACCOUNT_SUSPENDED,
    // 연결 없이 같은 이메일의 회원이 이미 있다. 기존 방식으로 로그인하라고 안내한다.
    EMAIL_IN_USE,
    // 그 밖의 전부: 사용자가 제공자 화면에서 취소함, 토큰·사용자 정보 호출 실패, 응답에 필수 값이 없음 등.
    SOCIAL_LOGIN_FAILED;

    public OAuth2AuthenticationException exception(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(name(), description, null), description);
    }

    // 우리가 던진 코드면 그대로, 프레임워크·제공자가 던진 오류(access_denied, invalid_token_response …)면 SOCIAL_LOGIN_FAILED.
    public static SocialLoginErrorCode from(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            String errorCode = oauth2Exception.getError().getErrorCode();
            for (SocialLoginErrorCode code : values()) {
                if (code.name().equals(errorCode)) {
                    return code;
                }
            }
        }
        return SOCIAL_LOGIN_FAILED;
    }
}
