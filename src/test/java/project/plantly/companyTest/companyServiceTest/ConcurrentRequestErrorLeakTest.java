package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyDraftService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.company.stat.CompanyFavorite;
import project.plantly.domain.company.stat.CompanyFavoriteRepository;
import project.plantly.domain.company.stat.CompanyLike;
import project.plantly.domain.company.stat.CompanyLikeRepository;
import project.plantly.domain.company.stat.CompanyStatService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동시 요청이 500 으로 새는 경로 통합 테스트 (리뷰 지적 ⑥).
 *
 * <p>데이터는 안전하지만 응답 코드만 잘못되는 경로들이다. 사용자가 실제로 만드는 겹침(자동저장과 수동저장, 더블클릭)을
 * 재현해, 늦은 쪽 요청이 예외 없이 끝나거나(멱등이어야 하는 경로) 친화적 에러로 끝나는지(복구) 본다.
 *
 * <p><b>겹치는 방법:</b> 앞 요청을 바깥 트랜잭션으로 감싸 "작업은 했지만 커밋 전" 에서 붙잡고, 그 사이 뒤 요청을
 * 실행한다. 뒤 요청이 앞 요청의 행 락에 막히지 않으면(= 수정 전) 바로 끝나서 신호를 보내고, 앞 요청은 그 신호를
 * 받고 커밋한다. 막히면(= 수정 후 기대 동작) 앞 요청은 잠시 기다리다 스스로 커밋하고, 뒤 요청은 그 결과 위에서
 * 이어간다.
 */
@DisplayName("동시 요청 500 누출")
class ConcurrentRequestErrorLeakTest extends PostgresContainerTest {

    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyDraftService draftService;
    @Autowired CompanyStatService statService;
    @Autowired CompanyUpdateService updateService;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyVerificationRepository verificationRepository;
    @Autowired CompanyLikeRepository likeRepository;
    @Autowired CompanyFavoriteRepository favoriteRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private Long userId;
    private String businessNumber;

    @BeforeEach
    void seed() {
        // 싱글톤 컨테이너를 다른 통합 테스트와 공유하므로 사용자·사업자번호를 매번 새로 뽑는다(끝자리 0001: Fake 규칙상 통과).
        businessNumber = String.format("%06d0001", ThreadLocalRandom.current().nextInt(1_000_000));
        userId = userRepository.save(User.create("leak-" + UUID.randomUUID() + "@plantly.test",
                "encodedPw!", "누출테스트", "01000000000")).getId();
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        jdbcTemplate.update("delete from company_like where user_id = ?", userId);
        jdbcTemplate.update("delete from company_favorite where user_id = ?", userId);
        jdbcTemplate.update("delete from company_draft where user_id = ?", userId);
        jdbcTemplate.update("delete from company_verification where business_number = ?", businessNumber);
        jdbcTemplate.update("delete from company where business_number = ?", businessNumber);
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("같은 초안의 첫 저장이 겹쳐도(자동저장 + 수동저장) 둘 다 성공하고 초안은 1건, 나중 저장 내용이 남는다")
    void concurrentFirstDraftSave_bothSucceed() throws Exception {
        verificationRepository.save(CompanyVerification.issue(userId, businessNumber, "홍길동",
                LocalDate.of(2020, 1, 2), LocalDateTime.now(), Duration.ofDays(7)));

        race(() -> draftService.save(userId, businessNumber, draft("첫저장")),
             () -> draftService.save(userId, businessNumber, draft("둘째저장")));

        List<String> payloads = jdbcTemplate.queryForList(
                "select payload from company_draft where user_id = ?", String.class, userId);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).contains("둘째저장");
    }

    @Test
    @DisplayName("초안 폐기가 겹쳐도(더블클릭) 둘 다 성공한다")
    void concurrentDraftDelete_bothSucceed() throws Exception {
        verificationRepository.save(CompanyVerification.issue(userId, businessNumber, "홍길동",
                LocalDate.of(2020, 1, 2), LocalDateTime.now(), Duration.ofDays(7)));
        draftService.save(userId, businessNumber, draft("지울초안"));

        race(() -> draftService.delete(userId, businessNumber),
             () -> draftService.delete(userId, businessNumber));

        assertThat(count("select count(*) from company_draft where user_id = ?")).isZero();
    }

    @Test
    @DisplayName("좋아요 해제가 겹쳐도(더블클릭) 둘 다 성공한다")
    void concurrentUnlike_bothSucceed() throws Exception {
        Long companyId = saveCompany(false);
        likeRepository.save(new CompanyLike(userId, companyId));

        race(() -> statService.unlike(userId, companyId),
             () -> statService.unlike(userId, companyId));

        assertThat(count("select count(*) from company_like where user_id = ?")).isZero();
    }

    @Test
    @DisplayName("즐겨찾기 해제가 겹쳐도(더블클릭) 둘 다 성공한다")
    void concurrentUnfavorite_bothSucceed() throws Exception {
        Long companyId = saveCompany(false);
        favoriteRepository.save(new CompanyFavorite(userId, companyId));

        race(() -> statService.unfavorite(userId, companyId),
             () -> statService.unfavorite(userId, companyId));

        assertThat(count("select count(*) from company_favorite where user_id = ?")).isZero();
    }

    @Test
    @DisplayName("복구하는 순간 같은 사업자번호로 활성 회사가 커밋되면, 복구는 500 이 아니라 BUSINESS_NUMBER_TAKEN 으로 거절된다")
    void restoreWhileSameNumberRegistered_rejectedAsTaken() throws Exception {
        Long deletedId = saveCompany(true);

        // 앞 요청 = 같은 번호의 신규 등록(INSERT 는 했지만 커밋 전), 뒤 요청 = 관리자 복구.
        Future<?>[] futures = start(() -> companyRepository.saveAndFlush(newCompany()),
                                    () -> updateService.restoreByAdmin(deletedId));

        futures[0].get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> getOrThrowCause(futures[1]))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.BUSINESS_NUMBER_TAKEN);
        assertThat(companyRepository.findById(deletedId).orElseThrow().isDeleted()).isTrue();
    }

    // ===== 겹치기 =====

    // 두 작업을 겹쳐 실행하고, 둘 다 예외 없이 끝나야 한다.
    private void race(Runnable first, Runnable second) throws Exception {
        Future<?>[] futures = start(first, second);
        for (Future<?> future : futures) {
            getOrThrowCause(future);
        }
    }

    // first 를 바깥 트랜잭션 안에서 실행하고 커밋 전에 붙잡은 채 second 를 실행한다.
    private Future<?>[] start(Runnable first, Runnable second) {
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        Future<?> firstFuture = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            first.run();
            firstApplied.countDown();
            // 수정 전: second 가 락에 안 막혀 바로 끝나므로 그 신호를 받고 커밋한다.
            // 수정 후: second 가 first 의 행 락에 막혀 신호가 안 온다 — 잠시 기다린 뒤 커밋해 풀어준다.
            awaitQuietly(secondFinished, 1);
        }));
        Future<?> secondFuture = executor.submit(() -> {
            awaitQuietly(firstApplied, 5);
            try {
                second.run();
            } finally {
                secondFinished.countDown();
            }
        });
        return new Future<?>[]{firstFuture, secondFuture};
    }

    private static void getOrThrowCause(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void awaitQuietly(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== 픽스처 =====

    private MyCompanyCreateRequest draft(String companyName) {
        // 초안 저장은 검증하지 않으므로 회사명만 채운다(나머지 27개 필드는 비움). 나중 저장이 이겼는지 회사명으로 가린다.
        return new MyCompanyCreateRequest(null, companyName,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Long saveCompany(boolean deleted) {
        Company company = newCompany();
        if (deleted) {
            company.delete();
        }
        return companyRepository.save(company).getId();
    }

    private Company newCompany() {
        return Company.createByUser(userId, businessNumber, "누출회사", "홍길동",
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, userId);
    }
}
