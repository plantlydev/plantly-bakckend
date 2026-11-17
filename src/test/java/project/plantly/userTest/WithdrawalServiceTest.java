package project.plantly.userTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.rejoin.RejoinRestriction;
import project.plantly.domain.auth.social.rejoin.RejoinTraceHasher;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyDraft;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.CompanyVerificationAttempt;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.repository.CompanyDraftRepository;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationAttemptRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.stat.CompanyLikeRepository;
import project.plantly.domain.company.stat.CompanyStatService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.UserService;
import project.plantly.domain.user.consent.ConsentType;
import project.plantly.domain.user.consent.UserConsent;
import project.plantly.domain.user.consent.repository.UserConsentRepository;
import project.plantly.domain.user.dto.request.UpdateProfileRequest;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.domain.user.withdrawal.WithdrawalErrorCode;
import project.plantly.domain.user.withdrawal.WithdrawalResult;
import project.plantly.domain.user.withdrawal.WithdrawalService;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.exception.CommonErrorCode;
import project.plantly.global.exception.ErrorCode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

// 회원 탈퇴 통합 테스트(실제 Postgres). 무엇을 지우고 무엇을 남기는지, 거절 조건, 겹친 요청과의 직렬화를 본다.
// 다른 Postgres 통합 테스트와 같은 MockitoBean 구성이라 스프링 컨텍스트를 공유한다.
@DisplayName("회원 탈퇴")
class WithdrawalServiceTest extends PostgresContainerTest {

    @MockitoBean NtsClient ntsClient;
    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired WithdrawalService withdrawalService;
    @Autowired UserService userService;
    @Autowired CompanyStatService statService;
    @Autowired UserRepository userRepository;
    @Autowired SocialAccountRepository socialAccountRepository;
    @Autowired RejoinRestriction rejoinRestriction;
    @Autowired RejoinTraceHasher hasher;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyMemberRepository memberRepository;
    @Autowired CompanyDraftRepository draftRepository;
    @Autowired CompanyLikeRepository likeRepository;
    @Autowired CompanyVerificationRepository verificationRepository;
    @Autowired CompanyVerificationAttemptRepository attemptRepository;
    @Autowired UserConsentRepository consentRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> companyIds = new ArrayList<>();
    private final List<String> naverIds = new ArrayList<>();

    private Long memberId;
    private String memberEmail;
    private String naverId;

    @BeforeEach
    void seedMember() {
        memberEmail = "member-" + UUID.randomUUID() + "@plantly.test";
        memberId = saveUser(memberEmail, UserRole.MEMBER, UserStatus.ACTIVE);
        naverId = linkNaver(memberId, memberEmail);
    }

    @AfterEach
    void cleanUp() {
        for (Long companyId : companyIds) {
            jdbcTemplate.update("delete from company_like where company_id = ?", companyId);
            jdbcTemplate.update("delete from company_favorite where company_id = ?", companyId);
            jdbcTemplate.update("delete from company_contact where company_id = ?", companyId);
            jdbcTemplate.update("delete from company_member where company_id = ?", companyId);
            jdbcTemplate.update("delete from company where id = ?", companyId);
        }
        for (Long userId : userIds) {
            for (String table : List.of("company_like", "company_favorite", "company_member", "company_draft",
                    "company_verification", "company_verification_attempt", "social_account", "user_consent")) {
                jdbcTemplate.update("delete from " + table + " where user_id = ?", userId);
            }
            jdbcTemplate.update("delete from users where id = ?", userId);
        }
        for (String id : naverIds) {
            jdbcTemplate.update("delete from rejoin_trace where account_hash = ?", hasher.hash(SocialProvider.NAVER, id));
        }
    }

    @Test
    @DisplayName("소유 회사는 함께 삭제되고, 회원에 딸린 데이터는 지워지고, 회원은 익명화된다 — 동의 기록·남의 데이터는 남는다")
    void withdrawCleansUp() {
        String bn = randomBusinessNumber();
        Long liveCompany = saveCompany(memberId, bn);
        Long alreadyDeleted = saveCompany(memberId, randomBusinessNumber());
        companyRepository.findById(alreadyDeleted).ifPresent(c -> {
            c.delete();
            companyRepository.save(c);
        });

        Long otherId = saveUser("other-" + UUID.randomUUID() + "@plantly.test", UserRole.MEMBER, UserStatus.ACTIVE);
        Long othersCompany = saveCompany(otherId, randomBusinessNumber());
        insertContact(liveCompany);
        insertContact(alreadyDeleted);
        insertContact(othersCompany);

        insertStat("company_like", memberId, othersCompany);
        insertStat("company_favorite", memberId, othersCompany);
        insertStat("company_like", otherId, liveCompany);          // 남이 내 회사에 남긴 좋아요는 남는다
        draftRepository.save(CompanyDraft.create(memberId, randomBusinessNumber(), "{}"));
        verificationRepository.save(CompanyVerification.issue(memberId, bn, "홍길동",
                LocalDate.of(2020, 1, 2), LocalDateTime.now(), Duration.ofDays(7)));
        attemptRepository.save(CompanyVerificationAttempt.of(memberId, bn, "홍길동",
                LocalDate.of(2020, 1, 2), VerificationOutcome.VALID, "{}"));
        consentRepository.save(UserConsent.record(memberId, ConsentType.TERMS_OF_SERVICE, "v1", true));

        WithdrawalResult result = withdrawalService.withdraw(memberId);

        assertThat(result.deletedCompanies()).isEqualTo(2);
        assertThat(result.providers()).containsExactly(SocialProvider.NAVER);

        // 소유 회사: 함께 삭제(soft) + 대표자명 익명화·국세청 인증 회수·연락처 삭제·검색 도큐먼트 갱신.
        // 이미 삭제돼 있던 회사도 같은 처리를 받는다(대표자명·연락처·도큐먼트가 남아 있었으므로).
        for (Long companyId : List.of(liveCompany, alreadyDeleted)) {
            Company company = companyRepository.findById(companyId).orElseThrow();
            assertThat(company.isDeleted()).isTrue();
            assertThat(company.getCeoName()).isEqualTo(User.WITHDRAWN_NAME);
            assertThat(company.isBusinessVerified()).isFalse();
            assertThat(company.getBusinessVerifiedAt()).isNull();
            assertThat(company.getBusinessNumber()).isNotNull();   // 사업자번호는 남는다(활성 유니크·분쟁 기록)
            assertThat(countContacts(companyId)).isZero();
            verify(searchDocumentWriter).write(companyId);
        }
        // 남의 회사는 그대로.
        Company others = companyRepository.findById(othersCompany).orElseThrow();
        assertThat(others.isDeleted()).isFalse();
        assertThat(others.getCeoName()).isEqualTo("홍길동");
        assertThat(others.isBusinessVerified()).isTrue();
        assertThat(countContacts(othersCompany)).isEqualTo(1);

        // 회원에 딸린 데이터는 전부 지워진다.
        for (String table : List.of("company_member", "company_draft", "company_like", "company_favorite",
                "company_verification", "company_verification_attempt", "social_account")) {
            assertThat(countByUser(table, memberId)).as(table).isZero();
        }
        // 남는 것: 동의 기록, 남이 내 회사에 남긴 좋아요, 남의 소유 연결
        assertThat(countByUser("user_consent", memberId)).isEqualTo(1);
        assertThat(likeRepository.existsByUserIdAndCompanyId(otherId, liveCompany)).isTrue();
        assertThat(memberRepository.existsByCompanyIdAndUserId(othersCompany, otherId)).isTrue();

        // 회원 행은 남고 개인정보만 지워진다. 원래 이메일은 풀린다.
        User user = userRepository.findById(memberId).orElseThrow();
        assertThat(user.getUserStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getEmail()).isEqualTo("withdrawn-" + memberId + "@withdrawn.invalid");
        assertThat(user.getName()).isEqualTo(User.WITHDRAWN_NAME);
        assertThat(user.getPhone()).isNull();
        assertThat(user.getNickname()).isNull();
        assertThat(user.getPassword()).isNotEqualTo("encodedPw!");
        assertThat(userRepository.existsByEmail(memberEmail)).isFalse();

        // 같은 소셜 계정은 재가입 제한 기간에 들어간다.
        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, naverId)).isTrue();
    }

    @Test
    @DisplayName("소유 회사가 없어도 탈퇴된다")
    void withdrawWithoutCompanies() {
        WithdrawalResult result = withdrawalService.withdraw(memberId);

        assertThat(result.deletedCompanies()).isZero();
        assertThat(userRepository.findById(memberId).orElseThrow().getUserStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("관리자는 탈퇴할 수 없다 — 아무것도 바뀌지 않는다")
    void adminCannotWithdraw() {
        jdbcTemplate.update("update users set user_role = 'ADMIN' where id = ?", memberId);

        assertError(() -> withdrawalService.withdraw(memberId), WithdrawalErrorCode.ADMIN_WITHDRAWAL_NOT_ALLOWED);

        assertUntouched();
    }

    @Test
    @DisplayName("정지·이미 탈퇴한 회원은 탈퇴할 수 없다")
    void inactiveCannotWithdraw() {
        jdbcTemplate.update("update users set user_status = 'SUSPENDED' where id = ?", memberId);

        assertError(() -> withdrawalService.withdraw(memberId), WithdrawalErrorCode.WITHDRAWAL_NOT_ALLOWED);

        assertThat(socialAccountRepository.findByUserId(memberId)).hasSize(1);
        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, naverId)).isFalse();
    }

    @Test
    @DisplayName("두 번 탈퇴하면 두 번째는 거절된다(이미 WITHDRAWN)")
    void withdrawTwice() {
        withdrawalService.withdraw(memberId);

        assertError(() -> withdrawalService.withdraw(memberId), WithdrawalErrorCode.WITHDRAWAL_NOT_ALLOWED);
    }

    @Test
    @DisplayName("탈퇴와 겹친 프로필 수정은 탈퇴가 끝날 때까지 기다렸다가 401 — 익명화를 덮어써 ACTIVE 로 되살리지 않는다")
    void profileUpdateRacingWithdrawal() throws Exception {
        assertRacingWriteStops(() -> userService.updateUserProfile(memberId, new UpdateProfileRequest("새이름", null, "01099998888")));

        User user = userRepository.findById(memberId).orElseThrow();
        assertThat(user.getUserStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getName()).isEqualTo(User.WITHDRAWN_NAME);
        assertThat(user.getPhone()).isNull();
    }

    @Test
    @DisplayName("탈퇴와 겹친 좋아요는 탈퇴가 끝날 때까지 기다렸다가 401 — 탈퇴한 회원의 좋아요가 남지 않는다")
    void likeRacingWithdrawal() throws Exception {
        Long otherId = saveUser("other-" + UUID.randomUUID() + "@plantly.test", UserRole.MEMBER, UserStatus.ACTIVE);
        Long othersCompany = saveCompany(otherId, randomBusinessNumber());

        assertRacingWriteStops(() -> statService.like(memberId, othersCompany));

        assertThat(likeRepository.existsByUserIdAndCompanyId(memberId, othersCompany)).isFalse();
    }

    // 탈퇴 트랜잭션이 회원 행을 쥔 채 커밋 전에 잠시 머무는 동안 다른 스레드가 쓰기를 시도한다.
    // 쓰기는 락에서 기다렸다가, 탈퇴가 커밋된 뒤 WITHDRAWN 을 보고 UNAUTHORIZED 로 멈춰야 한다.
    private void assertRacingWriteStops(Runnable write) throws Exception {
        CountDownLatch withdrawalHoldsLock = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> withdrawal = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            withdrawalService.withdraw(memberId);
            withdrawalHoldsLock.countDown();
            sleep(500);
        }));
        assertThat(withdrawalHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Throwable> racing = CompletableFuture.supplyAsync(() -> {
            try {
                write.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        withdrawal.get(10, TimeUnit.SECONDS);
        Throwable error = racing.get(10, TimeUnit.SECONDS);
        assertThat(error).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED));
    }

    private void assertUntouched() {
        User user = userRepository.findById(memberId).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(memberEmail);
        assertThat(user.getUserStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(socialAccountRepository.findByUserId(memberId)).hasSize(1);
        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, naverId)).isFalse();
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private Long saveUser(String email, UserRole role, UserStatus status) {
        Long id = userRepository.save(User.builder()
                .email(email).password("encodedPw!").name("탈퇴테스트").phone("01000000000").nickname("닉")
                .userRole(role).userStatus(status).build()).getId();
        userIds.add(id);
        return id;
    }

    private String linkNaver(Long userId, String email) {
        String id = "naver-" + UUID.randomUUID();
        socialAccountRepository.save(SocialAccount.link(userId, SocialProvider.NAVER, id, email));
        naverIds.add(id);
        return id;
    }

    private Long saveCompany(Long ownerId, String businessNumber) {
        Company company = Company.createByUser(ownerId, businessNumber, "탈퇴회사", "홍길동",
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
        company.markBusinessVerified(LocalDateTime.now());
        Long id = companyRepository.save(company).getId();
        memberRepository.save(CompanyMember.owner(id, ownerId));
        companyIds.add(id);
        return id;
    }

    private void insertStat(String table, Long userId, Long companyId) {
        jdbcTemplate.update("insert into " + table + " (user_id, company_id, created_at) values (?, ?, now())",
                userId, companyId);
    }

    private void insertContact(Long companyId) {
        jdbcTemplate.update("insert into company_contact (company_id, contact_name, position, phone, email, display_order,"
                + " representative, created_at, updated_at) values (?, '담당자', '팀장', '01012345678', 'contact@plantly.test',"
                + " 0, true, now(), now())", companyId);
    }

    private int countContacts(Long companyId) {
        return jdbcTemplate.queryForObject("select count(*) from company_contact where company_id = ?", Integer.class, companyId);
    }

    private int countByUser(String table, Long userId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }

    private static String randomBusinessNumber() {
        return String.format("%06d%04d", ThreadLocalRandom.current().nextInt(1_000_000),
                ThreadLocalRandom.current().nextInt(10_000));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
