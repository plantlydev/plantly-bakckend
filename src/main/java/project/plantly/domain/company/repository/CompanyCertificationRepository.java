package project.plantly.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.plantly.domain.company.entity.link.CompanyCertification;

import java.util.List;

public interface CompanyCertificationRepository extends JpaRepository<CompanyCertification, Long> {

    // 링크 + 인증 마스터를 한 번의 조회로 가져온다. (링크별 LAZY 로딩 N+1 회피)
    // 마스터만 뽑지 않고 링크째 반환하는 이유는 custom_name 때문이다 — '기타'(ETC) 링크가 화면에 뿌릴 이름은
    // 마스터의 "기타"가 아니라 회사가 직접 적은 이름이고, 그 값은 링크에만 있다.
    @Query("select l from CompanyCertification l join fetch l.certification where l.company.id = :companyId order by l.displayOrder, l.id")
    List<CompanyCertification> findLinksByCompanyId(@Param("companyId") Long companyId);

    // 링크 전체 교체(PUT) 시 기존 인증 링크를 일괄 삭제한다.
    void deleteByCompanyId(Long companyId);
}
