package project.plantly.domain.company.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.enums.CompanyVisibility;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    // 공개 노출 중인 회사 수(현황 지표). 목록/검색과 달리 company_search_document 를 조인하지 않는다 —
    // 색인은 등록 트랜잭션 안에서 동기로 쓰이므로 두 수는 같고, 다르다면 색인이 밀린 것이라
    // '등록된 회사가 몇인가'에 대한 답으로는 이쪽이 옳다. 조건은 showcase 레일의 가시성 규칙과 같다.
    long countByDeletedFalseAndVisibility(CompanyVisibility visibility);

    // 좋아요/즐겨찾기 등 상호작용 대상 검증용. 미삭제(공개 가능) 회사만 true. 소프트 삭제는 미존재로 취급한다.
    boolean existsByIdAndDeletedFalse(Long id);

    // 활성(미삭제) 회사 중 동일 사업자번호 존재 여부. 등록·복구 시 부분 유니크(활성 행끼리)를 앱 레벨에서도 사전 검증한다.
    // (DB 부분 유니크 인덱스가 최종 정합성을 보장하고, 이 조회는 친화적 에러 + H2 테스트 커버리지를 위한 belt-and-suspenders)
    boolean existsByBusinessNumberAndDeletedFalse(String businessNumber);

    // 회사 행을 SELECT ... FOR UPDATE 로 읽는다. 같은 회사에 대한 쓰기 트랜잭션을 직렬화해야 하는 경로에서 쓴다.
    // 락은 트랜잭션이 끝날 때 풀리므로 반드시 쓰기 트랜잭션 안에서, 외부 호출 없이 짧게 써야 한다.
    // (현재 사용처: 재인증 반영 ↔ 관리자 인증 회수. 두 경로가 businessVerified 를 서로 덮어쓰지 않게 한다)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") Long id);
}
