package project.plantly.companyTest.companyServiceTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.plantly.companyTest.support.CompanyCreateRequestBuilder;
import project.plantly.domain.company.dto.CompanyDraftResponse;
import project.plantly.domain.company.dto.CompanyDraftSummary;
import project.plantly.domain.company.dto.CompanyVerificationResponse;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.enums.VerificationStatus;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.entity.CompanyDraft;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyDraftRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.service.CompanyDraftService;
import project.plantly.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// CompanyDraftService 단위 테스트: 초안 저장/조회/폐기의 "분기와 방어"를 못 박는다.
//  - 리포지토리는 mock, ObjectMapper 는 진짜를 주입한다 — 직렬화가 실제로 돌아 저장 payload 를 검증할 수 있고,
//    조회는 저장 문자열을 그대로 역직렬화하는 왕복까지 확인된다(record equals 로 동치 비교).
//  - upsert(신규 save vs 기존 updatePayload), CONSUMED 저장 거절, 매 요청 소유권 검사(크로스키 방어),
//    404/409 에러코드 매핑을 확인한다. (초안은 저장 시 값 검증을 하지 않는 "느슨한 저장"이라 그 경계도 함께 못 박는다.)
//  - 키가 사업자번호라는 사실에서 오는 두 가지도 여기서 고정한다: 하이픈 표기 정규화와, 형식이 아닌 값의 조기 거절.
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyDraftService: 임시저장 초안")
class CompanyDraftServiceTest {

    @Mock CompanyDraftRepository draftRepository;
    @Mock CompanyVerificationRepository verificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CompanyDraftService service;

    private static final Long USER_ID = 7L;
    private static final Long VERIFICATION_ID = 99L;

    // 초안의 키. 저장은 정규화된(하이픈 없는) 형태로만 이뤄진다.
    private static final String BUSINESS_NUMBER = "1234567890";
    private static final String HYPHENATED = "123-45-67890";

    // 관심 필드만 채운 자가등록 폼(부분 입력). 초안 payload 로 직렬화되는 원본이다.
    private final MyCompanyCreateRequest payload = CompanyCreateRequestBuilder.aRequest()
            .videoUrl("https://youtu.be/demo")
            .brandColor("#123456")
            .categoryIds(List.of(1L, 2L))
            .buildMy(VERIFICATION_ID);

    @BeforeEach
    void setUp() {
        service = new CompanyDraftService(draftRepository, verificationRepository, objectMapper);
    }

    // 이 사용자가 이 번호로 국세청을 통과한 이력이 있다고 가정한다(초안 접근 권한의 근거).
    private void givenVerifiedBusinessNumber() {
        given(verificationRepository.existsByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER)).willReturn(true);
    }

    // 이 번호로 만든 회사가 없거나(미소비) 삭제된 상태.
    private void givenNotConsumed() {
        given(verificationRepository.existsConsumedByLiveCompany(USER_ID, BUSINESS_NUMBER)).willReturn(false);
    }

    private void givenDraftStored() {
        given(draftRepository.findByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                .willReturn(Optional.of(CompanyDraft.create(USER_ID, BUSINESS_NUMBER, serialized(payload))));
    }

    private String serialized(MyCompanyCreateRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("save (자동저장 upsert)")
    class Save {

        @Test
        @DisplayName("초안이 없으면 새 CompanyDraft 를 userId·사업자번호·직렬화 payload 로 저장한다")
        void newDraft_isCreated() {
            givenVerifiedBusinessNumber();
            givenNotConsumed();
            given(draftRepository.findByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(Optional.empty());

            service.save(USER_ID, BUSINESS_NUMBER, payload);

            ArgumentCaptor<CompanyDraft> captor = ArgumentCaptor.forClass(CompanyDraft.class);
            verify(draftRepository).save(captor.capture());
            CompanyDraft saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getBusinessNumber()).isEqualTo(BUSINESS_NUMBER);
            assertThat(saved.getPayload()).isEqualTo(serialized(payload));
        }

        @Test
        @DisplayName("초안이 이미 있으면 새로 저장하지 않고 기존 payload 를 통째 교체한다 (사업자번호당 1행 유지)")
        void existingDraft_isReplacedInPlace() {
            givenVerifiedBusinessNumber();
            givenNotConsumed();
            CompanyDraft existing = CompanyDraft.create(USER_ID, BUSINESS_NUMBER, "{}");
            given(draftRepository.findByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(Optional.of(existing));

            service.save(USER_ID, BUSINESS_NUMBER, payload);

            assertThat(existing.getPayload()).isEqualTo(serialized(payload));
            verify(draftRepository, never()).save(any());
        }

        // 표기가 섞이면 같은 사업자의 초안이 두 행으로 갈린다. 경로에 무엇이 오든 저장 키는 하나여야 한다.
        @Test
        @DisplayName("하이픈이 섞인 사업자번호도 정규화한 같은 키로 저장한다")
        void hyphenatedNumber_isNormalized() {
            givenVerifiedBusinessNumber();
            givenNotConsumed();
            given(draftRepository.findByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(Optional.empty());

            service.save(USER_ID, HYPHENATED, payload);

            ArgumentCaptor<CompanyDraft> captor = ArgumentCaptor.forClass(CompanyDraft.class);
            verify(draftRepository).save(captor.capture());
            assertThat(captor.getValue().getBusinessNumber()).isEqualTo(BUSINESS_NUMBER);
        }

        @Test
        @DisplayName("이 번호로 만든 회사가 살아 있으면 저장을 거절한다 (409 VERIFICATION_ALREADY_USED)")
        void consumedVerification_isRejected() {
            givenVerifiedBusinessNumber();
            given(verificationRepository.existsConsumedByLiveCompany(USER_ID, BUSINESS_NUMBER)).willReturn(true);

            assertThatThrownBy(() -> service.save(USER_ID, BUSINESS_NUMBER, payload))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_ALREADY_USED);

            verify(draftRepository, never()).findByUserIdAndBusinessNumber(any(), anyString());
            verify(draftRepository, never()).save(any());
        }

        @Test
        @DisplayName("국세청을 통과한 적 없는 사업자번호로는 저장할 수 없다 (400 VERIFICATION_NOT_FOUND)")
        void unverifiedBusinessNumber_isRejected() {
            given(verificationRepository.existsByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(false);

            assertThatThrownBy(() -> service.save(USER_ID, BUSINESS_NUMBER, payload))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_NOT_FOUND);

            verify(draftRepository, never()).save(any());
        }

        // 형식이 아닌 값은 조회하기 전에 끊는다 — 경로가 문자열이라 아무 값이나 들어올 수 있다.
        @Test
        @DisplayName("사업자번호 형식이 아니면 조회 전에 400 (BUSINESS_NUMBER_INVALID_FORMAT)")
        void malformedNumber_isRejectedBeforeLookup() {
            assertThatThrownBy(() -> service.save(USER_ID, "not-a-number", payload))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.BUSINESS_NUMBER_INVALID_FORMAT);

            verify(verificationRepository, never()).existsByUserIdAndBusinessNumber(any(), anyString());
            verify(draftRepository, never()).save(any());
        }

        // 계약 경계: 저장은 값 검증을 하지 않는다. 인증이 만료됐든 재발급됐든 초안은 계속 저장된다 —
        // 초안의 수명이 인증의 수명에 묶이지 않는다는 것이 사업자번호 키잉의 핵심이다.
        @Test
        @DisplayName("인증이 만료돼 있어도 저장은 허용된다 (막는 것은 CONSUMED 뿐 — 느슨한 저장)")
        void expiredVerification_isStillAllowed() {
            givenVerifiedBusinessNumber();
            givenNotConsumed();
            given(draftRepository.findByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(Optional.empty());

            service.save(USER_ID, BUSINESS_NUMBER, payload);

            verify(draftRepository).save(any(CompanyDraft.class));
        }
    }

    @Nested
    @DisplayName("get (재진입 폼 복원)")
    class Get {

        @Test
        @DisplayName("저장된 초안을 역직렬화한 payload 와 마지막 저장 시각을 돌려준다 (직렬화 왕복)")
        void returnsDeserializedPayloadAndTimestamp() {
            givenVerifiedBusinessNumber();
            LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 23, 10, 0);
            CompanyDraft draft = CompanyDraft.create(USER_ID, BUSINESS_NUMBER, serialized(payload));
            ReflectionTestUtils.setField(draft, "updatedAt", updatedAt); // @UpdateTimestamp 는 영속 시 채워지므로 심어준다
            given(draftRepository.findByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(Optional.of(draft));

            CompanyDraftResponse response = service.get(USER_ID, BUSINESS_NUMBER);

            assertThat(response.payload()).isEqualTo(payload); // record equals: 저장→복원 왕복이 원본과 동일
            assertThat(response.updatedAt()).isEqualTo(updatedAt);
        }

        @Test
        @DisplayName("이어쓸 인증(신원 3종·초기 등급·한도)을 함께 실어 재인증 없이 폼을 그리게 한다")
        void includesResumableVerification() {
            givenVerifiedBusinessNumber();
            givenDraftStored();
            CompanyVerification verification = CompanyVerification.issue(USER_ID, BUSINESS_NUMBER, "홍길동",
                    LocalDate.of(2018, 3, 2), LocalDateTime.of(2026, 7, 20, 9, 0), Duration.ofDays(7));
            ReflectionTestUtils.setField(verification, "id", 123L);
            given(verificationRepository.findFirstByUserIdAndBusinessNumberAndStatusInOrderByIdDesc(
                    USER_ID, BUSINESS_NUMBER, EnumSet.of(VerificationStatus.VERIFIED, VerificationStatus.EXPIRED)))
                    .willReturn(Optional.of(verification));

            CompanyVerificationResponse resumed = service.get(USER_ID, BUSINESS_NUMBER).verification();

            // payload 안의 낡은 verificationId(99)가 아니라 지금 쓸 인증(123)이 실린다
            assertThat(resumed.verificationId()).isEqualTo(123L);
            assertThat(resumed.ceoName()).isEqualTo("홍길동");
            assertThat(resumed.businessStartDate()).isEqualTo(LocalDate.of(2018, 3, 2));
        }

        @Test
        @DisplayName("이어쓸 인증이 없으면(전부 소비·회수) verification 은 null — 초안 자체는 돌려준다")
        void noResumableVerification_isNull() {
            givenVerifiedBusinessNumber();
            givenDraftStored();
            given(verificationRepository.findFirstByUserIdAndBusinessNumberAndStatusInOrderByIdDesc(
                    any(), anyString(), any())).willReturn(Optional.empty());

            CompanyDraftResponse response = service.get(USER_ID, BUSINESS_NUMBER);

            assertThat(response.payload()).isEqualTo(payload);
            assertThat(response.verification()).isNull();
        }

        @Test
        @DisplayName("저장된 초안이 없으면 404 (DRAFT_NOT_FOUND)")
        void noDraft_isNotFound() {
            givenVerifiedBusinessNumber();
            given(draftRepository.findByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(USER_ID, BUSINESS_NUMBER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.DRAFT_NOT_FOUND);
        }

        @Test
        @DisplayName("남의 사업자번호로는 조회할 수 없다 — 인증 이력 검사가 초안 조회보다 먼저 걸린다")
        void foreignBusinessNumber_isRejectedBeforeDraftLookup() {
            given(verificationRepository.existsByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(false);

            assertThatThrownBy(() -> service.get(USER_ID, BUSINESS_NUMBER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_NOT_FOUND);

            verify(draftRepository, never()).findByUserIdAndBusinessNumber(any(), anyString());
        }
    }

    @Nested
    @DisplayName("list (내 초안 목록)")
    class ListDrafts {

        @Test
        @DisplayName("최근 저장순 그대로 사업자번호·payload 의 회사명·저장 시각을 돌려준다 — 인증 이력은 묻지 않는다")
        void returnsSummariesInRepositoryOrder() {
            CompanyDraft recent = CompanyDraft.create(USER_ID, BUSINESS_NUMBER, serialized(payload));
            ReflectionTestUtils.setField(recent, "updatedAt", LocalDateTime.of(2026, 7, 23, 10, 0));
            CompanyDraft older = CompanyDraft.create(USER_ID, "2222222222", "{\"companyName\":\"  이전회사  \"}");
            given(draftRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID)).willReturn(List.of(recent, older));

            List<CompanyDraftSummary> result = service.list(USER_ID);

            assertThat(result).extracting(CompanyDraftSummary::businessNumber)
                    .containsExactly(BUSINESS_NUMBER, "2222222222");
            assertThat(result.get(0).companyName()).isEqualTo(payload.companyName());
            assertThat(result.get(0).updatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 23, 10, 0));
            assertThat(result.get(1).companyName()).isEqualTo("이전회사"); // 앞뒤 공백 제거
            // 조회가 userId 로 스코프되므로 번호마다 인증 이력을 확인하지 않는다.
            verify(verificationRepository, never()).existsByUserIdAndBusinessNumber(any(), anyString());
        }

        @Test
        @DisplayName("회사명을 아직 안 썼거나 비었거나 payload 가 깨졌으면 이름만 null — 목록 전체는 실패하지 않는다")
        void missingOrUnreadableName_isNull() {
            given(draftRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID)).willReturn(List.of(
                    CompanyDraft.create(USER_ID, "1111111111", "{\"introTitle\":\"요약만\"}"),
                    CompanyDraft.create(USER_ID, "2222222222", "{\"companyName\":\"   \"}"),
                    CompanyDraft.create(USER_ID, "3333333333", "{깨진 json")));

            assertThat(service.list(USER_ID))
                    .hasSize(3)
                    .extracting(CompanyDraftSummary::companyName)
                    .containsOnlyNulls();
        }
    }

    @Nested
    @DisplayName("delete (수동 폐기)")
    class Delete {

        @Test
        @DisplayName("인증 이력이 있는 사업자번호면 초안을 삭제한다")
        void verifiedBusinessNumber_deletesDraft() {
            givenVerifiedBusinessNumber();

            service.delete(USER_ID, BUSINESS_NUMBER);

            verify(draftRepository).deleteByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER);
        }

        @Test
        @DisplayName("남의 사업자번호로는 폐기할 수 없고, 삭제도 시도하지 않는다 (400 VERIFICATION_NOT_FOUND)")
        void foreignBusinessNumber_isRejected() {
            given(verificationRepository.existsByUserIdAndBusinessNumber(USER_ID, BUSINESS_NUMBER))
                    .willReturn(false);

            assertThatThrownBy(() -> service.delete(USER_ID, BUSINESS_NUMBER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_NOT_FOUND);

            verify(draftRepository, never()).deleteByUserIdAndBusinessNumber(any(), anyString());
        }
    }
}
