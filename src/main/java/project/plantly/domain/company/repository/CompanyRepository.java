package project.plantly.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.enums.CompanyVisibility;

// 비관적 락 조회(findByIdForUpdate)는 CompanyLockRepository 조각에서 온다 — OSIV 하에서도 최신 상태를 읽어야 해서 직접 구현했다.
public interface CompanyRepository extends JpaRepository<Company, Long>, CompanyLockRepository {

    // 공개 노출 중인 회사 수(현황 지표). 목록/검색과 달리 company_search_document 를 조인하지 않는다 —
    // 색인은 등록 트랜잭션 안에서 동기로 쓰이므로 두 수는 같고, 다르다면 색인이 밀린 것이라
    // '등록된 회사가 몇인가'에 대한 답으로는 이쪽이 옳다. 조건은 showcase 레일의 가시성 규칙과 같다.
    long countByDeletedFalseAndVisibility(CompanyVisibility visibility);

    // 좋아요/즐겨찾기 등 상호작용 대상 검증용. 공개 상세에 보이는 회사(미삭제 + 지정한 공개 범위)만 true.
    boolean existsByIdAndDeletedFalseAndVisibility(Long id, CompanyVisibility visibility);

    // 활성(미삭제) 회사 중 동일 사업자번호 존재 여부. 등록·복구 시 부분 유니크(활성 행끼리)를 앱 레벨에서도 사전 검증한다.
    // (DB 부분 유니크 인덱스가 최종 정합성을 보장하고, 이 조회는 친화적 에러 + H2 테스트 커버리지를 위한 belt-and-suspenders)
    boolean existsByBusinessNumberAndDeletedFalse(String businessNumber);
}
