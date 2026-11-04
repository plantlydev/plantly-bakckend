package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.plantly.companyTest.support.CompanyCreateRequestBuilder;
import project.plantly.companyTest.support.CompanyVerificationFixture;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.MemberRole;
import project.plantly.domain.company.enums.VerificationStatus;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.policy.CompanyPolicyView;
import project.plantly.domain.company.policy.CompanyRegistrationPolicy;
import project.plantly.domain.company.repository.CompanyDraftRepository;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyChildWriter;
import project.plantly.domain.company.service.CompanyLinkWriter;
import project.plantly.domain.company.service.CompanyService;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.exception.ErrorCode;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// CompanyService 단위 테스트: 정책별 outcome 이 아니라 "오케스트레이션/구조"를 검증한다.
//  - 정책은 mock 으로 두고 "전부 호출되는가" 만 본다. (한도 판정은 정책 단위 테스트 담당)
//  - 등록 경로별 차이(OWNER 멤버 생성, 인증 소비)를 못 박는다.
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyService: 회사 등록 오케스트레이션")
class CompanyServiceTest {

    @Mock CompanyRepository companyRepository;
    @Mock CompanyChildWriter childWriter;
    @Mock CompanyLinkWriter linkWriter;
    @Mock CompanyMemberRepository companyMemberRepository;
    @Mock CompanySearchDocumentWriter searchDocumentWriter;
    @Mock CompanyVerificationRepository verificationRepository;
    @Mock CompanyDraftRepository draftRepository;

    private static final Long USER_ID = 7L;
    private static final Long VERIFICATION_ID = 99L;

    // 관리자 등록 경로용(신원 3종 포함) / 자가등록 경로용(verificationId 참조) 요청을 같은 빌더에서 뽑는다.
    private final CompanyCreateRequest request = CompanyCreateRequestBuilder.aRequest().build();
    private final MyCompanyCreateRequest myRequest = CompanyCreateRequestBuilder.aRequest().buildMy(VERIFICATION_ID);

    // 정책 리스트는 테스트마다 다르므로 생성자 직접 호출로 주입한다. (Mockito 가 List<인터페이스> mock 을 자동 주입하지 못함)
    private CompanyService service(CompanyRegistrationPolicy... policies) {
        return new CompanyService(companyRepository, childWriter, linkWriter, companyMemberRepository,
                verificationRepository, draftRepository, searchDocumentWriter, List.of(policies));
    }

    // 자가등록은 선행 인증을 소비하므로, 사용 가능한 인증이 조회된다고 가정한다.
    private void givenUsableVerification() {
        given(verificationRepository.findByIdAndUserId(VERIFICATION_ID, USER_ID))
                .willReturn(Optional.of(CompanyVerificationFixture.usable(VERIFICATION_ID, USER_ID)));
    }

    // companyRepository.save 가 INSERT 후 id 를 채우는 것을 흉내낸다. (persist 가 직후 company.getId() 를 읽음)
    private void givenSaveAssignsId(long id) {
        given(companyRepository.save(any(Company.class))).willAnswer(inv -> {
            Company c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", id);
            return c;
        });
    }

    @Test
    @DisplayName("유저 자가등록: 본체/자식/링크 저장 후 등록자를 OWNER 멤버로 기록하고 회사 id 를 반환한다")
    void createByUser_savesCompanyAndOwnerMember() {
        givenSaveAssignsId(10L);
        givenUsableVerification();
        CompanyService service = service();

        Long companyId = service.createByUser(USER_ID, myRequest);

        assertThat(companyId).isEqualTo(10L);
        verify(companyRepository).save(any(Company.class));
        // 자가등록은 인증본을 합쳐 만든 새 CompanyCreateRequest 로 부속을 쓴다(요청 객체 동일성 비교 불가).
        verify(childWriter).write(any(Company.class), any(CompanyCreateRequest.class));
        verify(linkWriter).write(any(Company.class), any(CompanyCreateRequest.class));
        verify(searchDocumentWriter).write(10L);

        ArgumentCaptor<CompanyMember> memberCaptor = ArgumentCaptor.forClass(CompanyMember.class);
        verify(companyMemberRepository).save(memberCaptor.capture());
        CompanyMember member = memberCaptor.getValue();
        assertThat(member.getCompanyId()).isEqualTo(10L);
        assertThat(member.getUserId()).isEqualTo(7L);
        assertThat(member.getRole()).isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("유저 자가등록: 발행이 성공하면 소임을 다한 임시저장 초안을 (userId, 사업자번호) 로 삭제한다")
    void createByUser_deletesDraftOnPublish() {
        givenSaveAssignsId(10L);
        givenUsableVerification();

        service().createByUser(USER_ID, myRequest);

        // 발행 성공 후 같은 트랜잭션에서 초안을 제거한다(초안 없이 등록했다면 멱등하게 아무 일도 안 함).
        // 초안은 인증 레코드가 아니라 사업자번호로 키잉된다 — 인증이 중간에 만료·재발급됐어도
        // 발행에 실제로 쓴 번호의 초안이 지워져야 한다(CompanyDraft 주석).
        verify(draftRepository).deleteByUserIdAndBusinessNumber(
                USER_ID, CompanyVerificationFixture.BUSINESS_NUMBER);
    }

    @Test
    @DisplayName("유저 자가등록: 주입된 모든 정책을 실행한다")
    void createByUser_appliesAllPolicies() {
        givenSaveAssignsId(10L);
        CompanyRegistrationPolicy policyA = mock(CompanyRegistrationPolicy.class);
        CompanyRegistrationPolicy policyB = mock(CompanyRegistrationPolicy.class);
        givenUsableVerification();
        CompanyService service = service(policyA, policyB);

        service.createByUser(USER_ID, myRequest);

        verify(policyA).apply(any(CompanyPolicyView.class));
        verify(policyB).apply(any(CompanyPolicyView.class));
    }

    @Test
    @DisplayName("관리자 등록: 본체/자식/링크는 저장하지만 멤버는 만들지 않으며, 정책은 자가등록과 똑같이 실행한다")
    void createByAdmin_savesCompanyButNoMember() {
        givenSaveAssignsId(20L);
        CompanyRegistrationPolicy policy = mock(CompanyRegistrationPolicy.class);
        CompanyService service = service(policy);

        Long companyId = service.createByAdmin(3L, request);

        assertThat(companyId).isEqualTo(20L);
        verify(companyRepository).save(any(Company.class));
        verify(childWriter).write(any(Company.class), eq(request));
        verify(linkWriter).write(any(Company.class), eq(request));
        verify(searchDocumentWriter).write(20L);
        verify(companyMemberRepository, never()).save(any());

        verify(policy).apply(any(CompanyPolicyView.class));
    }

    @Test
    @DisplayName("정책이 위반을 던지면 저장 이전이라 아무것도 영속화되지 않는다")
    void createByUser_policyThrows_nothingPersisted() {
        CompanyRegistrationPolicy failing = mock(CompanyRegistrationPolicy.class);
        org.mockito.BDDMockito.willThrow(new BusinessException(TestError.FAIL))
                .given(failing).apply(any(CompanyPolicyView.class));
        givenUsableVerification();
        CompanyService service = service(failing);

        assertThatThrownBy(() -> service.createByUser(USER_ID, myRequest))
                .isInstanceOf(BusinessException.class);

        verify(companyRepository, never()).save(any());
        verify(childWriter, never()).write(any(), any());
        verify(linkWriter, never()).write(any(), any());
        verify(searchDocumentWriter, never()).write(any());
        verify(companyMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("자가등록은 인증본에서 사업자번호·대표자명·개업일자를 채우고 인증을 소비 처리한다")
    void createByUser_consumesVerificationAndCopiesIdentity() {
        givenSaveAssignsId(10L);
        CompanyVerification verification = CompanyVerificationFixture.usable(VERIFICATION_ID, USER_ID);
        given(verificationRepository.findByIdAndUserId(VERIFICATION_ID, USER_ID))
                .willReturn(Optional.of(verification));

        service().createByUser(USER_ID, myRequest);

        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(companyCaptor.capture());
        Company saved = companyCaptor.getValue();
        // 요청 본문엔 이 세 값의 자리가 없다 — 오직 인증본에서만 온다.
        assertThat(saved.getBusinessNumber()).isEqualTo(CompanyVerificationFixture.BUSINESS_NUMBER);
        assertThat(saved.getCeoName()).isEqualTo(CompanyVerificationFixture.CEO_NAME);
        assertThat(saved.getEstablishmentDate()).isEqualTo(CompanyVerificationFixture.START_DATE);
        assertThat(saved.isBusinessVerified()).isTrue();

        // 소비된 인증은 재사용할 수 없어야 한다 (같은 인증으로 여러 회사 생성 차단).
        assertThat(verification.getStatus()).isEqualTo(VerificationStatus.CONSUMED);
        assertThat(verification.getCompanyId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("남의 인증 식별자로는 등록할 수 없다 (userId 를 조회 조건에 함께 건다)")
    void createByUser_foreignVerification_rejected() {
        given(verificationRepository.findByIdAndUserId(VERIFICATION_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().createByUser(USER_ID, myRequest))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_NOT_FOUND);

        verify(companyRepository, never()).save(any());
    }

    @Test
    @DisplayName("만료된 인증으로는 등록할 수 없고, 만료 상태가 기록된다")
    void createByUser_expiredVerification_rejected() {
        CompanyVerification expired = CompanyVerificationFixture.expired(VERIFICATION_ID, USER_ID);
        given(verificationRepository.findByIdAndUserId(VERIFICATION_ID, USER_ID)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> service().createByUser(USER_ID, myRequest))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_EXPIRED);

        assertThat(expired.getStatus()).isEqualTo(VerificationStatus.EXPIRED);
        verify(companyRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 다른 회사에 쓴 인증은 재사용할 수 없다")
    void createByUser_consumedVerification_rejected() {
        given(verificationRepository.findByIdAndUserId(VERIFICATION_ID, USER_ID))
                .willReturn(Optional.of(CompanyVerificationFixture.consumed(VERIFICATION_ID, USER_ID, 55L)));

        assertThatThrownBy(() -> service().createByUser(USER_ID, myRequest))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_ALREADY_USED);

        verify(companyRepository, never()).save(any());
    }

    @Test
    @DisplayName("동시 등록으로 유니크 인덱스가 터지면 500 이 아니라 409(BUSINESS_NUMBER_TAKEN) 로 변환한다")
    void createByUser_concurrentDuplicate_mapsToBusinessNumberTaken() {
        givenUsableVerification();
        // 사전 검사는 통과했지만(아직 상대 트랜잭션이 커밋 전) flush 시점에 인덱스가 위반되는 상황.
        given(companyRepository.save(any(Company.class))).willAnswer(inv -> inv.getArgument(0));
        org.mockito.BDDMockito.willThrow(new org.springframework.dao.DataIntegrityViolationException("unique violation"))
                .given(companyRepository).flush();

        assertThatThrownBy(() -> service().createByUser(USER_ID, myRequest))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.BUSINESS_NUMBER_TAKEN);

        verify(childWriter, never()).write(any(), any());
        verify(companyMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("관리자 등록은 인증을 거치지 않으므로 미인증 상태로 생성된다")
    void createByAdmin_isNotBusinessVerified() {
        givenSaveAssignsId(20L);

        service().createByAdmin(3L, request);

        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(companyCaptor.capture());
        assertThat(companyCaptor.getValue().isBusinessVerified()).isFalse();
    }

    // 정책 실패 전파 검증용 임의 에러코드. (실제 정책의 구체 코드는 정책 단위 테스트가 검증)
    private enum TestError implements ErrorCode {
        FAIL;

        @Override
        public org.springframework.http.HttpStatus getStatus() {
            return org.springframework.http.HttpStatus.BAD_REQUEST;
        }

        @Override
        public String getMessage() {
            return "정책 위반";
        }
    }
}
