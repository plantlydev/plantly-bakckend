package project.plantly.domain.user.consent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 가입 때 받는 동의 항목. 상수 이름이 API 계약이다 — 프론트가 이 이름으로 체크박스를 만들고 그대로 돌려보낸다.
// 필수 여부는 법·정책이 정하는 성격이라 코드에 둔다. 버전(약관 개정)은 설정값이다(ConsentProperties).
//
// 선언 순서 = 가입 화면 표시 순서.
@Getter
@RequiredArgsConstructor
public enum ConsentType {

    // 만 14세 미만은 법정대리인 동의가 필요하다(개인정보보호법 제22조의2). 그 절차를 두지 않으므로 14세 이상만 받는다.
    AGE_OVER_14(true),
    TERMS_OF_SERVICE(true),
    // 개인정보 수집·이용(이름·이메일·소셜 계정 식별자).
    PRIVACY_COLLECTION(true),
    // 광고성 정보 수신(정보통신망법 제50조). 선택 — 동의하지 않아도 가입된다.
    // 이용자가 직접 켠 알림(키워드 알림 등)은 광고가 아니므로 이 동의와 무관하다.
    MARKETING(false);

    private final boolean required;
}
