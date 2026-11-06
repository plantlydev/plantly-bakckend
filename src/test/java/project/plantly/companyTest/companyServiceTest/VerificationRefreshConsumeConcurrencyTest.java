package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.enums.VerificationStatus;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationAttemptRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.domain.company.service.CompanyVerificationWriter;
import project.plantly.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 만료 인증 자동 재질의 ↔ 회사 등록(인증 소비) 동시성 통합 테스트 (리뷰 지적 ②).
 *
 * <p>자동 재질의({@code refreshIfExpired})는 "만료 판단 → 국세청 → 반영"이 서로 다른 트랜잭션이다. 만료된 인증으로
 * 발행을 두 번 누르면(A·B) 둘 다 만료로 판단하고 국세청을 부른다. A 가 먼저 갱신·등록·소비(CONSUMED)까지 끝낸 뒤
 * 늦게 도착한 B 의 반영이 그 인증을 VERIFIED(또는 EXPIRED)로 되돌리면 안 된다.
 * <ul>
 *   <li>시나리오 1·2: A 의 소비가 이미 커밋된 뒤 B 가 반영한다 — 반영 시점에 최종 상태를 다시 봐야 막힌다.</li>
 *   <li>시나리오 3: A 의 소비가 커밋 직전일 때 B 가 반영한다 — 행 락이 없으면 B 가 커밋된 옛 값을 읽고,
 *       A 커밋 뒤 전체 컬럼 UPDATE 로 status·companyId 를 덮는다.</li>
 * </ul>
 *
 * <p>A 는 "갱신 + 회사 등록의 소비"를 한 트랜잭션으로 줄여 흉내 낸다. 이 테스트의 관심은 인증 레코드의 최종 상태다.
 * 락 대기·READ COMMITTED 가시성은 DB 마다 다르므로 운영과 같은 Postgres 에서 검증한다.
 */
@DisplayName("만료 인증 자동 재질의 ↔ 인증 소비 동시성")
class VerificationRefreshConsumeConcurrencyTest extends PostgresContainerTest {

    private static final Long USER_ID = 7101L;
    private static final String CEO = "홍길동";
    private static final LocalDate START = LocalDate.of(2020, 1, 2);

    @MockitoBean NtsClient ntsClient;
    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyVerificationService verificationService;
    @Autowired CompanyVerificationWriter verificationWriter;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyVerificationRepository verificationRepository;
    @Autowired CompanyVerificationAttemptRepository attemptRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private String businessNumber;
    private Long companyId;
    private Long verificationId;

    @BeforeEach
    void seed() {
        // 싱글톤 컨테이너를 다른 통합 테스트와 공유하므로 활성 유니크에 걸리지 않게 번호를 매번 새로 뽑는다.
        businessNumber = String.format("%06d0001", ThreadLocalRandom.current().nextInt(1_000_000));

        // A 가 등록해 인증을 소비할 회사. (등록 자체는 이 테스트의 관심이 아니라 미리 만들어 둔다)
        Company company = Company.createByUser(USER_ID, businessNumber, "재질의회사", CEO, START,
                Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
        companyId = companyRepository.save(company).getId();

        // 8일 전 발급, 유효기간 7일 → 아직 VERIFIED 인데 기한이 지난(자동 재질의 대상) 인증.
        CompanyVerification verification = CompanyVerification.issue(USER_ID, businessNumber, CEO, START,
                LocalDateTime.now().minusDays(8), Duration.ofDays(7));
        verificationId = verificationRepository.save(verification).getId();
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        attemptRepository.findAll().stream()
                .filter(a -> businessNumber.equals(a.getBusinessNumber()))
                .forEach(attemptRepository::delete);
        if (verificationId != null) {
            verificationRepository.deleteById(verificationId);
        }
        if (companyId != null) {
            companyRepository.deleteById(companyId);
        }
    }

    @Test
    @DisplayName("국세청 응답을 기다리는 사이 다른 요청이 인증을 소비하면, 늦은 재질의 통과는 소비된 인증을 되살리지 않는다")
    void consumedWhileWaitingForNts_validRefreshDoesNotRevive() throws Throwable {
        CountDownLatch ntsRelease = givenNtsBlocks(VerificationOutcome.VALID);

        Future<?> lateRefresh = executor.submit(() -> verificationService.refreshIfExpired(USER_ID, verificationId));
        awaitNtsEntered();

        consumeAsAnotherRequest();
        ntsRelease.countDown();

        // 늦은 쪽은 갱신할 게 없을 뿐이다. 이어지는 등록 단계가 "이미 사용된 인증"으로 정상 거절한다.
        getOrThrowCause(lateRefresh);

        assertStillConsumed();
        // 국세청은 실제로 불렸으므로 반영을 건너뛰어도 자동 재질의 기록은 남아야 한다.
        assertThat(attemptsOf(VerificationOutcome.VALID)).isEqualTo(1);
    }

    @Test
    @DisplayName("국세청 응답을 기다리는 사이 다른 요청이 인증을 소비하면, 늦은 재질의 실패도 소비된 인증을 만료로 바꾸지 않는다")
    void consumedWhileWaitingForNts_failedRefreshDoesNotExpire() throws Throwable {
        CountDownLatch ntsRelease = givenNtsBlocks(VerificationOutcome.CLOSED);

        Future<?> lateRefresh = executor.submit(() -> verificationService.refreshIfExpired(USER_ID, verificationId));
        awaitNtsEntered();

        consumeAsAnotherRequest();
        ntsRelease.countDown();

        assertThatThrownBy(() -> getOrThrowCause(lateRefresh))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_CLOSED);

        assertStillConsumed();
        assertThat(attemptsOf(VerificationOutcome.CLOSED)).isEqualTo(1);
    }

    @Test
    @DisplayName("소비 트랜잭션이 커밋 전일 때 재질의 반영이 들어오면, 기다렸다가 소비된 상태를 보고 건너뛴다")
    void refreshArrivesWhileConsumeInFlight_waitsThenSkips() throws Throwable {
        CountDownLatch consumeFlushed = new CountDownLatch(1);
        CountDownLatch consumeCommit = new CountDownLatch(1);

        // 소비를 DB 에 반영(flush → 행 락)만 하고 커밋 직전에서 붙잡아 둔다.
        Future<?> consume = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            consumeVerification();
            verificationRepository.flush();
            consumeFlushed.countDown();
            awaitQuietly(consumeCommit);
        }));
        assertThat(consumeFlushed.await(5, TimeUnit.SECONDS)).isTrue();

        // 늦은 재질의 반영. 락 없이 읽으면 커밋된 옛 값(VERIFIED, companyId=null)을 들고 들어간다.
        LocalDateTime now = LocalDateTime.now();
        Future<?> lateRefresh = executor.submit(() -> verificationWriter.refresh(
                USER_ID, verificationId, now, Duration.ofDays(7), "{\"raw\":true}"));

        consumeCommit.countDown();
        consume.get(5, TimeUnit.SECONDS);
        getOrThrowCause(lateRefresh);

        assertStillConsumed();
    }

    // A: 먼저 도착한 요청이 재질의 갱신 → 회사 등록 → 인증 소비까지 마치고 커밋한다.
    private void consumeAsAnotherRequest() {
        transactionTemplate.executeWithoutResult(status -> consumeVerification());
    }

    private void consumeVerification() {
        CompanyVerification verification = verificationRepository.findById(verificationId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        verification.refresh(now, now.plusDays(7));
        verification.consume(companyId);
    }

    private void assertStillConsumed() {
        CompanyVerification verification = verificationRepository.findById(verificationId).orElseThrow();
        assertThat(verification.getStatus()).isEqualTo(VerificationStatus.CONSUMED);
        assertThat(verification.getCompanyId()).isEqualTo(companyId);
        // 상태 기계가 유지됐다는 실제 효과: 이 번호는 초안이 아니라 수정 대상으로 판정된다.
        assertThat(verificationRepository.existsConsumedByLiveCompany(USER_ID, businessNumber)).isTrue();
    }

    private long attemptsOf(VerificationOutcome outcome) {
        return attemptRepository.findAll().stream()
                .filter(a -> businessNumber.equals(a.getBusinessNumber()))
                .filter(a -> a.getOutcome() == outcome)
                .count();
    }

    private final CountDownLatch ntsEntered = new CountDownLatch(1);

    private CountDownLatch givenNtsBlocks(VerificationOutcome outcome) {
        CountDownLatch release = new CountDownLatch(1);
        given(ntsClient.verify(any(), any(), any())).willAnswer(invocation -> {
            ntsEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new NtsClient.Result(outcome, "{\"raw\":true}");
        });
        return release;
    }

    private void awaitNtsEntered() throws InterruptedException {
        assertThat(ntsEntered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void getOrThrowCause(Future<?> future) throws Throwable {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
