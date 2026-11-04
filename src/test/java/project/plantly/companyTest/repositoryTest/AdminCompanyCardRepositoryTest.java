package project.plantly.companyTest.repositoryTest;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.category.Category;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyTag;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.entity.link.CompanyIndustry;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.RegistrationSource;
import project.plantly.domain.company.industry.Industry;
import project.plantly.domain.company.repository.AdminCompanyCardRepository;
import project.plantly.domain.company.search.AdminCompanySearchCriteria;
import project.plantly.domain.company.search.dto.AdminCompanySummary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@DisplayName("AdminCompanyCardRepository: 관리자 회사 목록 카드")
class AdminCompanyCardRepositoryTest extends PostgresContainerTest {

    @Autowired EntityManager em;
    @Autowired AdminCompanyCardRepository repository;

    // 조건 없는 전체 조회용 기준 criteria (모든 필터 null).
    private static final AdminCompanySearchCriteria ALL =
            new AdminCompanySearchCriteria(null, null, null, null, null, null);

    @Test
    @DisplayName("조건이 없으면 삭제 회사까지 포함한 전체를, 최신 등록순으로 반환한다")
    void noFilter_returnsAllIncludingDeletedLatestFirst() {
        Company older = persistOwned(10L, "오래된회사");
        Company deleted = persistOwned(10L, "삭제회사");
        deleted.delete();
        Company newer = persistOwned(10L, "최신회사");
        em.flush();

        Page<AdminCompanySummary> page = repository.findForAdmin(ALL, PageRequest.of(0, 20));

        assertThat(page.getContent().stream().map(AdminCompanySummary::id).toList())
                .containsExactly(newer.getId(), deleted.getId(), older.getId()); // 삭제 포함, 최신순
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("불리언 3-상태는 지정된 것만 AND(교집합)로 좁히고, null 인 필드는 무시한다")
    void booleanFilters_intersectOnlySpecified() {
        Company target = persistOwned(10L, "인증추천회사");
        target.verify();   // verified=true
        target.feature();  // featured=true
        // spotlight=false (기본)
        Company onlyVerified = persistOwned(10L, "인증만회사");
        onlyVerified.verify();
        em.flush();

        // verified=true AND featured=true, spotlight=null(무시) → 교집합은 target 하나
        AdminCompanySearchCriteria criteria =
                new AdminCompanySearchCriteria(true, true, null, null, null, null);

        Page<AdminCompanySummary> page = repository.findForAdmin(criteria, PageRequest.of(0, 20));

        assertThat(page.getContent().stream().map(AdminCompanySummary::id).toList())
                .containsExactly(target.getId());
    }

    @Test
    @DisplayName("deleted 필터: true=삭제만, false=활성만으로 가른다")
    void deletedFilter_splitsByState() {
        Company active = persistOwned(10L, "활성회사");
        Company removed = persistOwned(10L, "삭제회사");
        removed.delete();
        em.flush();

        Page<AdminCompanySummary> onlyDeleted = repository.findForAdmin(
                new AdminCompanySearchCriteria(null, null, null, true, null, null), PageRequest.of(0, 20));
        Page<AdminCompanySummary> onlyActive = repository.findForAdmin(
                new AdminCompanySearchCriteria(null, null, null, false, null, null), PageRequest.of(0, 20));

        assertThat(onlyDeleted.getContent().stream().map(AdminCompanySummary::id).toList())
                .containsExactly(removed.getId());
        assertThat(onlyActive.getContent().stream().map(AdminCompanySummary::id).toList())
                .containsExactly(active.getId());
    }

    @Test
    @DisplayName("회사명 부분일치와 소유자 id 정확 일치로 필터링한다")
    void companyNameAndOwnerFilters() {
        Company mine = persistOwned(10L, "플랜틀리테크");
        persistOwned(10L, "베타기계");        // 회사명 불일치
        persistOwned(99L, "플랜틀리상사");      // 소유자 불일치
        em.flush();

        Page<AdminCompanySummary> byName = repository.findForAdmin(
                new AdminCompanySearchCriteria(null, null, null, null, "플랜틀리", null), PageRequest.of(0, 20));
        Page<AdminCompanySummary> byOwner = repository.findForAdmin(
                new AdminCompanySearchCriteria(null, null, null, null, "플랜틀리", 10L), PageRequest.of(0, 20));

        assertThat(byName.getTotalElements()).isEqualTo(2);          // 플랜틀리테크 + 플랜틀리상사
        assertThat(byOwner.getContent().stream().map(AdminCompanySummary::id).toList())
                .containsExactly(mine.getId());                      // 회사명 AND 소유자 교집합
    }

    @Test
    @DisplayName("카드에 운영 필드(deleted/소유자/출처/등록시각)와 연결 이름 목록을 담는다")
    void cardCarriesOperationalFieldsAndNames() {
        Company adminRegistered = persist(Company.createByAdmin(500L, null, "관리자등록회사", "대표", null,
                Address.of("06236", "서울", null, "1층"), null, "logo", null, null, null, null, null, null, null, null, null));
        Company a = persistOwned(10L, "가가");
        Category root = Category.createRoot("제조", "MFG", null, null, 0);
        em.persist(root);
        Category precision = Category.createChild(root, "정밀가공", "MFG-P", null, null, 0);
        em.persist(precision);
        Industry industry = Industry.create("농업기술", "AGRI", null, null, 0);
        em.persist(industry);
        em.persist(new CompanyCategory(a, precision, 0));
        em.persist(new CompanyIndustry(a, industry, 0));
        em.persist(new CompanyTag(a, "스마트팜", 0));
        em.persist(new CompanyTag(a, "IoT", 1));
        em.flush();

        List<AdminCompanySummary> cards = repository.findForAdmin(ALL, PageRequest.of(0, 20)).getContent();
        AdminCompanySummary userCard = cards.stream().filter(c -> c.id().equals(a.getId())).findFirst().orElseThrow();
        AdminCompanySummary adminCard = cards.stream().filter(c -> c.id().equals(adminRegistered.getId())).findFirst().orElseThrow();

        // 유저 자가등록: 소유자=본인, 출처 USER, 미삭제, 이름 목록 집계
        assertThat(userCard.deleted()).isFalse();
        assertThat(userCard.ownerUserId()).isEqualTo(10L);
        assertThat(userCard.registrationSource()).isEqualTo(RegistrationSource.USER);
        assertThat(userCard.createdAt()).isNotNull();
        assertThat(userCard.updatedAt()).isNotNull();
        assertThat(userCard.businessVerified()).isFalse();
        assertThat(userCard.coverImageUrl()).isNull(); // 커버 미지정 → null(프론트가 자리표시자로 폴백)
        assertThat(userCard.categoryNames()).containsExactly("정밀가공"); // closure 조상(제조) 제외
        assertThat(userCard.tagNames()).containsExactly("스마트팜", "IoT");
        assertThat(userCard.industryNames()).containsExactly("농업기술");

        // 관리자 등록·미연동: 소유자 null, 출처 ADMIN
        assertThat(adminCard.ownerUserId()).isNull();
        assertThat(adminCard.registrationSource()).isEqualTo(RegistrationSource.ADMIN);
    }

    // ===== helpers =====

    // 유저 자가등록 회사 + OWNER 멤버를 함께 저장한다. 소유는 company_member(role=OWNER) 로만 표현된다.
    private Company persistOwned(Long ownerId, String name) {
        Company c = persist(userCompany(ownerId, name));
        em.persist(CompanyMember.owner(c.getId(), ownerId)); // IDENTITY 라 persist 직후 id 확정
        return c;
    }

    private Company userCompany(Long ownerId, String name) {
        return Company.createByUser(ownerId, null, name, "대표자", null,
                Address.of("06236", "서울 강남구", null, "1층"), null, "logo-" + name, null,
                null, null, null, null, null, null, null, null);
    }

    private Company persist(Company c) {
        em.persist(c);
        return c;
    }
}
