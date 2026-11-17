package project.plantly.domain.user.withdrawal;

import project.plantly.domain.auth.social.SocialProvider;

import java.util.List;

// 탈퇴 결과. deletedCompanies = 함께 삭제한 소유 회사 수(이미 삭제돼 있던 회사 포함),
// providers = 연결을 지운 소셜 제공자(호출부가 연동 해제 대상을 고를 때 쓴다).
public record WithdrawalResult(int deletedCompanies, List<SocialProvider> providers) {

    public WithdrawalResult {
        providers = List.copyOf(providers);
    }
}
