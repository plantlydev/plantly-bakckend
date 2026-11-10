package project.plantly.companyTest.searchTest;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.category.Category;
import project.plantly.domain.company.certification.Certification;
import project.plantly.domain.company.certification.CertificationType;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyEquipment;
import project.plantly.domain.company.entity.CompanyMaterial;
import project.plantly.domain.company.entity.CompanyTag;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.entity.link.CompanyCertification;
import project.plantly.domain.company.entity.link.CompanyIndustry;
import project.plantly.domain.company.industry.Industry;
import project.plantly.domain.company.search.CompanySearchCriteria;
import project.plantly.domain.company.search.CompanySearchCriteria.AdvancedText;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.search.CompanySearchRepository;
import project.plantly.domain.company.search.dto.CompanySummary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@DisplayName("PostgresTrigramCompanySearch: 네이티브 검색")
class PostgresTrigramCompanySearchTest extends PostgresContainerTest {

    @Autowired EntityManager em;
    @Autowired CompanySearchDocumentWriter writer;
    @Autowired CompanySearchRepository repository;

    @Test
    @DisplayName("통합 키워드: search_all 부분일치, 다중 토큰은 AND")
    void unifiedKeyword() {
        Company a = index(persistCompany("플랜틀리", "스마트팜 자동관수 시스템"));
        index(persistCompany("베타기계", "금속 절삭 가공"));

        assertThat(ids(search(kw("스마트팜")))).containsExactly(a.getId());
        assertThat(ids(search(kw("스마트팜 자동관수")))).containsExactly(a.getId()); // 둘 다 A 에 있음
        assertThat(ids(search(kw("스마트팜 절삭")))).isEmpty();                      // 한 회사에 둘 다 있진 않음
    }

    @Test
    @DisplayName("통합 키워드: 태그 텍스트도 search_all 에 접혀 매칭된다 (본문/이름에 없어도)")
    void unifiedKeywordMatchesTag() {
        Company a = persistCompany("가가전자", "일반 제조");   // '친환경' 은 이름·본문에 없음
        em.persist(new CompanyTag(a, "친환경", 0));
        index(a);
        index(persistCompany("나나기계", "금속 절삭"));

        assertThat(ids(search(kw("친환경")))).containsExactly(a.getId());
    }

    // 태그·소재·장비는 입력한 표기 그대로 저장한다("Ai", "ArtificialIntelligence" 를 살리기 위해 소문자로 접지 않는다).
    // 대소문자 차이는 검색이 흡수한다 — 매칭이 ILIKE 라서. 이 테스트는 그 계약을 고정한다(LIKE 로 바뀌거나,
    // ES 전환 시 keyword 필드에 lowercase normalizer 를 빠뜨리면 여기서 깨진다).
    @Test
    @DisplayName("대소문자 무시: 'Ai' 태그는 AI·ai·Ai 통합검색에, 'Steel'·'CNC' 는 고급검색 소재·장비에 대소문자 무관하게 잡힌다")
    void matchingIgnoresCase() {
        Company a = persistCompany("가가전자", "일반 제조");
        em.persist(new CompanyTag(a, "Ai", 0));
        em.persist(new CompanyMaterial(a, "Steel", 0));
        em.persist(new CompanyEquipment(a, "CNC", 0));
        index(a);
        index(persistCompany("나나기계", "금속 절삭"));

        for (String keyword : List.of("AI", "ai", "Ai")) {
            assertThat(ids(search(kw(keyword)))).as("통합검색 '%s'", keyword).containsExactly(a.getId());
        }
        for (String material : List.of("STEEL", "steel")) {
            AdvancedText byMaterial = new AdvancedText(null, null, null, null, null, null, null, null, material);
            assertThat(ids(search(new CompanySearchCriteria(null, byMaterial, null, null, null))))
                    .as("고급검색 소재 '%s'", material).containsExactly(a.getId());
        }
        for (String equipment : List.of("cnc", "Cnc")) {
            AdvancedText byEquipment = new AdvancedText(null, null, null, null, null, null, null, equipment, null);
            assertThat(ids(search(new CompanySearchCriteria(null, byEquipment, null, null, null))))
                    .as("고급검색 장비 '%s'", equipment).containsExactly(a.getId());
        }
    }

    @Test
    @DisplayName("재색인(수정 훅): 태그를 바꾸면 이전 태그는 빠지고 새 태그로 검색된다 (backfill 불필요)")
    void reindexReflectsTagChange() {
        Company a = persistCompany("가가전자", "일반 제조");
        CompanyTag before = new CompanyTag(a, "친환경", 0);
        em.persist(before);
        index(a);
        assertThat(ids(search(kw("친환경")))).containsExactly(a.getId());

        // 수정(replaceTags 와 동일 형태): 기존 태그 삭제 + 새 태그 → 재색인(write). 수정 서비스의 write() 훅을 재현한다.
        em.remove(em.find(CompanyTag.class, before.getId()));
        em.persist(new CompanyTag(a, "고효율", 0));
        index(a);

        assertThat(ids(search(kw("고효율")))).containsExactly(a.getId()); // 새 태그로 잡히고
        assertThat(ids(search(kw("친환경")))).isEmpty();                   // 이전 태그는 더 이상 매칭 안 됨
    }

    @Test
    @DisplayName("고급 검색: 지정한 필드(회사명) 컬럼에만 매칭한다")
    void advancedTargetsColumn() {
        Company a = index(persistCompany("플랜틀리", "일반 제조"));
        index(persistCompany("베타기계", "플랜틀리 협력사"));   // '플랜틀리' 는 본문에만

        AdvancedText byName = new AdvancedText("플랜틀리", null, null, null, null, null, null, null, null);
        assertThat(ids(search(new CompanySearchCriteria(null, byName, null, null, null))))
                .containsExactly(a.getId());
    }

    @Test
    @DisplayName("카테고리 패싯: 대분류 선택 시 후손 서브트리(closure)까지 매칭한다")
    void categorySubtreeFacet() {
        Category root = Category.createRoot("제조", "MFG", null, null, 0);
        em.persist(root);
        Category child = Category.createChild(root, "정밀가공", "MFG-P", null, null, 0);
        em.persist(child);
        Category otherRoot = Category.createRoot("서비스", "SVC", null, null, 1);
        em.persist(otherRoot);

        Company a = persistCompany("가가", "x");
        em.persist(new CompanyCategory(a, child, 0));      // 중분류 연결 → closure 에 root 포함
        index(a);
        Company b = persistCompany("나나", "y");
        em.persist(new CompanyCategory(b, otherRoot, 0));  // 다른 대분류
        index(b);

        assertThat(ids(search(new CompanySearchCriteria(null, null, null, null, List.of(root.getId())))))
                .containsExactly(a.getId());
    }

    @Test
    @DisplayName("인증 패싯: 선택한 인증에 링크된 회사만 매칭한다")
    void certificationFacet() {
        Certification iso = Certification.create("ISO9001", "iso-9001", CertificationType.MANAGEMENT_SYSTEM, 0);
        em.persist(iso);

        Company a = persistCompany("가가", "x");
        em.persist(new CompanyCertification(a, iso, 0));
        index(a);
        index(persistCompany("나나", "y"));

        assertThat(ids(search(new CompanySearchCriteria(null, null, List.of(iso.getId()), null, null))))
                .containsExactly(a.getId());
    }

    @Test
    @DisplayName("인증 패싯: 같은 type 안에서 복수 선택하면 OR — 하나만 보유해도 매칭한다")
    void certificationFacetWithinTypeIsOr() {
        Certification iso9001 = persistCert("ISO9001", "iso-9001", CertificationType.MANAGEMENT_SYSTEM);
        Certification iso14001 = persistCert("ISO14001", "iso-14001", CertificationType.MANAGEMENT_SYSTEM);

        Company only9001 = persistCompany("구공공일", "x");
        em.persist(new CompanyCertification(only9001, iso9001, 0));
        index(only9001);

        Company only14001 = persistCompany("일사공공일", "y");
        em.persist(new CompanyCertification(only14001, iso14001, 0));
        index(only14001);

        Company neither = index(persistCompany("무인증", "z"));

        // 둘 다 경영시스템 → type 내 OR → 하나씩만 가진 회사도 모두 노출
        assertThat(ids(search(new CompanySearchCriteria(
                null, null, List.of(iso9001.getId(), iso14001.getId()), null, null))))
                .containsExactlyInAnyOrder(only9001.getId(), only14001.getId())
                .doesNotContain(neither.getId());
    }

    @Test
    @DisplayName("인증 패싯: 서로 다른 type 을 선택하면 AND — 양쪽 type 을 모두 보유한 회사만 매칭한다")
    void certificationFacetAcrossTypesIsAnd() {
        Certification iso9001 = persistCert("ISO9001", "iso-9001", CertificationType.MANAGEMENT_SYSTEM);
        Certification iso14001 = persistCert("ISO14001", "iso-14001", CertificationType.MANAGEMENT_SYSTEM);
        Certification kc = persistCert("KC", "kc", CertificationType.MARKET_ACCESS);

        Company both = persistCompany("둘다", "x");
        em.persist(new CompanyCertification(both, iso9001, 0));
        em.persist(new CompanyCertification(both, kc, 1));
        index(both);

        Company onlyManagement = persistCompany("경영만", "y");
        em.persist(new CompanyCertification(onlyManagement, iso9001, 0));
        index(onlyManagement);

        Company onlyMarket = persistCompany("시장만", "z");
        em.persist(new CompanyCertification(onlyMarket, kc, 0));
        index(onlyMarket);

        // 경영시스템 + 시장진입 선택 → type 간 AND → 한쪽만 가진 회사는 제외
        assertThat(ids(search(new CompanySearchCriteria(
                null, null, List.of(iso9001.getId(), kc.getId()), null, null))))
                .containsExactly(both.getId());

        // 경영시스템에서 2개 + 시장진입 1개 → (9001 or 14001) and KC. 조합 규칙이 함께 적용된다.
        assertThat(ids(search(new CompanySearchCriteria(
                null, null, List.of(iso9001.getId(), iso14001.getId(), kc.getId()), null, null))))
                .containsExactly(both.getId());
    }

    @Test
    @DisplayName("기본 정렬: 스팟라이트 고정 → 추천 → 최신 (플래그가 생성시간을 이긴다)")
    void defaultSort() {
        Company spot = persistCompany("스팟", "x");   // 가장 먼저 생성(가장 오래됨)
        spot.turnOnSpotlight(1);
        index(spot);
        Company feat = persistCompany("추천", "y");
        feat.feature();
        index(feat);
        Company plain = index(persistCompany("일반", "z")); // 가장 최신

        assertThat(ids(search(new CompanySearchCriteria(null, null, null, null, null))))
                .containsExactly(spot.getId(), feat.getId(), plain.getId());
    }

    @Test
    @DisplayName("카드에 직접 연결한 카테고리/태그/산업군 이름을 display_order 순으로 집계 (closure 조상 제외)")
    void summaryAggregatesNames() {
        Category root = Category.createRoot("제조", "MFG", null, null, 0);
        em.persist(root);
        Category precision = Category.createChild(root, "정밀가공", "MFG-P", null, null, 0);
        em.persist(precision);
        Category assembly = Category.createChild(root, "조립", "MFG-A", null, null, 0);
        em.persist(assembly);
        Industry industry = Industry.create("농업기술", "AGRI", null, null, 0);
        em.persist(industry);

        Company a = persistCompany("가가", "x");
        // 링크 displayOrder(=회사가 고른 순서)로 정렬됨 — 이름순(정밀가공<조립)이 아니라 선택 순서(조립=0, 정밀가공=1)를 따른다.
        em.persist(new CompanyCategory(a, precision, 1)); // 직접 링크 = 중분류만 (대분류 제조는 closure 에만)
        em.persist(new CompanyCategory(a, assembly, 0));
        em.persist(new CompanyIndustry(a, industry, 0));
        em.persist(new CompanyTag(a, "스마트팜", 0));
        em.persist(new CompanyTag(a, "IoT", 1));
        index(a);

        CompanySummary card = search(new CompanySearchCriteria(null, null, null, null, null))
                .getContent().get(0);

        assertThat(card.categoryNames()).containsExactly("조립", "정밀가공"); // 링크 displayOrder 0,1 (이름순 아님), 조상 제외
        assertThat(card.tagNames()).containsExactly("스마트팜", "IoT");        // display_order 순
        assertThat(card.industryNames()).containsExactly("농업기술");
    }

    @Test
    @DisplayName("공개 범위: 비공개(PRIVATE) 회사는 공개 검색에서 제외되고, 다시 공개로 전환하면 노출된다")
    void privateExcludedFromPublicSearch() {
        Company pub = index(persistCompany("공개회사", "스마트팜"));
        Company priv = persistCompany("비공개회사", "스마트팜");
        priv.changeVisibility(project.plantly.domain.company.enums.CompanyVisibility.PRIVATE);
        index(priv);

        // 같은 키워드로 잡히지만 비공개는 빠진다.
        assertThat(ids(search(kw("스마트팜")))).containsExactly(pub.getId());

        // 다시 공개로 전환하면 노출된다(원천 행 유지 → 재색인 불필요, 필터는 company.visibility 실시간 참조).
        priv.changeVisibility(project.plantly.domain.company.enums.CompanyVisibility.PUBLIC);
        em.flush();
        assertThat(ids(search(kw("스마트팜")))).containsExactlyInAnyOrder(pub.getId(), priv.getId());
    }

    // ===== helpers =====

    private Company persistCompany(String name, String content) {
        Company c = Company.createByUser(1L, null, name, "대표자", null,
                Address.of("06236", "서울 강남구", null, "테헤란로 1"), null, "logo-" + name, null,
                name + " 요약", content, null, null, null, null, null, null);
        em.persist(c);
        return c;
    }

    private Company index(Company c) {
        writer.write(c.getId());
        return c;
    }

    private Certification persistCert(String name, String slug, CertificationType type) {
        Certification cert = Certification.create(name, slug, type, 0);
        em.persist(cert);
        return cert;
    }

    private Page<CompanySummary> search(CompanySearchCriteria criteria) {
        return repository.search(criteria, PageRequest.of(0, 20));
    }

    private CompanySearchCriteria kw(String keyword) {
        return new CompanySearchCriteria(keyword, null, null, null, null);
    }

    private List<Long> ids(Page<CompanySummary> page) {
        return page.getContent().stream().map(CompanySummary::id).toList();
    }
}
