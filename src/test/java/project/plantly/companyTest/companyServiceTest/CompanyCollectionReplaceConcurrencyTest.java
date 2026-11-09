package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ContactRequest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyTag;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyTagRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyUpdateService;

import java.time.LocalDate;
import java.util.List;
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
 * 같은 회사의 컬렉션 전체 교체(PUT) 동시성 통합 테스트 (리뷰 지적 ④).
 *
 * <p>컬렉션 교체는 "파생 delete(조회 후 건별 삭제) → 새 리스트 저장"이다. 두 PUT 이 겹치면 —
 * <ul>
 *   <li><b>기존 행이 0건:</b> 둘 다 지울 게 없어 두 요청분 INSERT 가 모두 커밋된다. 연락처·레퍼런스의
 *       "1건, 대표 1개" 불변식이 깨져 대표가 2건이 된다.</li>
 *   <li><b>기존 행이 있음:</b> 두 트랜잭션이 같은 옛 행을 지우려 하고, 늦게 flush 하는 쪽의 DELETE 가 0건이 되어
 *       {@code ObjectOptimisticLockingFailureException}(핸들러 매핑 없음 → 500)으로 실패한다.</li>
 * </ul>
 * 이 테스트는 ③ 수정(회사 행 비관적 락, {@code CompanyUpdateService#loadCompany})이 두 경우를 함께 막는지 고정한다.
 * 락 없이 같은 교체 로직을 겹치면 위 두 결함이 그대로 재현됨을 실측으로 확인했다(대표 2건 / 첫 요청 낙관적 락 예외).
 *
 * <p>첫 PUT 을 "교체 적용 후, 커밋 전" 시점에 붙잡으려고 {@code mutateOwned} 가 커밋 직전에 부르는 검색 도큐먼트
 * writer(mock)의 첫 호출을 래치로 막는다({@code CompanyUpdateLostUpdateConcurrencyTest} 와 같은 방식).
 */
@DisplayName("컬렉션 동시 PUT 직렬화")
class CompanyCollectionReplaceConcurrencyTest extends PostgresContainerTest {

    private static final Long OWNER_ID = 7301L;

    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyUpdateService updateService;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyMemberRepository memberRepository;
    @Autowired CompanyTagRepository tagRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final CountDownLatch firstApplied = new CountDownLatch(1);
    private final CountDownLatch firstCommit = new CountDownLatch(1);

    private Company company;

    @BeforeEach
    void seed() {
        String businessNumber = String.format("%06d0002", ThreadLocalRandom.current().nextInt(1_000_000));
        company = companyRepository.save(Company.createByUser(OWNER_ID, businessNumber, "컬렉션회사", "홍길동",
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null));
        memberRepository.save(CompanyMember.owner(company.getId(), OWNER_ID));

        // 첫 재색인 호출(= 첫 PUT 의 커밋 직전)만 붙잡고, 두 번째 PUT 의 호출은 그대로 통과시킨다.
        AtomicBoolean first = new AtomicBoolean(true);
        willAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                firstApplied.countDown();
                firstCommit.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).given(searchDocumentWriter).write(anyLong());
    }

    @AfterEach
    void cleanUp() {
        firstCommit.countDown();
        executor.shutdownNow();
        if (company == null) {
            return;
        }
        jdbcTemplate.update("delete from company_contact where company_id = ?", company.getId());
        jdbcTemplate.update("delete from company_tag where company_id = ?", company.getId());
        jdbcTemplate.update("delete from company_member where company_id = ?", company.getId());
        companyRepository.deleteById(company.getId());
    }

    @Test
    @DisplayName("빈 연락처에 동시 PUT 이 겹쳐도 연락처는 나중 요청의 1건(대표 1건)만 남는다")
    void concurrentPutOnEmptyContacts_keepsSingleRepresentative() throws Exception {
        Future<?> first = executor.submit(() ->
                updateService.replaceContactsByUser(company.getId(), OWNER_ID, contact("첫요청")));
        assertThat(firstApplied.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> second = executor.submit(() ->
                updateService.replaceContactsByUser(company.getId(), OWNER_ID, contact("둘째요청")));

        releaseFirstAndAwait(first, second);

        assertThat(count("select count(*) from company_contact where company_id = ?")).isEqualTo(1);
        assertThat(count("select count(*) from company_contact where company_id = ? and representative")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select contact_name from company_contact where company_id = ?", String.class, company.getId()))
                .isEqualTo("둘째요청");
    }

    @Test
    @DisplayName("기존 태그가 있는 회사에 동시 PUT 이 겹쳐도 둘 다 성공하고 나중 요청의 리스트만 남는다")
    void concurrentPutOnExistingTags_bothSucceed() throws Exception {
        tagRepository.saveAll(List.of(new CompanyTag(company, "기존1", 0), new CompanyTag(company, "기존2", 1)));

        Future<?> first = executor.submit(() ->
                updateService.replaceTagsByUser(company.getId(), OWNER_ID, List.of("첫1", "첫2")));
        assertThat(firstApplied.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> second = executor.submit(() ->
                updateService.replaceTagsByUser(company.getId(), OWNER_ID, List.of("둘1", "둘2", "둘3")));

        releaseFirstAndAwait(first, second);   // 어느 쪽이든 예외면 get() 이 ExecutionException 으로 실패시킨다

        assertThat(jdbcTemplate.queryForList(
                "select tag_name from company_tag where company_id = ? order by display_order",
                String.class, company.getId()))
                .containsExactly("둘1", "둘2", "둘3");
    }

    private List<ContactRequest> contact(String name) {
        return List.of(new ContactRequest(name, null, "010-0000-0000", "contact@plantly.test"));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, company.getId());
    }

    // 락이 없다면 두 번째 PUT 은 첫 PUT 을 기다리지 않고 먼저 끝난다 — 그걸 기다린 뒤 첫 PUT 을 풀어준다.
    // 락이 있다면 두 번째 PUT 은 첫 PUT 의 행 락에 막혀 있으므로 잠시 기다려도 끝나지 않는다(정상).
    private void releaseFirstAndAwait(Future<?> first, Future<?> second) throws Exception {
        try {
            second.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException blockedByFirstLock) {
            // 수정 후 기대 동작
        }
        firstCommit.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
    }
}
