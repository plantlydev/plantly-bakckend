package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.plantly.companyTest.support.CompanyFixture;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.companyTest.support.CompanyVerificationFixture;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.enums.VerificationStatus;

import java.time.LocalDateTime;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyChildWriter;
import project.plantly.domain.company.service.CompanyLinkWriter;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// CompanyUpdateService 단위 테스트: 소유 검증·신원 필드 보호·인증 회수·컬렉션 교체 후 재동기화를 본다. (DB·writer 만 mock)
// 컬렉션 개수 한도는 요청 DTO 의 @Size 가 걸기 때문에 서비스 단위에서는 검증할 것이 없다.
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyUpdateService: 수정 경로")
class CompanyUpdateServiceTest {

    @Mock CompanyRepository companyRepository;
    @Mock CompanyMemberRepository companyMemberRepository;
    @Mock CompanyChildWriter childWriter;
    @Mock CompanyLinkWriter linkWriter;
    @Mock CompanySearchDocumentWriter searchDocumentWriter;
    @Mock CompanyVerificationRepository verificationRepository;

    private static final long COMPANY_ID = 1L;
    private static final long OWNER_ID = 7L;

    private CompanyUpdateService service;

    @BeforeEach
    void setUp() {
        service = new CompanyUpdateService(companyRepository, companyMemberRepository,
                verificationRepository, childWriter, linkWriter, searchDocumentWriter);
    }

    // 소유자 경로: 회사 로드 + 멤버(소유) 확인을 스텁하고, 대상 Company 를 돌려준다.
    private Company givenOwnedCompany() {
        Company company = CompanyFixture.userCompany();
        given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
        given(companyMemberRepository.existsByCompanyIdAndUserId(COMPANY_ID, OWNER_ID)).willReturn(true);
        return company;
    }

    // 기본정보 PATCH 요청 — videoUrl / brandColor 만 관심.
    private CompanyUpdateRequest basicInfo(String videoUrl, String brandColor) {
        return new CompanyUpdateRequest(null, null, null, null, null, null, null, null, null, null, null, null, null,
                videoUrl, null, null, null, brandColor);
    }

    // 신원 필드(대표자명/개업일자)만 건드리는 수정 요청.
    private CompanyUpdateRequest identityInfo(String ceoName, LocalDate establishmentDate) {
        return new CompanyUpdateRequest(null, ceoName, establishmentDate, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("국세청 인증 회사의 신원 필드 보호")
    class VerifiedIdentityGuard {

        @Test
        @DisplayName("인증받은 회사의 대표자명 변경은 막는다 — 통과 후 값만 바꿔 배지를 유지하는 우회 차단")
        void verifiedCompany_cannotChangeCeoName() {
            Company company = givenOwnedCompany();
            company.markBusinessVerified(LocalDateTime.now());

            assertThatThrownBy(() -> service.updateBasicInfoByUser(COMPANY_ID, OWNER_ID, identityInfo("다른대표", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CompanyErrorCode.VERIFIED_FIELD_NOT_EDITABLE);

            verify(searchDocumentWriter, never()).write(any());
        }

        @Test
        @DisplayName("인증받은 회사의 개업일자 변경도 막는다 — 업력 뻥튀기 차단")
        void verifiedCompany_cannotChangeEstablishmentDate() {
            Company company = givenOwnedCompany();
            company.markBusinessVerified(LocalDateTime.now());

            assertThatThrownBy(() -> service.updateBasicInfoByUser(COMPANY_ID, OWNER_ID,
                    identityInfo(null, LocalDate.of(1990, 1, 1))))
                    .extracting("errorCode")
                    .isEqualTo(CompanyErrorCode.VERIFIED_FIELD_NOT_EDITABLE);
        }

        @Test
        @DisplayName("같은 값을 다시 보내는 건 변경이 아니므로 통과시킨다 (전체 폼 재전송 대응)")
        void verifiedCompany_sameValueIsNotAChange() {
            Company company = givenOwnedCompany();
            company.markBusinessVerified(LocalDateTime.now());

            assertThatCode(() -> service.updateBasicInfoByUser(COMPANY_ID, OWNER_ID,
                    identityInfo(company.getCeoName(), company.getEstablishmentDate())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("미인증 회사(관리자 등록 등)는 대표자명을 자유롭게 수정할 수 있다")
        void unverifiedCompany_canChangeCeoName() {
            Company company = givenOwnedCompany();

            service.updateBasicInfoByUser(COMPANY_ID, OWNER_ID, identityInfo("새대표", null));

            assertThat(company.getCeoName()).isEqualTo("새대표");
        }
    }

    @Nested
    @DisplayName("사업자 인증 회수 (관리자)")
    class RevokeBusinessVerification {

        @Test
        @DisplayName("회사 플래그를 내리고 인증 레코드를 사유와 함께 REVOKED 로 남긴다")
        void revoke_clearsFlagAndRecordsReason() {
            Company company = CompanyFixture.userCompany();
            company.markBusinessVerified(LocalDateTime.now());
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
            CompanyVerification verification = CompanyVerificationFixture.consumed(9L, OWNER_ID, COMPANY_ID);
            given(verificationRepository.findByCompanyId(COMPANY_ID)).willReturn(Optional.of(verification));

            service.revokeBusinessVerificationByAdmin(COMPANY_ID, "사칭 신고");

            assertThat(company.isBusinessVerified()).isFalse();
            assertThat(company.getBusinessVerifiedAt()).isNull();
            // 미인증으로 되돌리지 않고 REVOKED 로 남긴다 — 같은 번호의 즉시 재인증을 막는 근거가 된다.
            assertThat(verification.getStatus()).isEqualTo(VerificationStatus.REVOKED);
            assertThat(verification.getRevokedReason()).isEqualTo("사칭 신고");
        }

        @Test
        @DisplayName("사업자번호는 지우지 않는다 — 활성 유니크로 재등록을 계속 막고 분쟁 기록도 남겨야 한다")
        void revoke_keepsBusinessNumber() {
            Company company = CompanyFixture.userCompany();
            company.markBusinessVerified(LocalDateTime.now());
            String before = company.getBusinessNumber();
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
            given(verificationRepository.findByCompanyId(COMPANY_ID)).willReturn(Optional.empty());

            service.revokeBusinessVerificationByAdmin(COMPANY_ID, "사유");

            assertThat(company.getBusinessNumber()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("컬렉션 교체(카테고리)")
    class ReplaceCategories {

        @Test
        @DisplayName("소유자 경로: 교체를 위임하고 검색 도큐먼트를 재동기화한다")
        void owner_replacesAndResyncs() {
            Company company = givenOwnedCompany();

            service.replaceCategoriesByUser(COMPANY_ID, OWNER_ID, List.of(1L, 2L, 3L));

            verify(linkWriter).replaceCategories(company, List.of(1L, 2L, 3L));
            verify(searchDocumentWriter).write(COMPANY_ID);
        }

        @Test
        @DisplayName("관리자 경로: 소유 검증 없이 교체하고 재동기화한다")
        void admin_replacesAndResyncs() {
            Company company = CompanyFixture.userCompany();
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));

            service.replaceCategoriesByAdmin(COMPANY_ID, List.of(1L, 2L));

            verify(linkWriter).replaceCategories(company, List.of(1L, 2L));
            verify(searchDocumentWriter).write(COMPANY_ID);
        }
    }

    @Nested
    @DisplayName("기본정보 수정")
    class BasicInfo {

        @Test
        @DisplayName("videoUrl·brandColor 를 요청한 대로 저장하고 재동기화한다")
        void basicInfo_storedWithoutGradeGate() {
            Company company = givenOwnedCompany();

            service.updateBasicInfoByUser(COMPANY_ID, OWNER_ID, basicInfo("https://youtu.be/x", "#FF0000"));

            assertThat(company.getVideoUrl()).isEqualTo("https://youtu.be/x");
            assertThat(company.getBrandColor()).isEqualTo("#FF0000");
            verify(searchDocumentWriter).write(COMPANY_ID);
        }
    }

    @Test
    @DisplayName("소유자가 아니면 COMPANY_ACCESS_DENIED 로 막고 아무것도 바꾸지 않는다")
    void notOwner_accessDenied() {
        given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(CompanyFixture.userCompany()));
        given(companyMemberRepository.existsByCompanyIdAndUserId(COMPANY_ID, OWNER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.replaceCategoriesByUser(COMPANY_ID, OWNER_ID, List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CompanyErrorCode.COMPANY_ACCESS_DENIED);

        verify(searchDocumentWriter, never()).write(any());
    }
}
