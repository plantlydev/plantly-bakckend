package project.plantly.domain.user.withdrawal;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import project.plantly.global.exception.ErrorCode;

// 회원 탈퇴 실패. 상수 이름이 응답 code 다.
@Getter
@RequiredArgsConstructor
public enum WithdrawalErrorCode implements ErrorCode {

    // 관리자는 화면에서 탈퇴할 수 없다 — 관리자가 한 명도 없는 상태를 막는다. SQL 로 MEMBER 로 내린 뒤 탈퇴한다.
    ADMIN_WITHDRAWAL_NOT_ALLOWED(HttpStatus.FORBIDDEN, "관리자 계정은 탈퇴할 수 없습니다."),
    // ACTIVE 가 아닌 회원(정지·이미 탈퇴). 정지 회원의 탈퇴는 문의로 처리한다.
    WITHDRAWAL_NOT_ALLOWED(HttpStatus.FORBIDDEN, "탈퇴할 수 없는 계정 상태입니다. 고객센터로 문의해 주세요."),
    // 연결된 소셜 계정이 없어 본인 확인(재인가)을 할 수 없다. 운영은 소셜 가입만 있으므로 정상 흐름에선 나오지 않는다
    // (자체 가입 시드 회원 등). 문의로 처리한다.
    SOCIAL_ACCOUNT_REQUIRED(HttpStatus.FORBIDDEN, "본인 확인에 쓸 소셜 계정 연결이 없습니다. 고객센터로 문의해 주세요.");

    private final HttpStatus status;
    private final String message;
}
