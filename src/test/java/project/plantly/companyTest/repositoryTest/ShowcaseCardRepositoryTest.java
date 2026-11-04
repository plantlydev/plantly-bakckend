package project.plantly.companyTest.repositoryTest;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.repository.ShowcaseCardRepository;
import project.plantly.domain.company.repository.ShowcaseCardRepository.ShowcaseRail;
import project.plantly.domain.company.search.dto.CompanySummary;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메인 화면 레일 조회. 스팟라이트·추천은 관리자가 켠 플래그(spotlight / featured)만 보고, 최근 등록은 플래그를
 * 일절 보지 않는다. 세 레일 모두 삭제·비공개 회사를 뺀다.
 */
@Transactional
@DisplayName("ShowcaseCardRepository: 메인 화면 노출 레일")
class ShowcaseCardRepositoryTest extends PostgresContainerTest {

    @Autowired EntityManager em;
    @Autowired ShowcaseCardRepository repository;

    private static final int SLOTS = 10;   // 자르기와 무관하게 후보만 보고 싶을 때 쓰는 넉넉한 자리 수

    @Test
    @DisplayName("스팟라이트는 관리자가 고정(pin)한 회사만 오른다")
    void spotlight_containsOnlyPinnedCompanies() {
        Company pinned = company("고정");
        pinned.turnOnSpotlight(1);
        company("미고정");
        em.flush();

        assertThat(ids(repository.findSpotlight(SLOTS))).containsExactly(pinned.getId());
    }

    @Test
    @DisplayName("고정된 회사끼리는 spotlightOrder 오름차순으로 온다")
    void pinnedCompanies_areOrderedBySpotlightOrder() {
        Company second = company("제휴사B");
        second.turnOnSpotlight(2);
        Company first = company("제휴사A");
        first.turnOnSpotlight(1);
        em.flush();

        assertThat(ids(repository.findSpotlight(SLOTS))).containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("고정을 해제하면 레일에서 빠진다")
    void turningOffSpotlight_removesFromRail() {
        Company company = company("해제");
        company.turnOnSpotlight(1);
        em.flush();
        company.turnOffSpotlight();
        em.flush();

        assertThat(repository.findSpotlight(SLOTS).cards()).isEmpty();
    }

    @Test
    @DisplayName("스팟라이트 카드는 로고와 별개로 커버 이미지·브랜드 컬러를 실어 온다")
    void spotlightCard_carriesCoverImageAndBrandColor() {
        Company pinned = company("고정");
        pinned.turnOnSpotlight(1);
        em.flush();

        CompanySummary card = repository.findSpotlight(SLOTS).cards().get(0);

        assertThat(card.id()).isEqualTo(pinned.getId());
        // 로고와 커버는 서로 다른 자리다 — 배너 배경에 로고가 깔리면 안 된다.
        assertThat(card.logoUrl()).isEqualTo("logo-고정");
        assertThat(card.coverImageUrl()).isEqualTo("cover-고정");
        assertThat(card.brandColor()).isEqualTo("#2E7D32");
    }

    @Test
    @DisplayName("커버·브랜드 컬러가 없는 회사도 레일에 오른다 — 두 값은 선택이라 노출과 무관하다")
    void cardWithoutCoverStyling_stillAppears() {
        Company plain = company("민무늬");
        plain.turnOnSpotlight(1);
        plain.updateBasicInfo(null, null, null, null, null, null, null,
                null, null, "", null, null, null, null, null, null, null, "");
        em.flush();

        CompanySummary card = repository.findSpotlight(SLOTS).cards().get(0);

        // null 로 내려가고, 폴백(자리표시자 + 기본 배너 색)은 화면이 정한다 — 서버가 대체값을 지어내지 않는다.
        assertThat(card.coverImageUrl()).isNull();
        assertThat(card.brandColor()).isNull();
    }

    @Test
    @DisplayName("삭제되거나 비공개인 회사는 고정돼 있어도 노출되지 않는다")
    void deletedOrPrivate_areExcluded() {
        Company visible = company("공개");
        visible.turnOnSpotlight(1);
        Company deleted = company("삭제");
        deleted.turnOnSpotlight(2);
        deleted.delete();
        Company priv = company("비공개");
        priv.turnOnSpotlight(3);
        priv.changeVisibility(CompanyVisibility.PRIVATE);
        em.flush();

        assertThat(ids(repository.findSpotlight(SLOTS))).containsExactly(visible.getId());
    }

    @Test
    @DisplayName("후보가 자리보다 많으면 자리 수만큼 자르되, candidateCount 는 자르기 전 총수를 그대로 알려준다")
    void overflow_isTruncatedButCounted() {
        for (int i = 0; i < 4; i++) {
            company("고정" + i).turnOnSpotlight(i);
        }
        em.flush();

        ShowcaseRail rail = repository.findSpotlight(2);

        assertThat(rail.cards()).hasSize(2);          // 노출은 자리 수만큼
        assertThat(rail.candidateCount()).isEqualTo(4); // 초과 감지의 근거는 자르기 전 총수
    }

    @Test
    @DisplayName("추천 레일은 featured 플래그만 본다 — 스팟라이트 고정과 무관하다")
    void featuredRail_onlyLooksAtFeatured() {
        Company featured = company("추천");
        featured.feature();
        company("고정미추천").turnOnSpotlight(1);
        em.flush();

        assertThat(ids(repository.findFeatured(SLOTS))).containsExactly(featured.getId());
    }

    // ===== 최근 등록 레일 =====
    // 앞의 두 레일과 정반대 성격이다. 저쪽은 관리자가 켠 플래그로 후보를 고르지만, 이쪽은 플래그를 일절
    // 보지 않는다 — 큐레이션 상위 노출은 목록/검색 화면이 지고, 이 지면은 시간순만 한다.

    @Test
    @DisplayName("최근 등록 레일은 등록 역순으로 온다")
    void latestRail_ordersByRegistrationDesc() {
        Company first = company("먼저");
        Company second = company("나중");
        Company third = company("마지막");
        em.flush();

        assertThat(idsOf(repository.findLatest(SLOTS)))
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    @DisplayName("최근 등록 레일은 큐레이션 플래그를 보지 않는다 — 고정·추천이 없는 회사도 전부 들어온다")
    void latestRail_ignoresCurationFlags() {
        Company plainA = company("평범A");
        Company plainB = company("평범B");
        em.flush();

        assertThat(repository.findSpotlight(SLOTS).cards()).isEmpty();   // 고정은 아무도 없는데
        assertThat(repository.findFeatured(SLOTS).cards()).isEmpty();    // 추천도 없는데
        assertThat(idsOf(repository.findLatest(SLOTS)))                    // 최근 등록에는 전원 등장한다
                .containsExactlyInAnyOrder(plainA.getId(), plainB.getId());
    }

    @Test
    @DisplayName("최근 등록 레일도 삭제·비공개 회사는 제외한다 — 가시성 규칙은 세 레일이 공유한다")
    void latestRail_excludesDeletedAndPrivate() {
        Company visible = company("공개");
        Company deleted = company("삭제");
        deleted.delete();
        Company priv = company("비공개");
        priv.changeVisibility(CompanyVisibility.PRIVATE);
        em.flush();

        assertThat(idsOf(repository.findLatest(SLOTS))).containsExactly(visible.getId());
    }

    @Test
    @DisplayName("최근 등록 레일은 자리 수만큼만 자르고 후보 총수는 세지 않는다")
    void latestRail_truncatesWithoutCountingCandidates() {
        List<Company> registered = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            registered.add(company("회사" + i));
        }
        em.flush();

        // 반환 타입이 ShowcaseRail 이 아니라 카드 목록이라는 것 자체가 계약이다 — 이 레일은 초과가
        // 정상이라 셀 이유가 없고, 세면 공개 회사 전체를 훑는 비용만 남는다.
        List<CompanySummary> cards = repository.findLatest(2);

        // 잘려도 '가장 최근 둘'이 남는다(자르기가 정렬 뒤에 일어난다는 뜻).
        assertThat(cards).extracting(CompanySummary::id)
                .containsExactly(registered.get(4).getId(), registered.get(3).getId());
    }

    @Test
    @DisplayName("후보가 없으면 빈 레일과 총수 0 을 반환한다")
    void noCandidates_returnsEmptyRail() {
        ShowcaseRail rail = repository.findSpotlight(SLOTS);

        assertThat(rail.cards()).isEmpty();
        assertThat(rail.candidateCount()).isZero();
    }

    // ===== helpers =====

    private List<Long> ids(ShowcaseRail rail) {
        return idsOf(rail.cards());
    }

    // 최근 등록 레일은 ShowcaseRail 을 쓰지 않는다(후보 총수를 세지 않으므로) — 카드 목록을 그대로 받는다.
    private List<Long> idsOf(List<CompanySummary> cards) {
        return cards.stream().map(CompanySummary::id).toList();
    }

    private Company company(String name) {
        // 커버·브랜드 컬러는 스팟라이트 카드가 배경·배너 색으로 쓰는 값이라 이 리포지토리 테스트에서 함께 채운다.
        Company company = Company.createByUser(10L, null, name, "대표자", null,
                Address.of("06236", "서울 강남구", null, "1층"), null, "logo-" + name, "cover-" + name,
                null, null, null, null, null, null, null, "#2E7D32");
        em.persist(company);
        return company;
    }
}
