package project.plantly.companyTest.repositoryTest;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.category.Category;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyTag;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.entity.link.CompanyIndustry;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.industry.Industry;
import project.plantly.domain.company.repository.OwnedCompanyCardRepository;
import project.plantly.domain.company.search.dto.CompanySummary;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@DisplayName("OwnedCompanyCardRepository: 내 회사 목록 카드")
class OwnedCompanyCardRepositoryTest extends PostgresContainerTest {

    @Autowired EntityManager em;
    @Autowired OwnedCompanyCardRepository repository;

    private static final long OWNER = 100L;
    private static final long OTHER = 200L;

    @Test
    @DisplayName("소유자 본인의 미삭제 회사만, 최신 등록순으로 카드를 반환한다 (삭제·타인 회사 제외)")
    void returnsOwnedNonDeletedLatestFirst() {
        Company older = persistCompany(OWNER, "오래된내회사");
        Company newer = persistCompany(OWNER, "최신내회사");
        Company deleted = persistCompany(OWNER, "내삭제회사");
        deleted.delete();
        persistCompany(OTHER, "남의회사"); // 타인 소유 → 제외
        em.flush(); // JPA 변경분을 DB 로 내려 JdbcTemplate 가 같은 트랜잭션에서 보게 한다.

        Page<CompanySummary> page = repository.findOwnedBy(OWNER, PageRequest.of(0, 20));

        // 최신순(created_at DESC, id DESC) — 삭제·타인 회사는 빠진다.
        assertThat(page.getContent().stream().map(CompanySummary::id).toList())
                .containsExactly(newer.getId(), older.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("카드에 회사가 연결한 카테고리/태그/산업군 이름을 선택 순서로 담는다 (closure 조상 제외)")
    void cardAggregatesNames() {
        Company a = persistCompany(OWNER, "가가");
        Category root = Category.createRoot("제조", "MFG", null, null, 0);
        em.persist(root);
        Category precision = Category.createChild(root, "정밀가공", "MFG-P", null, null, 0);
        em.persist(precision);
        Industry industry = Industry.create("농업기술", "AGRI", null, null, 0);
        em.persist(industry);
        em.persist(new CompanyCategory(a, precision, 0)); // 직접 링크 = 중분류만 (대분류 제조는 closure 에만)
        em.persist(new CompanyIndustry(a, industry, 0));
        em.persist(new CompanyTag(a, "스마트팜", 0));
        em.persist(new CompanyTag(a, "IoT", 1));
        em.flush();

        CompanySummary card = repository.findOwnedBy(OWNER, PageRequest.of(0, 20)).getContent().get(0);

        assertThat(card.categoryNames()).containsExactly("정밀가공"); // 직접 링크만, closure 조상(제조) 제외
        assertThat(card.tagNames()).containsExactly("스마트팜", "IoT"); // display_order 순
        assertThat(card.industryNames()).containsExactly("농업기술");
    }

    // 소유자 목록만 공개 범위를 실어 보낸다(CompanySummary::fromOwner). 카드 프로젝션은 공개 경로와 공유하므로
    // 이 값이 실제로 SQL 에서 읽혀 응답 객체까지 닿는지는 실 DB 로 확인해야 한다 —
    // 컬럼(c.visibility)이 빠지거나 fromPublic 으로 잘못 매핑되면 조용히 null 이 되고, 그러면
    // 소유자 화면에서 공개/비공개를 구분할 수 없게 된다.
    @Test
    @DisplayName("소유자 카드는 공개 범위를 담는다 — 비공개 회사도 내 목록에는 보이고 PRIVATE 로 구분된다")
    void ownerCardCarriesVisibility() {
        Company open = persistCompany(OWNER, "공개회사");
        Company hidden = persistCompany(OWNER, "비공개회사");
        hidden.changeVisibility(CompanyVisibility.PRIVATE);
        em.flush();

        Map<Long, CompanyVisibility> byId = repository.findOwnedBy(OWNER, PageRequest.of(0, 20))
                .getContent().stream()
                .collect(Collectors.toMap(CompanySummary::id, CompanySummary::visibility));

        // 비공개 회사가 목록에서 빠지지 않는다는 것 자체도 계약이다(공개 목록과 갈리는 지점).
        assertThat(byId).containsEntry(open.getId(), CompanyVisibility.PUBLIC);
        assertThat(byId).containsEntry(hidden.getId(), CompanyVisibility.PRIVATE);
    }

    // 국세청 확인 여부·등록/수정 시각도 visibility 와 같은 길(공유 컬럼 → fromOwner)로 나간다. 컬럼이 빠지거나
    // 매퍼 순서가 어긋나면 조용히 false/null 이 되므로 실 DB 로 확인한다.
    @Test
    @DisplayName("소유자 카드는 국세청 확인 여부와 등록·수정 시각을 담는다 (verified=에디터 선정과 별개)")
    void ownerCardCarriesManagementFields() {
        Company checked = persistCompany(OWNER, "국세청확인회사");
        checked.markBusinessVerified(LocalDateTime.now());
        Company unchecked = persistCompany(OWNER, "미확인회사");
        em.flush();

        Map<Long, CompanySummary> byId = repository.findOwnedBy(OWNER, PageRequest.of(0, 20))
                .getContent().stream()
                .collect(Collectors.toMap(CompanySummary::id, card -> card));

        assertThat(byId.get(checked.getId()).businessVerified()).isTrue();
        assertThat(byId.get(checked.getId()).verified()).isFalse(); // 축이 다르다 — 섞이지 않는다
        assertThat(byId.get(unchecked.getId()).businessVerified()).isFalse();
        assertThat(byId.values()).allSatisfy(card -> {
            assertThat(card.createdAt()).isNotNull();
            assertThat(card.updatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("페이지 크기를 넘으면 페이징되고 총 건수는 전체를 센다")
    void paginates() {
        for (int i = 0; i < 3; i++) {
            persistCompany(OWNER, "회사" + i);
        }
        em.flush();

        Page<CompanySummary> firstPage = repository.findOwnedBy(OWNER, PageRequest.of(0, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    // ===== helpers =====

    // 회사 + OWNER 멤버를 함께 저장한다. 소유는 company_member(role=OWNER) 로만 표현된다.
    private Company persistCompany(Long ownerId, String name) {
        Company c = Company.createByUser(ownerId, null, name, "대표자", null,
                Address.of("06236", "서울 강남구", null, "1층"), null, "logo-" + name, null,
                null, null, null, null, null, null, null, null);
        em.persist(c);
        em.persist(CompanyMember.owner(c.getId(), ownerId)); // IDENTITY 라 persist 직후 id 확정
        return c;
    }
}
