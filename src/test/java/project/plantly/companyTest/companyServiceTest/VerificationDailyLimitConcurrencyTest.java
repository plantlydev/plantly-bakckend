package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.dto.CompanyVerificationRequest;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.policy.VerificationProperties;
import project.plantly.domain.company.repository.CompanyVerificationAttemptRepository;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;

/**
 * 국세청 일일 시도 한도 동시성 통합 테스트 (리뷰 지적 ⑤).
 *
 * <p>verify 는 "오늘 시도 수를 센다 → 국세청을 부른다 → 결과가 나온 뒤 시도를 기록한다" 순서다. 확인과 기록 사이에
 * 국세청 대기(수백 ms~수 초)가 끼어 있어, 같은 사용자가 요청을 동시에 여러 개 보내면 모두 "아직 0회"를 보고 통과해
 * 한도(기본 5회)를 넘겨 국세청을 부른다.
 *
 * <p>국세청 mock 이 응답을 붙잡아 두는 동안 같은 사용자의 verify 를 한도의 두 배만큼 한꺼번에 보내고, 실제로 국세청에
 * 도달한 호출 수를 센다.
 */
@DisplayName("국세청 일일 시도 한도 병렬 우회")
class VerificationDailyLimitConcurrencyTest extends PostgresContainerTest {

    private static final String BUSINESS_NUMBER = "1234567890";

    @MockitoBean NtsClient ntsClient;

    @Autowired CompanyVerificationService verificationService;
    @Autowired CompanyVerificationAttemptRepository attemptRepository;
    @Autowired UserRepository userRepository;
    @Autowired VerificationProperties properties;

    private ExecutorService executor;
    private Long userId;

    @BeforeEach
    void seed() {
        // 한도 집계가 사용자 단위라, 다른 테스트의 시도와 섞이지 않게 매번 새 사용자를 만든다.
        userId = userRepository.save(User.create("limit-" + UUID.randomUUID() + "@plantly.test",
                "encodedPw!", "한도테스트", "01000000000")).getId();
    }

    @AfterEach
    void cleanUp() {
        if (executor != null) {
            executor.shutdownNow();
        }
        attemptRepository.findAll().stream()
                .filter(a -> userId.equals(a.getUserId()))
                .forEach(attemptRepository::delete);
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("같은 사용자가 한도의 두 배를 동시에 보내도 국세청 호출은 한도만큼만 나가고 나머지는 한도 초과로 거절된다")
    void concurrentVerify_cannotExceedDailyLimit() throws Exception {
        int limit = properties.dailyAttemptLimit();
        int requests = limit * 2;

        AtomicInteger ntsCalls = new AtomicInteger();
        AtomicInteger limitRejected = new AtomicInteger();
        CountDownLatch ntsRelease = new CountDownLatch(1);
        given(ntsClient.verify(any(), any(), any())).willAnswer(invocation -> {
            ntsCalls.incrementAndGet();
            ntsRelease.await(5, TimeUnit.SECONDS);
            return new NtsClient.Result(VerificationOutcome.MISMATCH, "{\"raw\":true}");
        });

        executor = Executors.newFixedThreadPool(requests);
        CyclicBarrier start = new CyclicBarrier(requests);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    verificationService.verify(userId,
                            new CompanyVerificationRequest(BUSINESS_NUMBER, "홍길동", LocalDate.of(2020, 1, 2)));
                } catch (BusinessException e) {
                    if (e.getErrorCode() == CompanyErrorCode.VERIFICATION_LIMIT_EXCEEDED) {
                        limitRejected.incrementAndGet();
                    }
                    throw e;
                }
                return null;
            }));
        }

        // 모든 요청이 "국세청 대기 중" 이거나 "한도 초과로 거절됨" 중 하나에 도달할 때까지 기다린 뒤 국세청을 풀어준다.
        long deadline = System.currentTimeMillis() + 5_000;
        while (ntsCalls.get() + limitRejected.get() < requests && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        ntsRelease.countDown();
        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                // MISMATCH 또는 한도 초과 — 둘 다 BusinessException 이고 개수로 검증한다
            }
        }

        assertThat(ntsCalls.get()).isEqualTo(limit);
        assertThat(limitRejected.get()).isEqualTo(requests - limit);
        assertThat(countedAttempts()).isEqualTo(limit);
    }

    @Test
    @DisplayName("국세청 장애로 끝난 시도는 한도에서 빠진다 — 장애가 한도만큼 이어져도 다음 시도는 국세청에 닿는다")
    void unavailableAttempts_doNotConsumeDailyLimit() {
        int limit = properties.dailyAttemptLimit();
        given(ntsClient.verify(any(), any(), any())).willThrow(new NtsClient.UnavailableException("down"));

        for (int i = 0; i < limit; i++) {
            assertThatThrownBy(this::verifyOnce)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_UNAVAILABLE);
        }

        // 장애가 회복된 뒤의 시도는 한도에 걸리지 않고 국세청 판정까지 간다.
        // 스텁이 던지는 상태라 given(mock.call()) 로 다시 묶으면 묶는 순간 예외가 난다 — willReturn 형태로 덮는다.
        willReturn(new NtsClient.Result(VerificationOutcome.MISMATCH, "{\"raw\":true}"))
                .given(ntsClient).verify(any(), any(), any());
        assertThatThrownBy(this::verifyOnce)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_MISMATCH);

        assertThat(countedAttempts()).isEqualTo(1);
    }

    private void verifyOnce() {
        verificationService.verify(userId,
                new CompanyVerificationRequest(BUSINESS_NUMBER, "홍길동", LocalDate.of(2020, 1, 2)));
    }

    // 일일 한도에 잡히는 시도 수(사용자 시도이면서 국세청 장애가 아닌 것).
    private long countedAttempts() {
        return attemptRepository.findAll().stream()
                .filter(a -> userId.equals(a.getUserId()))
                .filter(a -> !a.isAutomatic())
                .filter(a -> a.getOutcome().countsTowardDailyLimit())
                .count();
    }
}
