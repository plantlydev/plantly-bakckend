package project.plantly.domain.user.withdrawal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.rejoin.RejoinRestriction;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.company.enums.MemberRole;
import project.plantly.domain.company.repository.CompanyDraftRepository;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyVerificationAttemptRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.company.stat.CompanyFavoriteRepository;
import project.plantly.domain.company.stat.CompanyLikeRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.exception.UserErrorCode;
import project.plantly.domain.user.repository.UserAuthState;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

// 회원 탈퇴(회원 도메인층). 한 트랜잭션에서 회원과 그 회원의 데이터를 정리한다. 전부 되거나 전부 안 된다.
//
// 정리 규칙(2026-11-16 확정)
//  - 소유 회사: 함께 삭제(soft delete). 보관기한(30일) 뒤 완전 파기는 별도 작업(보류). 원래 회원에게 돌려주는 복구는 없다.
//    회사에 남은 개인정보도 정리한다(2026-11-17 확정): 대표자명은 '탈퇴회원'으로 익명화(필수 컬럼), 연락처는 삭제,
//    국세청 인증 표시는 회수. 관리자 복구는 그대로 열어 둔다.
//  - 지우는 것: 소유 연결(CompanyMember), 임시저장 초안, 좋아요·즐겨찾기, 사업자 인증·인증 시도 기록, 소셜 연결.
//  - 남기는 것: 약관 동의 기록(동의 증빙), 업로드 파일(완전 파기 작업에서 함께 정리), 회원 행(익명화해서 — 회사의
//    registeredBy 등 이력 참조가 깨지지 않게).
//  - 재가입 흔적: 소셜 연결을 지우기 전에 RejoinRestriction 에 남긴다(24시간 같은 소셜 계정 재가입 제한).
//
// 하지 않는 것: 제공자 연동 해제(네이버·구글)와 세션 정리는 호출부(WithdrawalReauthHandler)가 커밋 뒤에 한다 —
// 외부 호출 실패가 탈퇴를 되돌리면 안 되고, 외부 호출을 DB 트랜잭션 안에 두지 않기 위해서다.
//
// 흐름: 확인 화면(preview) → 시작(reauthProvider, 호출부가 의도를 세션에 둔다) → 소셜 재인가 → 콜백에서 withdraw.
// preview·reauthProvider 의 거절 규칙은 withdraw 와 같다(checkWithdrawable). withdraw 는 락 아래에서 다시 확인한다.
//
// 동시성: 시작하자마자 회원 행을 잠근다. 회원 데이터를 쓰는 다른 경로도 같은 행을 잠그고(ActiveUserGuard)
// 상태를 확인하므로, 탈퇴와 겹친 요청은 탈퇴가 끝난 뒤 WITHDRAWN 을 보고 멈춘다.
// 잠금 순서는 회원 행 → 회사 행(소유 회사 삭제)이다. 회사 행을 먼저 잡고 회원 행을 잡는 경로는 없다.
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final RejoinRestriction rejoinRestriction;
    private final CompanyMemberRepository companyMemberRepository;
    private final CompanyUpdateService companyUpdateService;
    private final CompanyDraftRepository draftRepository;
    private final CompanyLikeRepository likeRepository;
    private final CompanyFavoriteRepository favoriteRepository;
    private final CompanyVerificationRepository verificationRepository;
    private final CompanyVerificationAttemptRepository verificationAttemptRepository;

    @Transactional
    public WithdrawalResult withdraw(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        // 잠근 뒤의 상태·역할은 프로젝션으로 다시 읽는다(ActiveUserGuard 와 같은 이유 — 영속성 컨텍스트의 옛 값을 믿지 않는다).
        UserAuthState state = userRepository.findAuthStateById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        checkWithdrawable(state);

        // 1) 소유 회사 삭제 + 회사에 남은 개인정보 정리(회사 행 락 → 대표자명 익명화·국세청 인증 회수·soft delete →
        //    연락처 삭제 → 검색 도큐먼트 갱신). 이미 삭제된 회사도 같은 처리를 받는다. 소유 연결을 지우기 전에 해야 소유 검증을 통과한다.
        List<Long> ownedCompanyIds = companyMemberRepository.findCompanyIdsByUserIdAndRole(userId, MemberRole.OWNER);
        for (Long companyId : ownedCompanyIds) {
            companyUpdateService.deleteForOwnerWithdrawal(companyId, userId, User.WITHDRAWN_NAME);
        }

        // 2) 회원에 딸린 데이터 정리(벌크 DELETE — 건별 삭제의 0건 낙관적 락 실패를 피한다, 리뷰 ⑥)
        companyMemberRepository.deleteAllByUserId(userId);
        draftRepository.deleteAllByUserId(userId);
        likeRepository.deleteAllByUserId(userId);
        favoriteRepository.deleteAllByUserId(userId);
        verificationRepository.deleteAllByUserId(userId);
        verificationAttemptRepository.deleteAllByUserId(userId);

        // 3) 소셜 연결: 재가입 흔적을 남기고 연결을 지운다. 호출부가 연동 해제에 쓸 수 있게 제공자를 돌려준다.
        List<SocialAccount> accounts = socialAccountRepository.findByUserId(userId);
        for (SocialAccount account : accounts) {
            rejoinRestriction.record(account.getProvider(), account.getProviderUserId());
        }
        socialAccountRepository.deleteAllByUserId(userId);

        // 4) 회원 익명화 — 행은 남기고 개인정보만 지운다.
        user.withdraw(LocalDateTime.now());

        log.info("회원 탈퇴 userId={} 삭제한 소유 회사={}건 소셜 연결={}건", userId, ownedCompanyIds.size(), accounts.size());
        return new WithdrawalResult(ownedCompanyIds.size(),
                accounts.stream().map(SocialAccount::getProvider).toList());
    }

    // 탈퇴 확인 화면: 함께 지워질 것의 개수와 본인 확인에 쓸 제공자. 탈퇴할 수 없는 회원이면 화면 전에 거절한다.
    @Transactional(readOnly = true)
    public WithdrawalPreview preview(Long userId) {
        SocialProvider provider = reauthProvider(userId);
        return new WithdrawalPreview(
                companyMemberRepository.countLiveCompaniesByUserIdAndRole(userId, MemberRole.OWNER),
                draftRepository.countByUserId(userId),
                provider);
    }

    // 탈퇴 시작: 거절 규칙을 확인하고 본인 확인(재인가)에 쓸 제공자를 고른다. 실제 탈퇴는 재인가 콜백의 withdraw 다.
    // 연결이 여럿이면(계정 연결 기능은 아직 없어 정상 흐름에선 하나다) 열거 순서상 첫 제공자로 확인한다 —
    // 연동 해제도 그 제공자만 된다(나머지는 재인가 토큰이 없어 호출부가 로그만 남긴다).
    @Transactional(readOnly = true)
    public SocialProvider reauthProvider(Long userId) {
        UserAuthState state = userRepository.findAuthStateById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        checkWithdrawable(state);
        return socialAccountRepository.findByUserId(userId).stream()
                .map(SocialAccount::getProvider)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new BusinessException(WithdrawalErrorCode.SOCIAL_ACCOUNT_REQUIRED));
    }

    private static void checkWithdrawable(UserAuthState state) {
        if (state.userRole() == UserRole.ADMIN) {
            throw new BusinessException(WithdrawalErrorCode.ADMIN_WITHDRAWAL_NOT_ALLOWED);
        }
        if (state.userStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(WithdrawalErrorCode.WITHDRAWAL_NOT_ALLOWED);
        }
    }
}
