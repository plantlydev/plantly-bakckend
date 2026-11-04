package project.plantly.companyTest.searchTest;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.category.Category;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyEquipment;
import project.plantly.domain.company.entity.CompanyMaterial;
import project.plantly.domain.company.entity.CompanyProjectReference;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@DisplayName("CompanySearchDocumentWriter: 검색 도큐먼트·closure 재생성")
class CompanySearchDocumentWriterTest extends PostgresContainerTest {

    @Autowired EntityManager em;
    @Autowired CompanySearchDocumentWriter writer;

    @Test
    @DisplayName("회사 스칼라·자식 집계를 search_all 로 합치고, 카테고리는 조상까지 closure 에 펼친다")
    void rebuildsDocumentAndAncestorClosure() {
        // given: 카테고리 트리 (대분류 제조 -> 중분류 정밀가공)
        Category root = Category.createRoot("제조", "MFG", null, null, 0);
        em.persist(root);
        Category child = Category.createChild(root, "정밀가공", "MFG-PRECISION", null, null, 0);
        em.persist(child);

        // 회사 + 자식(레퍼런스/설비/소재) + 카테고리 링크(중분류에 연결)
        Company company = Company.createByUser(1L, "1112233444", "플랜틀리", "홍길동", null,
                Address.of("06236", "서울 강남구", null, "테헤란로 1"), null, "http://logo", null,
                "스마트팜 솔루션", "자동 관수 시스템 제공", null, null, null, null, null, null);
        em.persist(company);
        em.persist(new CompanyProjectReference(company, "수직농장 구축", "생산성 30% 향상", "OO대학", "2024", 0));
        em.persist(new CompanyEquipment(company, "CNC 가공기", 0));
        em.persist(new CompanyMaterial(company, "알루미늄", 0));
        em.persist(new CompanyCategory(company, child, 0));

        // when
        writer.write(company.getId());

        // then: search_all 에 각 출처(이름/요약/본문/레퍼런스/설비/소재) 토큰이 모두 들어간다
        Object[] doc = (Object[]) em.createNativeQuery("""
                SELECT search_all, reference_text, equipment_text, material_text
                FROM company_search_document WHERE company_id = :id
                """).setParameter("id", company.getId()).getSingleResult();
        String searchAll = (String) doc[0];
        assertThat(searchAll).contains(
                "플랜틀리", "스마트팜 솔루션", "자동 관수 시스템",
                "수직농장 구축", "생산성 30% 향상", "OO대학", "CNC 가공기", "알루미늄");
        assertThat((String) doc[1]).contains("수직농장 구축", "생산성 30% 향상", "OO대학"); // reference_text 집계
        assertThat((String) doc[2]).isEqualTo("CNC 가공기");
        assertThat((String) doc[3]).isEqualTo("알루미늄");

        // closure: 연결한 중분류 + 그 조상(대분류) 둘 다 들어간다
        List<Long> categoryIds = em.createNativeQuery(
                        "SELECT category_id FROM company_category_closure WHERE company_id = :id")
                .setParameter("id", company.getId())
                .getResultList().stream()
                .map(o -> ((Number) o).longValue())
                .toList();
        assertThat(categoryIds).containsExactlyInAnyOrder(child.getId(), root.getId());
    }

}
