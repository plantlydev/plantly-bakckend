package project.plantly.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.enums.VerificationStatus;

import java.util.Collection;
import java.util.Optional;

public interface CompanyVerificationRepository
        extends JpaRepository<CompanyVerification, Long>, CompanyVerificationLockRepository {

    /** 회사 등록 시 인증을 소비하는 경로. userId 를 함께 걸어 타인의 인증을 쓰지 못하게 한다. */
    Optional<CompanyVerification> findByIdAndUserId(Long id, Long userId);

    /** 관리자 인증 회수 / 상태 조회. 등록에 소비된 인증은 companyId 로 찾는다. */
    Optional<CompanyVerification> findByCompanyId(Long companyId);

    /**
     * 초안 접근 권한의 근거. 초안은 인증 <b>레코드</b>가 아니라 사업자번호로 키잉되므로
     * ({@code CompanyDraft} 주석 참고) "이 사용자가 이 번호로 국세청을 통과한 적이 있는가" 만 물으면 된다.
     * 인증이 만료돼 여러 번 재발급됐어도 그 이력 중 하나만 있으면 자기 초안에 계속 닿을 수 있다.
     */
    boolean existsByUserIdAndBusinessNumber(Long userId, String businessNumber);

    /**
     * 초안 이어쓰기에 붙여 줄 "지금 쓸 인증". 같은 번호로 재발급 이력이 여러 행일 수 있어 가장 최근 것을 고른다.
     * 상태는 호출부가 정한다({@code CompanyDraftService} 참고).
     */
    Optional<CompanyVerification> findFirstByUserIdAndBusinessNumberAndStatusInOrderByIdDesc(
            Long userId, String businessNumber, Collection<VerificationStatus> statuses);

    /**
     * 이 사용자가 이 번호로 만든 회사가 <b>지금 살아 있는지</b>. 살아 있으면 그 사업자번호는 초안이 아니라 수정 대상이다.
     *
     * <p>인증이 CONSUMED 인지만 보면 안 된다 — 회사를 삭제(soft delete)해도 인증은 CONSUMED 로 남는데, 삭제된
     * 번호는 재인증·재등록이 허용되므로(활성 회사끼리만 중복을 막는다) 그 상태로는 재등록 초안이 영영 막힌다.
     * 그래서 소비한 회사가 삭제되지 않았는지까지 함께 본다. companyId 는 FK 가 아니라 raw id 라 조인 대신 서브쿼리다.
     * (같은 번호로 재발급 이력이 여러 행일 수 있어 단건 조회로는 판정할 수 없다)
     */
    @Query("""
            select count(v) > 0 from CompanyVerification v
            where v.userId = :userId
              and v.businessNumber = :businessNumber
              and v.status = project.plantly.domain.company.enums.VerificationStatus.CONSUMED
              and exists (select c.id from Company c where c.id = v.companyId and c.deleted = false)
            """)
    boolean existsConsumedByLiveCompany(@Param("userId") Long userId, @Param("businessNumber") String businessNumber);
}
