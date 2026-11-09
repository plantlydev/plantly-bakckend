package project.plantly.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.plantly.domain.company.entity.CompanyDraft;

import java.util.List;
import java.util.Optional;

public interface CompanyDraftRepository extends JpaRepository<CompanyDraft, Long> {

    /** 초안은 사용자·사업자번호당 1개. 조회/자동저장 upsert 의 진입점이다. */
    Optional<CompanyDraft> findByUserIdAndBusinessNumber(Long userId, String businessNumber);

    /** 내 초안 목록. 최근에 손댄 것부터. */
    List<CompanyDraft> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 수동 폐기 및 발행 성공 후 정리. 없어도 예외 없이 통과한다(멱등).
     *
     * <p>파생 delete 가 아니라 JPQL 벌크 DELETE 다. 파생 delete 는 조회한 행을 커밋 때 건별로 지워서, 폐기 더블클릭이나
     * 폐기와 발행이 겹치면 늦은 쪽의 DELETE 가 0건이 되고 낙관적 락 실패(500)로 터진다(리뷰 ⑥). 벌크 DELETE 는 0건이면
     * 그냥 0건이다.
     */
    @Modifying
    @Query("delete from CompanyDraft d where d.userId = :userId and d.businessNumber = :businessNumber")
    void deleteByUserIdAndBusinessNumber(@Param("userId") Long userId, @Param("businessNumber") String businessNumber);
}
