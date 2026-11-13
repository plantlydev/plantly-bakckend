package project.plantly.domain.auth.social;

import project.plantly.domain.user.User;

// 소셜 로그인 판정 결과. 셋 중 하나다.
//  - LoggedIn: 연결된 활성 회원 → 로그인시킨다.
//  - SignupRequired: 연결도, 같은 이메일의 회원도 없다 → 가입 흐름으로 보낸다(3단계).
//  - Rejected: 로그인시키지 않는다. 이유는 RejectReason.
// 번역층이 이 결과를 OAuth2 주체(OAuth2UserPrincipal)나 OAuth2AuthenticationException 으로 바꾼다.
public sealed interface SocialLoginResult {

    record LoggedIn(User user) implements SocialLoginResult {
    }

    record SignupRequired(SocialProfile profile) implements SocialLoginResult {
    }

    record Rejected(RejectReason reason) implements SocialLoginResult {
    }

    enum RejectReason {
        // 연결된 회원이 정지·탈퇴 상태다.
        ACCOUNT_SUSPENDED,
        // 연결은 없는데 같은 이메일의 회원이 이미 있다(자체 가입·다른 제공자).
        // 먼저 가입한 쪽을 살린다 — 새 회원을 만들지도, 자동으로 연결하지도 않는다.
        // 연결은 기존 방식으로 로그인한 뒤 계정 설정에서 한다(후속 작업).
        EMAIL_IN_USE
    }
}
