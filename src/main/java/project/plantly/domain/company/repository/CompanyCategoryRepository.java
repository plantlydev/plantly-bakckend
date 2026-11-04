package project.plantly.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.plantly.domain.company.entity.link.CompanyCategory;

import java.util.List;

public interface CompanyCategoryRepository extends JpaRepository<CompanyCategory, Long> {

    // 링크 + 카테고리 마스터를 한 번의 조회로 가져온다. (링크별 LAZY 로딩 N+1 회피)
    @Query("select l from CompanyCategory l join fetch l.category where l.company.id = :companyId order by l.displayOrder, l.id")
    List<CompanyCategory> findLinksByCompanyId(@Param("companyId") Long companyId);

    // 링크 전체 교체(PUT) 시 기존 카테고리 링크를 일괄 삭제한다.
    void deleteByCompanyId(Long companyId);
}
