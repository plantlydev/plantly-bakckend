package project.plantly.companyTest.companyControllerTest;

import com.zaxxer.hikari.HikariDataSource;
import io.restassured.filter.cookie.CookieFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.plantly.AcceptanceTest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.enums.VerificationStatus;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.user.repository.UserRepository;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 재인증 엔드포인트를 실제 HTTP 로 호출하는 인수 테스트 — OSIV 가 켜진 운영과 같은 요청 경로를 탄다.
 *
 * <p>서비스를 직접 부르는 동시성 테스트({@code ReverificationRevokeConcurrencyTest})는 OSIV 인터셉터를 거치지 않아
 * "요청 하나에 EntityManager 하나" 조건을 재현하지 못한다. 여기서는 실서버(RANDOM_PORT)·실 필터 체인·
 * Boot 기본 OSIV 아래에서 두 가지를 본다.
 * <ol>
 *   <li>국세청 대기 중 관리자 회수가 커밋되면 재인증은 거부되고 회수가 유지되는가 (정합성)</li>
 *   <li>국세청을 기다리는 동안 DB 커넥션을 붙잡지 않는가 (커넥션 풀 보호)</li>
 * </ol>
 *
 * <p>국세청은 래치로 응답을 붙잡는 mock 으로 바꾼다. 검색 도큐먼트 writer 는 Postgres 전용 SQL 이라 H2 인수
 * 프로파일에서 mock 으로 둔다(이 테스트의 관심은 회사 행 상태와 커넥션이다). 관리자 회수는 서비스를 직접 부른다 —
 * 회수 쪽은 요청 안에서 회사를 미리 읽어 두는 단계가 없어 OSIV 영향을 받지 않는다.
 */
@DisplayName("재인증 HTTP 경로: OSIV 하의 회수 정합성·커넥션 점유")
class ReverificationOsivAcceptanceTest extends AcceptanceTest {

    private static final String REVERIFY_PATH = "/api/v1/companies/{id}/verification";
    private static final String BUSINESS_NUMBER = "1234560001";
    private static final String ORIGINAL_CEO = "홍길동";

    @MockitoBean NtsClient ntsClient;
    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired UserRepository userRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyMemberRepository memberRepository;
    @Autowired CompanyVerificationRepository verificationRepository;
    @Autowired CompanyUpdateService updateService;
    @Autowired DataSource dataSource;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch ntsEntered = new CountDownLatch(1);
    private final CountDownLatch ntsRelease = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        ntsRelease.countDown();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("국세청 응답을 기다리는 사이 관리자 회수가 커밋되면, 재인증은 거부되고 회수가 유지된다")
    void revokeDuringNtsWait_reverificationRejected() throws Exception {
        Session owner = signUpMember("osiv-revoke@example.com");
        Long companyId = seedVerifiedCompany(owner.userId());
        givenNtsBlocksUntilReleased();

        Future<Response> reverify = executor.submit(() -> reverify(owner, companyId));
        assertThat(ntsEntered.await(5, TimeUnit.SECONDS)).isTrue();

        updateService.revokeBusinessVerificationByAdmin(companyId, "사칭 신고");
        ntsRelease.countDown();

        Response response = reverify.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(400);

        Company company = companyRepository.findById(companyId).orElseThrow();
        assertThat(company.isBusinessVerified()).isFalse();
        assertThat(company.getCeoName()).isEqualTo(ORIGINAL_CEO);
        assertThat(verificationRepository.findByCompanyId(companyId).orElseThrow().getStatus())
                .isEqualTo(VerificationStatus.REVOKED);
    }

    @Test
    @DisplayName("국세청 응답을 기다리는 동안 요청은 DB 커넥션을 붙잡지 않는다")
    void ntsWait_holdsNoConnection() throws Exception {
        Session owner = signUpMember("osiv-conn@example.com");
        Long companyId = seedVerifiedCompany(owner.userId());
        givenNtsBlocksUntilReleased();

        Future<Response> reverify = executor.submit(() -> reverify(owner, companyId));
        assertThat(ntsEntered.await(5, TimeUnit.SECONDS)).isTrue();

        int activeDuringNtsWait = dataSource.unwrap(HikariDataSource.class)
                .getHikariPoolMXBean().getActiveConnections();

        ntsRelease.countDown();
        assertThat(reverify.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);

        assertThat(activeDuringNtsWait)
                .as("국세청 대기 중 점유 커넥션 수 (이 요청 외에 DB 를 쓰는 스레드는 없다)")
                .isZero();
    }

    private void givenNtsBlocksUntilReleased() {
        given(ntsClient.verify(any(), any(), any())).willAnswer(invocation -> {
            ntsEntered.countDown();
            ntsRelease.await(5, TimeUnit.SECONDS);
            return new NtsClient.Result(VerificationOutcome.VALID, "{\"raw\":true}");
        });
    }

    private Response reverify(Session owner, Long companyId) {
        return given()
                .filter(owner.cookies())
                .header("X-XSRF-TOKEN", owner.csrf())
                .contentType(ContentType.JSON)
                .body("""
                        {"ceoName":"김신임","businessStartDate":"2021-03-04"}
                        """)
                .when()
                .post(REVERIFY_PATH, companyId);
    }

    // 국세청 인증을 받아 등록된 회사 — 소유 멤버와 소비된(CONSUMED) 인증 레코드까지 갖춘다.
    private Long seedVerifiedCompany(Long ownerId) {
        Company company = Company.createByUser(ownerId, BUSINESS_NUMBER, "OSIV회사", ORIGINAL_CEO,
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
        company.markBusinessVerified(LocalDateTime.now().minusDays(1));
        Long companyId = companyRepository.save(company).getId();

        memberRepository.save(CompanyMember.owner(companyId, ownerId));

        CompanyVerification verification = CompanyVerification.issue(ownerId, BUSINESS_NUMBER, ORIGINAL_CEO,
                LocalDate.of(2020, 1, 2), LocalDateTime.now().minusDays(1), Duration.ofDays(7));
        verification.consume(companyId);
        verificationRepository.save(verification);
        return companyId;
    }

    private record Session(CookieFilter cookies, String csrf, long userId) {}

    // 회원가입 + 로그인까지 마친 멤버 세션. 로그인이 CSRF 토큰을 회전시키면 새 값을 쓴다.
    private Session signUpMember(String email) {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, email).then().statusCode(201);
        Response loginResponse = login(cookies, csrf, email, VALID_PASSWORD, false);
        loginResponse.then().statusCode(200);
        String rotated = loginResponse.cookie("XSRF-TOKEN");
        long userId = userRepository.findByEmail(email).orElseThrow().getId();
        return new Session(cookies, rotated != null ? rotated : csrf, userId);
    }
}
