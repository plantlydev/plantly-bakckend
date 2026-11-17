package project.plantly.domain.auth.social.repository;

import java.util.List;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;

import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    // 소셜 로그인 판정의 조회 키. (provider, providerUserId) 유니크 제약이 결과를 최대 1건으로 보장한다.
    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    // 회원 탈퇴: 재가입 흔적을 남길 연결 목록 → 연결 삭제
    List<SocialAccount> findByUserId(Long userId);

    // 회원 탈퇴 정리(WithdrawalService). JPQL 벌크 DELETE — 건별 삭제의 0건 낙관적 락 실패를 피한다(리뷰 ⑥).
    @Modifying
    @Query("delete from SocialAccount x where x.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
