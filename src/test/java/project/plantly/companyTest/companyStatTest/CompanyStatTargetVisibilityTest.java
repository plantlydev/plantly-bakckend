package project.plantly.companyTest.companyStatTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.stat.CompanyStatService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좋아요·즐겨찾기 대상 회사 검증 통합 테스트 (리뷰 "정책 확인" 2번).
 *
 * <p>공개 상세가 미존재로 취급하는 회사(삭제·비공개)에는 새 좋아요·즐겨찾기를 넣을 수 없어야 한다. 비공개 회사만
 * 통과시키면 "없는 id 는 404, 비공개 회사는 204" 라는 응답 차이로 비공개 회사의 존재가 드러난다. 그래서 셋을 같은
 * 404(COMPANY_NOT_FOUND)로 거절한다. 해제는 검증하지 않는다 — 회사가 나중에 숨겨져도 내 기록은 정리할 수 있어야 한다.
 */
@DisplayName("좋아요·즐겨찾기 대상 회사 검증")
class CompanyStatTargetVisibilityTest extends PostgresContainerTest {

    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyStatService statService;
    @Autowired CompanyRepository companyRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long userId;
    private String businessNumber;

    @BeforeEach
    void seed() {
        // 싱글톤 컨테이너를 다른 통합 테스트와 공유하므로 사용자·사업자번호를 매번 새로 뽑는다.
        businessNumber = String.format("%06d0001", ThreadLocalRandom.current().nextInt(1_000_000));
        userId = userRepository.save(User.create("stat-" + UUID.randomUUID() + "@plantly.test",
                "encodedPw!", "통계테스트", "01000000000")).getId();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from company_like where user_id = ?", userId);
        jdbcTemplate.update("delete from company_favorite where user_id = ?", userId);
        jdbcTemplate.update("delete from company where business_number = ?", businessNumber);
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("공개 회사에는 좋아요·즐겨찾기를 넣을 수 있다")
    void publicCompany_accepted() {
        Long companyId = saveCompany(CompanyVisibility.PUBLIC, false);

        statService.like(userId, companyId);
        statService.favorite(userId, companyId);

        assertThat(count("company_like")).isEqualTo(1);
        assertThat(count("company_favorite")).isEqualTo(1);
    }

    @Test
    @DisplayName("비공개 회사에 좋아요하면 없는 회사와 같은 404 로 거절하고 아무것도 남기지 않는다")
    void privateCompany_like_rejectedAsNotFound() {
        Long companyId = saveCompany(CompanyVisibility.PRIVATE, false);

        assertNotFound(() -> statService.like(userId, companyId));
        assertThat(count("company_like")).isZero();
    }

    @Test
    @DisplayName("비공개 회사에 즐겨찾기하면 없는 회사와 같은 404 로 거절하고 아무것도 남기지 않는다")
    void privateCompany_favorite_rejectedAsNotFound() {
        Long companyId = saveCompany(CompanyVisibility.PRIVATE, false);

        assertNotFound(() -> statService.favorite(userId, companyId));
        assertThat(count("company_favorite")).isZero();
    }

    @Test
    @DisplayName("삭제된 회사와 없는 회사도 같은 404 로 거절한다")
    void deletedOrMissingCompany_rejectedAsNotFound() {
        Long deletedId = saveCompany(CompanyVisibility.PUBLIC, true);

        assertNotFound(() -> statService.like(userId, deletedId));
        assertNotFound(() -> statService.favorite(userId, deletedId));
        assertNotFound(() -> statService.like(userId, Long.MAX_VALUE));
        assertNotFound(() -> statService.favorite(userId, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("공개일 때 넣은 좋아요·즐겨찾기는 회사가 비공개로 바뀐 뒤에도 해제할 수 있다")
    void becamePrivate_canStillRemove() {
        Long companyId = saveCompany(CompanyVisibility.PUBLIC, false);
        statService.like(userId, companyId);
        statService.favorite(userId, companyId);
        Company company = companyRepository.findById(companyId).orElseThrow();
        company.changeVisibility(CompanyVisibility.PRIVATE);
        companyRepository.save(company);

        statService.unlike(userId, companyId);
        statService.unfavorite(userId, companyId);

        assertThat(count("company_like")).isZero();
        assertThat(count("company_favorite")).isZero();
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.COMPANY_NOT_FOUND);
    }

    private Long saveCompany(CompanyVisibility visibility, boolean deleted) {
        Company company = Company.createByUser(userId, businessNumber, "통계회사", "홍길동",
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
        company.changeVisibility(visibility);
        if (deleted) {
            company.delete();
        }
        return companyRepository.save(company).getId();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }
}
