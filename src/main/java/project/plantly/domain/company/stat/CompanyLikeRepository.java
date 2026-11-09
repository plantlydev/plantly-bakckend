package project.plantly.domain.company.stat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CompanyLikeRepository extends JpaRepository<CompanyLike, Long> {

    // 개인화 상태 조회용(likedByMe). 요청자가 이미 해당 회사에 좋아요 중인지 확인한다.
    boolean existsByUserIdAndCompanyId(Long userId, Long companyId);

    // 멱등 등록(upsert). unique(user_id, company_id) 충돌 시 아무것도 안 하고 넘어가 재요청에도 예외가 나지 않는다.
    // created_at 은 @CreationTimestamp(JPA persist 전용)가 안 걸리므로 now() 로 직접 채운다. 반환값 = 신규 삽입 행 수(1|0).
    @Modifying
    @Query(value = "insert into company_like (user_id, company_id, created_at) "
            + "values (:userId, :companyId, now()) "
            + "on conflict (user_id, company_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("companyId") Long companyId);

    // 좋아요 해제(hard delete). 없는 행을 지워도 0을 반환할 뿐 예외가 없으므로 DELETE 엔드포인트가 그대로 멱등하다.
    // 해제는 JPQL 벌크 DELETE 로 지운다 — 파생 delete(deleteBy...)는 "조회 → 건별 em.remove → 커밋 때 DELETE" 라서,
    // 더블클릭처럼 두 요청이 같은 행을 함께 읽으면 늦은 쪽의 DELETE 가 0건이 되고 Hibernate 가 이를 낙관적 락 실패로 보아
    // 500 을 낸다(리뷰 ⑥, ConcurrentRequestErrorLeakTest). 벌크 DELETE 는 0건이어도 0 을 돌려줄 뿐이다.
    @Modifying
    @Query("delete from CompanyLike l where l.userId = :userId and l.companyId = :companyId")
    int deleteByUserIdAndCompanyId(@Param("userId") Long userId, @Param("companyId") Long companyId);

    // 목록 개인화용 배치 조회. 한 페이지의 회사 id 들 중 이 유저가 좋아요한 것만 id 로 돌려준다(행마다 N+1 없이 한 쿼리).
    @Query("select l.companyId from CompanyLike l where l.userId = :userId and l.companyId in :companyIds")
    List<Long> findLikedCompanyIds(@Param("userId") Long userId, @Param("companyIds") Collection<Long> companyIds);
}
