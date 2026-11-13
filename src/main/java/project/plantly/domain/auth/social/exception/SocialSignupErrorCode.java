package project.plantly.domain.auth.social.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import project.plantly.global.exception.ErrorCode;

// 소셜 가입(가입 대기 → 동의 → 회원 생성) 실패. 상수 이름이 응답 code 다.
@Getter
@RequiredArgsConstructor
public enum SocialSignupErrorCode implements ErrorCode {

    // 세션에 가입 대기 정보가 없거나 유효 시간이 지났다. 프론트는 로그인 화면으로 보내 소셜 로그인을 다시 시작한다.
    SIGNUP_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "가입 가능 시간이 지났습니다. 소셜 로그인을 다시 진행해 주세요."),
    REQUIRED_CONSENT_MISSING(HttpStatus.BAD_REQUEST, "필수 항목에 모두 동의해야 가입할 수 있습니다."),
    // 제공자가 이름을 주지 않았고 요청에도 없다.
    NAME_REQUIRED(HttpStatus.BAD_REQUEST, "이름을 입력해 주세요."),
    // 가입 대기 중에 같은 이메일의 회원이 생겼다(대기 시작 때는 없었다).
    EMAIL_IN_USE(HttpStatus.CONFLICT, "같은 이메일로 가입된 회원이 있습니다."),
    // 같은 소셜 계정이 이미 연결됐다 — 가입 버튼을 두 번 누른 경우 등. 프론트는 재인가로 로그인하면 된다.
    ALREADY_SIGNED_UP(HttpStatus.CONFLICT, "이미 가입된 계정입니다.");

    private final HttpStatus status;
    private final String message;
}
