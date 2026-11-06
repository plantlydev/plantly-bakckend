package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.dto.CompanyReverificationRequest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.enums.VerificationStatus;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationAttemptRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyUpdateService;
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
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 재인증 ↔ 관리자 인증 회수 동시성 통합 테스트 (리뷰 지적 ①).
 *
 * <p>재인증은 국세청 호출을 트랜잭션 밖에 두므로 "확인(1단계) → 국세청 → 반영(2단계)" 사이에 틈이 있다.
 * 그 틈에 회수가 커밋되거나(시나리오 1), 회수 트랜잭션이 아직 진행 중일 때(시나리오 2) 반영이 실행되어도
 * 회수가 되돌려지면 안 된다. 시나리오 1 은 반영 시점 재확인이, 시나리오 2 는 회사 행 비관적 락이 막는다.
 *
 * <p>락 대기·READ COMMITTED 가시성은 DB 마다 다르므로 운영과 같은 Postgres 에서 검증한다.
 * 여러 스레드가 각자 커밋해야 하므로 테스트 메서드에 @Transactional 을 걸지 않고, 시드는 @AfterEach 에서 직접 지운다.
 * 검색 도큐먼트 writer 는 mock 으로 둔다 — 이 테스트의 관심은 회사 행 상태이고, 정리할 행도 줄어든다.
 */
@DisplayName("재인증 ↔ 관리자 회수 동시성")
class ReverificationRevokeConcurrencyTest extends PostgresContainerTest {

    private static final Long OWNER_ID = 7001L;
    private static final String NEW_CEO = "김신임";
    private static final LocalDate NEW_START = LocalDate.of(2021, 3, 4);

    @MockitoBean NtsClient ntsClient;
    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyVerificationService verificationService;
    @Autowired CompanyVerificationWriter verificationWriter;
    @Autowired CompanyUpdateService updateService;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyMemberRepository memberRepository;
    @Autowired CompanyVerificationRepository verificationRepository;
    @Autowired CompanyVerificationAttemptRepository attemptRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private String businessNumber;
    private Long companyId;

    @BeforeEach
    void seed() {
        // 싱글톤 컨테이너를 다른 통합 테스트와 공유하므로 활성 유니크에 걸리지 않게 번호를 매번 새로 뽑는다(끝자리 0001: Fake 규칙상 통과).
        businessNumber = String.format("%06d0001", ThreadLocalRandom.current().nextInt(1_000_000));

        Company company = Company.createByUser(OWNER_ID, businessNumber, "동시성회사", "홍길동",
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
        company.markBusinessVerified(LocalDateTime.now().minusDays(1));
        companyId = companyRepository.save(company).getId();

        memberRepository.save(CompanyMember.owner(companyId, OWNER_ID));

        CompanyVerification verification = CompanyVerification.issue(OWNER_ID, businessNumber, "홍길동",
                LocalDate.of(2020, 1, 2), LocalDateTime.now().minusDays(1), Duration.ofDays(7));
        verification.consume(companyId);
        verificationRepository.save(verification);
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        if (companyId == null) {
            return;
        }
        attemptRepository.findAll().stream()
                .filter(a -> businessNumber.equals(a.getBusinessNumber()))
                .forEach(attemptRepository::delete);
        verificationRepository.findByCompanyId(companyId).ifPresent(verificationRepository::delete);
        memberRepository.findAll().stream()
                .filter(m -> companyId.equals(m.getCompanyId()))
                .forEach(memberRepository::delete);
        companyRepository.deleteById(companyId);
    }

    @Test
    @DisplayName("국세청 응답을 기다리는 사이 회수가 커밋되면, 재인증 반영은 거부되고 회수가 유지된다")
    void revokeCommittedWhileWaitingForNts_reverificationRejected() throws Exception {
        CountDownLatch ntsEntered = new CountDownLatch(1);
        CountDownLatch ntsRelease = new CountDownLatch(1);
        given(ntsClient.verify(any(), any(), any())).willAnswer(invocation -> {
            ntsEntered.countDown();
            ntsRelease.await(5, TimeUnit.SECONDS);
            return new NtsClient.Result(VerificationOutcome.VALID, "{\"raw\":true}");
        });

        // 1단계(businessVerified 확인)는 통과한 뒤 국세청 응답에서 멈춘다.
        Future<?> reverify = executor.submit(() -> verificationService.reverify(
                OWNER_ID, companyId, new CompanyReverificationRequest(NEW_CEO, NEW_START)));
        assertThat(ntsEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // 그 사이 관리자가 회수를 커밋한다.
        updateService.revokeBusinessVerificationByAdmin(companyId, "사칭 신고");
        ntsRelease.countDown();

        assertThatThrownBy(() -> getOrThrowCause(reverify))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.COMPANY_NOT_BUSINESS_VERIFIED);

        assertRevokedAndUntouched();
        // 국세청은 실제로 불렸으므로 반영이 거부돼도 시도는 감사 로그와 일일 한도에 남아야 한다.
        assertThat(attemptRepository.findAll().stream()
                .filter(a -> businessNumber.equals(a.getBusinessNumber()))
                .filter(a -> a.getOutcome() == VerificationOutcome.VALID)
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("회수 트랜잭션이 커밋 전일 때 재인증 반영이 들어오면, 락을 기다렸다가 회수된 상태를 보고 거부된다")
    void reverificationArrivesWhileRevokeInFlight_waitsThenRejected() throws Exception {
        CountDownLatch revokeApplied = new CountDownLatch(1);
        CountDownLatch revokeCommit = new CountDownLatch(1);

        // 회수를 반영만 하고 커밋 직전에서 붙잡아 둔다(바깥 트랜잭션에 합류하므로 커밋은 execute 가 끝날 때).
        Future<?> revoke = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            updateService.revokeBusinessVerificationByAdmin(companyId, "사칭 신고");
            revokeApplied.countDown();
            awaitQuietly(revokeCommit);
        }));
        assertThat(revokeApplied.await(5, TimeUnit.SECONDS)).isTrue();

        // 반영 단계가 들어온다. 락이 없다면 커밋된 옛 값(businessVerified=true)을 읽고 재확인을 통과해버린다.
        Future<?> apply = executor.submit(() -> verificationWriter.applyReverification(
                OWNER_ID, companyId, businessNumber, NEW_CEO, NEW_START, "{\"raw\":true}", LocalDateTime.now()));

        // 회수가 행을 잡고 있는 동안 반영은 끝나지 못한다.
        assertThatThrownBy(() -> apply.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        revokeCommit.countDown();
        revoke.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> getOrThrowCause(apply))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.COMPANY_NOT_BUSINESS_VERIFIED);

        assertRevokedAndUntouched();
    }

    @Test
    @DisplayName("재인증 반영이 먼저 행을 잡으면 회수는 기다렸다가 그 위에 적용된다(나중에 온 회수가 이긴다)")
    void revokeArrivesWhileReverificationInFlight_waitsThenWins() throws Exception {
        CountDownLatch applyDone = new CountDownLatch(1);
        CountDownLatch applyCommit = new CountDownLatch(1);

        Future<?> apply = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            verificationWriter.applyReverification(OWNER_ID, companyId, businessNumber, NEW_CEO, NEW_START,
                    "{\"raw\":true}", LocalDateTime.now());
            applyDone.countDown();
            awaitQuietly(applyCommit);
        }));
        assertThat(applyDone.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> revoke = executor.submit(() -> updateService.revokeBusinessVerificationByAdmin(companyId, "사칭 신고"));
        assertThatThrownBy(() -> revoke.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        applyCommit.countDown();
        apply.get(5, TimeUnit.SECONDS);
        revoke.get(5, TimeUnit.SECONDS);

        Company company = companyRepository.findById(companyId).orElseThrow();
        assertThat(company.isBusinessVerified()).isFalse();
        assertThat(company.getCeoName()).isEqualTo(NEW_CEO);   // 재인증 값은 남고, 배지만 내려간다
        assertThat(verificationRepository.findByCompanyId(companyId).orElseThrow().getStatus())
                .isEqualTo(VerificationStatus.REVOKED);
    }

    // 회사는 미인증, 인증 레코드는 REVOKED, 재인증 값은 반영되지 않은 상태 — 회수 결과가 그대로 남아야 한다.
    private void assertRevokedAndUntouched() {
        Company company = companyRepository.findById(companyId).orElseThrow();
        assertThat(company.isBusinessVerified()).isFalse();
        assertThat(company.getBusinessVerifiedAt()).isNull();
        assertThat(company.getCeoName()).isEqualTo("홍길동");

        CompanyVerification verification = verificationRepository.findByCompanyId(companyId).orElseThrow();
        assertThat(verification.getStatus()).isEqualTo(VerificationStatus.REVOKED);
        assertThat(verification.getCeoName()).isEqualTo("홍길동");
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
