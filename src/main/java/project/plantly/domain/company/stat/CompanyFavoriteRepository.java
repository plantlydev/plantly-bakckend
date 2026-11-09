package project.plantly.domain.company.stat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CompanyFavoriteRepository extends JpaRepository<CompanyFavorite, Long> {

    // 개인화 상태 조회용(favoritedByMe). 요청자가 이미 해당 회사를 즐겨찾기 중인지 확인한다.
    boolean existsByUserIdAndCompanyId(Long userId, Long companyId);

    // 멱등 등록(upsert). unique(user_id, company_id) 충돌 시 아무것도 안 하고 넘어가 재요청에도 예외가 나지 않는다.
    // created_at 은 @CreationTimestamp(JPA persist 전용)가 안 걸리므로 now() 로 직접 채운다. 반환값 = 신규 삽입 행 수(1|0).
    @Modifying
    @Query(value = "insert into company_favorite (user_id, company_id, created_at) "
            + "values (:userId, :companyId, now()) "
            + "on conflict (user_id, company_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("companyId") Long companyId);

    // 즐겨찾기 해제(hard delete). 없는 행을 지워도 0을 반환할 뿐 예외가 없으므로 DELETE 엔드포인트가 그대로 멱등하다.
    // 해제는 JPQL 벌크 DELETE 로 지운다 — 파생 delete(deleteBy...)는 "조회 → 건별 em.remove → 커밋 때 DELETE" 라서,
    // 더블클릭처럼 두 요청이 같은 행을 함께 읽으면 늦은 쪽의 DELETE 가 0건이 되고 Hibernate 가 이를 낙관적 락 실패로 보아
    // 500 을 낸다(리뷰 ⑥, ConcurrentRequestErrorLeakTest). 벌크 DELETE 는 0건이어도 0 을 돌려줄 뿐이다.
    @Modifying
    @Query("delete from CompanyFavorite f where f.userId = :userId and f.companyId = :companyId")
    int deleteByUserIdAndCompanyId(@Param("userId") Long userId, @Param("companyId") Long companyId);

    // 내 즐겨찾기 목록(카드)은 FavoriteCompanyCardRepository 가 회사 본체와 JOIN 해 한 쿼리로 가져간다.
    // (원천 행만 페이징하면 카드마다 회사를 다시 조회해야 해서 N+1 이 된다.)

    // 목록 개인화용 배치 조회. 한 페이지의 회사 id 들 중 이 유저가 즐겨찾기한 것만 id 로 돌려준다(한 쿼리).
    @Query("select f.companyId from CompanyFavorite f where f.userId = :userId and f.companyId in :companyIds")
    List<Long> findFavoritedCompanyIds(@Param("userId") Long userId, @Param("companyIds") Collection<Long> companyIds);
}
