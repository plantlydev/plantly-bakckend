package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDate;
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
 * 락 대기 상한 통합 테스트.
 *
 * <p>Postgres 의 락 대기는 기본이 무한이고, JPA 의 {@code jakarta.persistence.lock.timeout} 힌트는 Hibernate 6.6 의
 * Postgres 방언이 양수 값을 버려 효과가 없다. 그래서 락을 쥔 트랜잭션이 끝나지 않으면(커밋 안 한 DB 도구 세션,
 * 멈춘 요청) 같은 행을 원하는 요청이 커넥션을 쥔 채 끝없이 기다리고, 풀이 마르면 무관한 요청까지 멈춘다.
 *
 * <p>연결 단위 {@code lock_timeout} 으로 상한을 건다. 회사 행 락을 다른 트랜잭션이 놓지 않는 동안 수정 요청이
 * 상한 근처에서 락 획득 실패로 끝나는지 본다.
 */
@DisplayName("락 대기 상한")
class LockWaitTimeoutTest extends PostgresContainerTest {

    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyUpdateService updateService;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyMemberRepository memberRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private Long ownerId;
    private Long companyId;

    @BeforeEach
    void seed() {
        String businessNumber = String.format("%06d0001", ThreadLocalRandom.current().nextInt(1_000_000));
        ownerId = userRepository.save(User.create("lock-" + UUID.randomUUID() + "@plantly.test",
                "encodedPw!", "락테스트", "01000000000")).getId();
        companyId = companyRepository.save(Company.createByUser(ownerId, businessNumber, "락회사", "홍길동",
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null)).getId();
        memberRepository.save(CompanyMember.owner(companyId, ownerId));
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        jdbcTemplate.update("delete from company_member where company_id = ?", companyId);
        companyRepository.deleteById(companyId);
        userRepository.deleteById(ownerId);
    }

    @Test
    @DisplayName("Postgres 연결에는 3초 락 대기 상한이 걸려 있다")
    void connectionHasLockTimeout() {
        assertThat(jdbcTemplate.queryForObject("show lock_timeout", String.class)).isEqualTo("3s");
    }

    @Test
    @DisplayName("다른 트랜잭션이 회사 행 락을 놓지 않으면, 수정 요청은 무한 대기하지 않고 상한 근처에서 락 획득 실패로 끝난다")
    void lockHeldElsewhere_requestGivesUpAtTimeout() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 커밋하지 않고 회사 행 락을 쥔 채 머무는 세션(커밋을 잊은 DB 도구, 멈춘 요청).
        Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("select id from company where id = ? for update", Long.class, companyId);
            locked.countDown();
            awaitQuietly(release, 20);
        }));
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Future<?> request = executor.submit(
                    () -> updateService.changeVisibilityByUser(companyId, ownerId, CompanyVisibility.PRIVATE));

            // 상한이 없으면 holder 가 놓을 때까지(20초) 기다리므로 여기서 TimeoutException 으로 실패한다.
            assertThatThrownBy(() -> getOrThrowCause(request, 10))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(waited).isBetween(Duration.ofMillis(2_500), Duration.ofSeconds(6));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        // 거절된 수정은 반영되지 않았다.
        assertThat(companyRepository.findById(companyId).orElseThrow().getVisibility())
                .isEqualTo(CompanyVisibility.PUBLIC);
    }

    private static void getOrThrowCause(Future<?> future, int seconds) throws Exception {
        try {
            future.get(seconds, TimeUnit.SECONDS);
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
}
