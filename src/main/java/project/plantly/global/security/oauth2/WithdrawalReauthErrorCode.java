package project.plantly.global.security.oauth2;

// 탈퇴 본인 확인(재인가)이 탈퇴로 끝나지 않았을 때 프론트로 넘기는 코드. 리다이렉트 주소의 error 파라미터로 간다.
// 프론트는 문구가 아니라 이 상수명으로 안내를 고른다(SocialLoginErrorCode 와 같은 규칙).
// 이 밖에 탈퇴 자체의 거절(WithdrawalErrorCode: ADMIN_WITHDRAWAL_NOT_ALLOWED·WITHDRAWAL_NOT_ALLOWED)도
// 같은 자리에 상수명 그대로 실린다.
public enum WithdrawalReauthErrorCode {

    // 탈퇴를 시작한 회원이 아닌 다른 계정으로 확인했다(다른 회원의 소셜 계정, 가입 안 된 소셜 계정, 다른 제공자).
    WITHDRAWAL_REAUTH_MISMATCH,
    // 본인 확인을 끝내지 못했다: 제공자 화면에서 취소, 통신 실패, 그 밖의 오류.
    WITHDRAWAL_REAUTH_FAILED,
    // 탈퇴를 시작한 지 오래돼(WithdrawalReauthStore.TTL) 의도가 만료됐거나, 콜백의 탈퇴 난수가 세션 의도와 맞지 않는다
    // (이미 쓴 주소, 다시 시작해 바뀐 주소, 만들어 낸 주소). 탈퇴 화면에서 다시 시작한다.
    WITHDRAWAL_SESSION_EXPIRED
}
