package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.dto.CompanyReverificationRequest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyQueryService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 삭제된 회사에 대한 소유자 접근 통합 테스트 (리뷰 "정책 확인" 1번).
 *
 * <p>소프트 삭제된 회사는 소유자에게도 없는 회사다 — 소유자 목록·공개 경로와 같이 상세·수정·재인증도 404
 * (COMPANY_NOT_FOUND)로 거절한다. 삭제된 회사는 관리자만 보고 되살린다. 소유자의 삭제 재요청만 예외로 멱등 성공한다
 * (더블클릭·재시도가 오류로 보이지 않게). 재인증은 국세청 대기 중에 삭제가 커밋되는 경우도 반영 직전에 막아야 한다.
 */
@DisplayName("삭제된 회사의 소유자 접근")
class OwnerDeletedCompanyAccessTest extends PostgresContainerTest {

    @MockitoBean NtsClient ntsClient;
    @MockitoBean CompanySearchDocumentWriter searchDocumentWriter;

    @Autowired CompanyQueryService queryService;
    @Autowired CompanyUpdateService updateService;
    @Autowired CompanyVerificationService verificationService;
    @Autowired CompanyRepository companyRepository;
    @Autowired CompanyMemberRepository memberRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long ownerId;
    private Long strangerId;
    private String businessNumber;
    private Long companyId;
    private LocalDateTime verifiedAt;

    @BeforeEach
    void seed() {
        // 싱글톤 컨테이너를 다른 통합 테스트와 공유하므로 사용자·사업자번호를 매번 새로 뽑는다.
        businessNumber = String.format("%06d0001", ThreadLocalRandom.current().nextInt(1_000_000));
        ownerId = saveUser("owner");
        strangerId = saveUser("stranger");

        Company company = Company.createByUser(ownerId, businessNumber, "삭제회사", "홍길동",
                LocalDate.of(2020, 1, 2), Address.of("06236", "서울 강남구", null, "테헤란로 1"),
                null, "logo", null, "요약", "본문", null, null, null, null, null, null);
        verifiedAt = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MICROS);
        company.markBusinessVerified(verifiedAt);
        companyId = companyRepository.save(company).getId();
        memberRepository.save(CompanyMember.owner(companyId, ownerId));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from company_verification_attempt where business_number = ?", businessNumber);
        jdbcTemplate.update("delete from company_member where company_id = ?", companyId);
        jdbcTemplate.update("delete from company_tag where company_id = ?", companyId);
        companyRepository.deleteById(companyId);
        userRepository.deleteById(ownerId);
        userRepository.deleteById(strangerId);
    }

    @Test
    @DisplayName("소유자도 삭제된 자기 회사의 상세를 볼 수 없다 (404)")
    void ownerView_deleted_notFound() {
        updateService.deleteByUser(companyId, ownerId);

        assertNotFound(() -> queryService.getOwnerView(companyId, ownerId));
    }

    @Test
    @DisplayName("소유자는 삭제된 자기 회사를 수정할 수 없다 — 컬렉션·공개 범위 모두 404 이고 아무것도 바뀌지 않는다")
    void ownerMutations_deleted_notFound() {
        updateService.deleteByUser(companyId, ownerId);

        assertNotFound(() -> updateService.replaceTagsByUser(companyId, ownerId, List.of("태그")));
        assertNotFound(() -> updateService.changeVisibilityByUser(companyId, ownerId, CompanyVisibility.PRIVATE));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from company_tag where company_id = ?", Integer.class, companyId)).isZero();
        assertThat(companyRepository.findById(companyId).orElseThrow().getVisibility())
                .isEqualTo(CompanyVisibility.PUBLIC);
    }

    @Test
    @DisplayName("소유자는 삭제된 자기 회사를 재인증할 수 없다 — 국세청을 부르지 않고 한도도 쓰지 않는다")
    void reverify_deleted_notFoundBeforeNts() {
        updateService.deleteByUser(companyId, ownerId);

        assertNotFound(() -> verificationService.reverify(ownerId, companyId, reverificationRequest()));
        verify(ntsClient, never()).verify(any(), any(), any());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from company_verification_attempt where business_number = ?",
                Integer.class, businessNumber)).isZero();
    }

    @Test
    @DisplayName("국세청을 기다리는 사이 회사가 삭제되면, 재인증은 반영되지 않고 404 로 끝난다")
    void reverify_deletedWhileWaitingForNts_notApplied() {
        given(ntsClient.verify(any(), any(), any())).willAnswer(invocation -> {
            // 국세청 호출은 트랜잭션 밖이라, 그 사이 다른 요청(여기선 관리자)의 삭제가 커밋될 수 있다.
            updateService.deleteByAdmin(companyId);
            return new NtsClient.Result(VerificationOutcome.VALID, "{\"raw\":true}");
        });

        assertNotFound(() -> verificationService.reverify(ownerId, companyId, reverificationRequest()));

        Company company = companyRepository.findById(companyId).orElseThrow();
        assertThat(company.isDeleted()).isTrue();
        assertThat(company.getCeoName()).isEqualTo("홍길동");
        assertThat(company.getBusinessVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    @DisplayName("소유자의 삭제 재요청은 이미 삭제된 회사여도 멱등하게 성공한다")
    void ownerDelete_alreadyDeleted_idempotent() {
        updateService.deleteByUser(companyId, ownerId);

        updateService.deleteByUser(companyId, ownerId);

        assertThat(companyRepository.findById(companyId).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("소유자가 아니면 삭제된 회사에 대한 삭제 요청도 없는 회사와 같은 404 다")
    void strangerDelete_deleted_notFound() {
        updateService.deleteByUser(companyId, ownerId);

        assertNotFound(() -> updateService.deleteByUser(companyId, strangerId));
    }

    @Test
    @DisplayName("삭제되지 않은 회사는 지금처럼 소유자가 보고 고칠 수 있고, 소유자가 아니면 403 이다")
    void activeCompany_unchanged() {
        assertThat(queryService.getOwnerView(companyId, ownerId)).isNotNull();
        updateService.replaceTagsByUser(companyId, ownerId, List.of("태그"));

        assertThatThrownBy(() -> queryService.getOwnerView(companyId, strangerId))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.COMPANY_ACCESS_DENIED);
        assertThatThrownBy(() -> updateService.replaceTagsByUser(companyId, strangerId, List.of("남")))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.COMPANY_ACCESS_DENIED);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.COMPANY_NOT_FOUND);
    }

    private CompanyReverificationRequest reverificationRequest() {
        return new CompanyReverificationRequest("김신임", LocalDate.of(2021, 3, 4));
    }

    private Long saveUser(String prefix) {
        return userRepository.save(User.create(prefix + "-" + UUID.randomUUID() + "@plantly.test",
                "encodedPw!", "삭제접근", "01000000000")).getId();
    }
}
