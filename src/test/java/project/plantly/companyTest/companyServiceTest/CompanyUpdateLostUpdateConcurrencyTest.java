package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.dto.AdminCompanyFlagsRequest;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.VerificationStatus;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationAttemptRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.company.service.CompanyVerificationWriter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 소유자 기본 정보 수정(PATCH) ↔ 관리자 변경 동시성 통합 테스트 (리뷰 지적 ③).
 *
 * <p>{@code Company} 에는 {@code @Version}·{@code @DynamicUpdate} 가 없어 Hibernate 가 dirty checking 때 <b>모든 컬럼</b>을
 * 다시 쓴다. 소유자 PATCH 가 회사를 읽은 뒤 커밋하기 전에 다른 트랜잭션이 회사 행을 바꾸고 커밋하면, PATCH 커밋이 읽어 둔
 * 옛 값으로 그 변경을 덮는다 — PATCH 가 건드리지도 않은 businessVerified·featured·ceoName 이 되돌아간다.
 *
 * <p>PATCH 를 "변경 적용 후, 커밋 전" 시점에 붙잡으려고 {@code mutateOwned} 가 커밋 직전에 부르는 검색 도큐먼트 writer(mock)의
 * 첫 호출을 래치로 막는다. 그 사이 관리자 경로를 다른 스레드에서 실행하고, 둘 다 끝난 뒤 최종 상태를 본다.
 * (수정 후에는 관리자 경로가 PATCH 의 행 락을 기다리므로, 관리자 경로가 끝나기를 먼저 기다리지 않는다.)
 */
@DisplayName("소유자 PATCH ↔ 관리자 변경 lost update")
class CompanyUpdateLostUpdateConcurrencyTest extends PostgresContainerTest {

    private static final Long OWNER_ID = 7201L;
    private static final String ORIGINAL_CEO = "홍길동";
    private static final String NEW_CEO = "김신임";

    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyUpdateService updateService;
    @Autowired CompanyVerificationWriter verificationWriter;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyMemberRepository memberRepository;
    @Autowired CompanyVerificationRepository verificationRepository;
    @Autowired CompanyVerificationAttemptRepository attemptRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final CountDownLatch patchApplied = new CountDownLatch(1);
    private final CountDownLatch patchCommit = new CountDownLatch(1);

    private String businessNumber;
    private Long companyId;

    @BeforeEach
    void seed() {
        businessNumber = String.format("%06d0001", ThreadLocalRandom.current().nextInt(1_000_000));

        Company company = Company.createByUser(OWNER_ID, businessNumber, "수정회사", ORIGINAL_CEO,
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
        company.markBusinessVerified(LocalDateTime.now().minusDays(1));
        companyId = companyRepository.save(company).getId();

        memberRepository.save(CompanyMember.owner(companyId, OWNER_ID));

        CompanyVerification verification = CompanyVerification.issue(OWNER_ID, businessNumber, ORIGINAL_CEO,
                LocalDate.of(2020, 1, 2), LocalDateTime.now().minusDays(1), Duration.ofDays(7));
        verification.consume(companyId);
        verificationRepository.save(verification);

        // 첫 재색인 호출(= PATCH 의 커밋 직전)만 붙잡고, 이후 호출(관리자·재인증 경로)은 그대로 통과시킨다.
        AtomicBoolean first = new AtomicBoolean(true);
        willAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                patchApplied.countDown();
                patchCommit.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).given(searchDocumentWriter).write(anyLong());
    }

    @AfterEach
    void cleanUp() {
        patchCommit.countDown();
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
    @DisplayName("PATCH 가 커밋 전일 때 관리자가 인증을 회수하면, PATCH 커밋이 회수를 되돌리지 않는다")
    void revokeDuringPatch_revokeSurvives() throws Exception {
        Future<?> patch = startPatchAndHoldBeforeCommit();

        Future<?> revoke = executor.submit(() -> updateService.revokeBusinessVerificationByAdmin(companyId, "사칭 신고"));
        releasePatchAndAwait(patch, revoke);

        Company company = companyRepository.findById(companyId).orElseThrow();
        assertThat(company.getIntroTitle()).isEqualTo("새 요약");          // PATCH 도 반영되고
        assertThat(company.isBusinessVerified()).isFalse();                 // 회수도 남는다
        assertThat(verificationRepository.findByCompanyId(companyId).orElseThrow().getStatus())
                .isEqualTo(VerificationStatus.REVOKED);
    }

    @Test
    @DisplayName("PATCH 가 커밋 전일 때 관리자가 운영 플래그를 바꾸면, PATCH 커밋이 플래그를 되돌리지 않는다")
    void flagsDuringPatch_flagsSurvive() throws Exception {
        Future<?> patch = startPatchAndHoldBeforeCommit();

        Future<?> flags = executor.submit(() -> updateService.changeFlagsByAdmin(companyId,
                new AdminCompanyFlagsRequest(null, true, null)));
        releasePatchAndAwait(patch, flags);

        Company company = companyRepository.findById(companyId).orElseThrow();
        assertThat(company.getIntroTitle()).isEqualTo("새 요약");
        assertThat(company.isFeatured()).isTrue();
    }

    @Test
    @DisplayName("PATCH 가 커밋 전일 때 재인증이 반영되면, PATCH 커밋이 국세청 검증값을 옛 값으로 되돌리지 않는다")
    void reverificationDuringPatch_verifiedIdentitySurvives() throws Exception {
        Future<?> patch = startPatchAndHoldBeforeCommit();

        Future<?> reverify = executor.submit(() -> verificationWriter.applyReverification(OWNER_ID, companyId,
                businessNumber, NEW_CEO, LocalDate.of(2021, 3, 4), "{\"raw\":true}", LocalDateTime.now()));
        releasePatchAndAwait(patch, reverify);

        Company company = companyRepository.findById(companyId).orElseThrow();
        assertThat(company.getIntroTitle()).isEqualTo("새 요약");
        // 회사 본체와 인증 레코드가 같은 검증값을 가리켜야 한다.
        assertThat(company.getCeoName()).isEqualTo(NEW_CEO);
        assertThat(verificationRepository.findByCompanyId(companyId).orElseThrow().getCeoName()).isEqualTo(NEW_CEO);
    }

    // 소유자가 한 줄 요약만 바꾸는 PATCH. 신원·인증 필드는 보내지 않는다(null = 미변경).
    private Future<?> startPatchAndHoldBeforeCommit() throws InterruptedException {
        CompanyUpdateRequest request = new CompanyUpdateRequest(null, null, null, null, null, null, null, null,
                null, null, "새 요약", null, null, null, null, null, null, null);
        Future<?> patch = executor.submit(() -> updateService.updateBasicInfoByUser(companyId, OWNER_ID, request));
        assertThat(patchApplied.await(5, TimeUnit.SECONDS)).isTrue();
        return patch;
    }

    // 락이 없다면 관리자 경로는 PATCH 를 기다리지 않고 먼저 커밋한다 — 그걸 기다린 뒤 PATCH 를 풀어준다.
    // 락이 있다면 관리자 경로는 PATCH 의 행 락에 막혀 있으므로 잠시 기다려도 끝나지 않는다(정상).
    private void releasePatchAndAwait(Future<?> patch, Future<?> other) throws Exception {
        try {
            other.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException blockedByPatchLock) {
            // 수정 후 기대 동작
        }
        patchCommit.countDown();
        patch.get(5, TimeUnit.SECONDS);
        other.get(5, TimeUnit.SECONDS);
    }
}
