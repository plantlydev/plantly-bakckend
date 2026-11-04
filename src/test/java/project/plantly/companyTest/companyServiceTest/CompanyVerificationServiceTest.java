package project.plantly.companyTest.companyServiceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import project.plantly.domain.company.policy.VerificationProperties;
import project.plantly.companyTest.support.CompanyVerificationFixture;
import project.plantly.domain.company.dto.CompanyReverificationRequest;
import project.plantly.domain.company.dto.CompanyReverificationResponse;
import project.plantly.domain.company.dto.CompanyVerificationRequest;
import project.plantly.domain.company.dto.CompanyVerificationResponse;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.domain.company.service.CompanyVerificationWriter;
import project.plantly.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// CompanyVerificationService 단위 테스트.
// 관심사는 "국세청 판정을 우리 도메인 결과로 어떻게 옮기는가" — 판정별 에러 매핑, 재시도 제한, 감사 로그 기록 여부.
// 국세청 응답 파싱 자체는 NtsRestClientTest 가, DB 접근은 Writer 가 담당한다.
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyVerificationService: 선행 사업자 인증")
class CompanyVerificationServiceTest {

    private static final Long USER_ID = 7L;

    @Mock NtsClient ntsClient;
    @Mock CompanyVerificationWriter writer;

    // 유효기간·일일 한도는 실물을 넣는다(기본 7일 / 5회). 설정 기본값이 바뀌면 이 테스트가 먼저 알려준다.
    @Spy VerificationProperties properties = new VerificationProperties(null, null);

    @InjectMocks CompanyVerificationService service;

    private final CompanyVerificationRequest request =
            new CompanyVerificationRequest("123-45-67890", "홍길동", LocalDate.of(2020, 1, 2));

    private void givenNtsReturns(VerificationOutcome outcome) {
        given(ntsClient.verify(any(), any(), any()))
                .willReturn(new NtsClient.Result(outcome, "{\"raw\":true}"));
    }

    @Test
    @DisplayName("국세청 판정을 통과하면 정규화된 사업자번호로 인증을 발급한다")
    void verify_success_issuesVerification() {
        givenNtsReturns(VerificationOutcome.VALID);
        given(writer.issue(eq(USER_ID), eq("1234567890"), eq("홍길동"), any(), any(), any(), any()))
                .willReturn(CompanyVerificationFixture.usable(99L, USER_ID));

        CompanyVerificationResponse response = service.verify(USER_ID, request);

        assertThat(response.verificationId()).isEqualTo(99L);
        // 하이픈이 제거된 형태로 국세청에 질의하고 저장한다 — 표기가 섞이면 활성 유니크가 중복을 못 막는다.
        verify(ntsClient).verify(eq("1234567890"), eq("홍길동"), eq(LocalDate.of(2020, 1, 2)));
    }

    @Test
    @DisplayName("사업자번호 형식이 틀리면 국세청을 호출하지 않고 거른다")
    void verify_invalidFormat_doesNotCallNts() {
        CompanyVerificationRequest malformed =
                new CompanyVerificationRequest("12345", "홍길동", LocalDate.of(2020, 1, 2));

        assertThatThrownBy(() -> service.verify(USER_ID, malformed))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.BUSINESS_NUMBER_INVALID_FORMAT);

        // 오타로 일일 쿼터를 태우지 않는다.
        verify(ntsClient, never()).verify(any(), any(), any());
    }

    @Nested
    @DisplayName("국세청 판정별 응답")
    class OutcomeMapping {

        @Test
        @DisplayName("진위 불일치는 400(MISMATCH)이며 시도를 감사 로그에 남긴다")
        void mismatch() {
            givenNtsReturns(VerificationOutcome.MISMATCH);

            assertThatThrownBy(() -> service.verify(USER_ID, request))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_MISMATCH);

            verify(writer).recordAttempt(eq(USER_ID), eq("1234567890"), eq("홍길동"), any(),
                    eq(VerificationOutcome.MISMATCH), any());
        }

        @Test
        @DisplayName("미등록 번호는 400(NOT_REGISTERED) — 입력을 고쳐도 소용없는 경우라 불일치와 구분한다")
        void notRegistered() {
            givenNtsReturns(VerificationOutcome.NOT_REGISTERED);

            assertThatThrownBy(() -> service.verify(USER_ID, request))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_NOT_REGISTERED);
        }

        @Test
        @DisplayName("폐업자는 인증을 통과하지 못한다 — 진위확인만으로는 못 거르는 케이스")
        void closed() {
            givenNtsReturns(VerificationOutcome.CLOSED);

            assertThatThrownBy(() -> service.verify(USER_ID, request))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_CLOSED);

            verify(writer, never()).issue(anyLong(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("휴업자도 인증을 통과하지 못한다")
        void suspended() {
            givenNtsReturns(VerificationOutcome.SUSPENDED);

            assertThatThrownBy(() -> service.verify(USER_ID, request))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_SUSPENDED);
        }
    }

    @Test
    @DisplayName("국세청 장애는 503 으로 안내하고, 사용자 잘못이 아니므로 UNAVAILABLE 로 기록한다")
    void verify_ntsDown_mapsToServiceUnavailable() {
        willThrow(new NtsClient.UnavailableException("timeout")).given(ntsClient).verify(any(), any(), any());

        assertThatThrownBy(() -> service.verify(USER_ID, request))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_UNAVAILABLE);

        // 재시도 제한 집계에서 제외되는 outcome 으로 남겨야 장애 시간에 하루치 기회를 잃지 않는다.
        verify(writer).recordAttempt(eq(USER_ID), any(), any(), any(),
                eq(VerificationOutcome.UNAVAILABLE), eq(null));
    }

    @Test
    @DisplayName("일일 시도 한도(5회)를 채웠으면 국세청을 호출하지 않고 429 를 반환한다")
    void verify_dailyLimitExceeded() {
        given(writer.countTodayAttempts(eq(USER_ID), any())).willReturn(5L);

        assertThatThrownBy(() -> service.verify(USER_ID, request))
                .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_LIMIT_EXCEEDED);

        verify(ntsClient, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("한도 미만이면 정상적으로 국세청을 호출한다 (경계값: 4회 사용)")
    void verify_underDailyLimit_proceeds() {
        given(writer.countTodayAttempts(eq(USER_ID), any())).willReturn(4L);
        givenNtsReturns(VerificationOutcome.VALID);
        given(writer.issue(anyLong(), any(), any(), any(), any(), any(), any()))
                .willReturn(CompanyVerificationFixture.usable(99L, USER_ID));

        service.verify(USER_ID, request);

        verify(ntsClient).verify(any(), any(), any());
    }

    // TTL 은 상수가 아니라 설정값이다(app.verification.ttl, 기본 7일). 짧게 잡아도 막히는 것이 거의 없고
    // 정상 사용자만 폼 작성 중 만료를 만나므로 며칠 단위로 둔다 — 근거는 VerificationProperties 주석 참고.
    @Test
    @DisplayName("발급 시 설정된 TTL(기본 7일)이 적용된다")
    void verify_appliesConfiguredTtl() {
        givenNtsReturns(VerificationOutcome.VALID);
        given(writer.issue(anyLong(), any(), any(), any(), any(), any(), eq(Duration.ofDays(7))))
                .willReturn(CompanyVerificationFixture.usable(99L, USER_ID));

        service.verify(USER_ID, request);

        verify(writer).issue(eq(USER_ID), eq("1234567890"), eq("홍길동"), eq(LocalDate.of(2020, 1, 2)),
                any(), any(LocalDateTime.class), eq(Duration.ofDays(7)));
    }

    // 재인증: 등록·인증된 회사의 대표자명·개업일자를 국세청에 다시 확인해 갱신한다.
    // 선행 인증과 결정적으로 다른 점은 "사업자번호를 요청으로 받지 않고 DB 저장값으로 국세청에 질의"하는 것.
    @Nested
    @DisplayName("재인증")
    class Reverify {

        private static final Long COMPANY_ID = 42L;
        private static final String STORED_BUSINESS_NUMBER = "1234567890";

        // 새로 입력받는 대표자명·개업일자(국세청 정보가 바뀐 상황을 가정). 사업자번호 자리는 요청에 아예 없다.
        private final CompanyReverificationRequest request =
                new CompanyReverificationRequest("김신임", LocalDate.of(2021, 3, 4));

        @Test
        @DisplayName("DB 저장 사업자번호로 국세청에 재질의하고, 통과하면 검증값을 갱신해 돌려준다")
        void reverify_success_updatesWithStoredBusinessNumber() {
            LocalDateTime reverifiedAt = LocalDateTime.of(2026, 7, 21, 10, 0);
            given(writer.loadOwnedVerifiedBusinessNumber(COMPANY_ID, USER_ID)).willReturn(STORED_BUSINESS_NUMBER);
            given(ntsClient.verify(any(), any(), any()))
                    .willReturn(new NtsClient.Result(VerificationOutcome.VALID, "{\"raw\":true}"));
            given(writer.applyReverification(eq(USER_ID), eq(COMPANY_ID), eq(STORED_BUSINESS_NUMBER),
                    eq("김신임"), eq(LocalDate.of(2021, 3, 4)), any(), any()))
                    .willReturn(reverifiedAt);

            CompanyReverificationResponse response = service.reverify(USER_ID, COMPANY_ID, request);

            // 사업자번호는 요청이 아니라 저장값에서 온다 — 탈취를 원천 차단하는 핵심 규칙.
            verify(ntsClient).verify(eq(STORED_BUSINESS_NUMBER), eq("김신임"), eq(LocalDate.of(2021, 3, 4)));
            assertThat(response.businessNumber()).isEqualTo(STORED_BUSINESS_NUMBER);
            assertThat(response.ceoName()).isEqualTo("김신임");
            assertThat(response.businessStartDate()).isEqualTo(LocalDate.of(2021, 3, 4));
            assertThat(response.verifiedAt()).isEqualTo(reverifiedAt);
        }

        @Test
        @DisplayName("미인증(소유 아님/인증 회수 등) 회사는 국세청을 호출하지 않고 선행조건 오류로 막는다")
        void reverify_notVerified_blockedBeforeNts() {
            given(writer.loadOwnedVerifiedBusinessNumber(COMPANY_ID, USER_ID))
                    .willThrow(new BusinessException(CompanyErrorCode.COMPANY_NOT_BUSINESS_VERIFIED));

            assertThatThrownBy(() -> service.reverify(USER_ID, COMPANY_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.COMPANY_NOT_BUSINESS_VERIFIED);

            verify(ntsClient, never()).verify(any(), any(), any());
            verify(writer, never()).applyReverification(anyLong(), anyLong(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("국세청 정보와 불일치하면 400(MISMATCH)이며 갱신하지 않고 시도만 감사 로그에 남긴다")
        void reverify_mismatch_recordsAttemptAndDoesNotUpdate() {
            given(writer.loadOwnedVerifiedBusinessNumber(COMPANY_ID, USER_ID)).willReturn(STORED_BUSINESS_NUMBER);
            given(ntsClient.verify(any(), any(), any()))
                    .willReturn(new NtsClient.Result(VerificationOutcome.MISMATCH, "{\"raw\":true}"));

            assertThatThrownBy(() -> service.reverify(USER_ID, COMPANY_ID, request))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_MISMATCH);

            verify(writer).recordAttempt(eq(USER_ID), eq(STORED_BUSINESS_NUMBER), eq("김신임"), any(),
                    eq(VerificationOutcome.MISMATCH), any());
            verify(writer, never()).applyReverification(anyLong(), anyLong(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("국세청 장애는 503 으로 안내하고 UNAVAILABLE 로 기록한다(재시도 한도 미소모)")
        void reverify_ntsDown_mapsToServiceUnavailable() {
            given(writer.loadOwnedVerifiedBusinessNumber(COMPANY_ID, USER_ID)).willReturn(STORED_BUSINESS_NUMBER);
            willThrow(new NtsClient.UnavailableException("timeout")).given(ntsClient).verify(any(), any(), any());

            assertThatThrownBy(() -> service.reverify(USER_ID, COMPANY_ID, request))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_UNAVAILABLE);

            verify(writer).recordAttempt(eq(USER_ID), any(), any(), any(),
                    eq(VerificationOutcome.UNAVAILABLE), eq(null));
        }

        @Test
        @DisplayName("일일 시도 한도(5회)를 채웠으면 국세청을 호출하지 않고 429 를 반환한다(선행 인증과 한도 공유)")
        void reverify_dailyLimitExceeded() {
            given(writer.loadOwnedVerifiedBusinessNumber(COMPANY_ID, USER_ID)).willReturn(STORED_BUSINESS_NUMBER);
            given(writer.countTodayAttempts(eq(USER_ID), any())).willReturn(5L);

            assertThatThrownBy(() -> service.reverify(USER_ID, COMPANY_ID, request))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_LIMIT_EXCEEDED);

            verify(ntsClient, never()).verify(any(), any(), any());
        }
    }

    // 발행 직전의 만료 인증 자동 갱신. 이 경로의 핵심은 "사용자에게 다시 받을 값이 없다" 는 것이라,
    // 정상 사용자는 만료를 인지하지 못한 채 저장이 성공해야 한다. 만료가 드러나야 하는 유일한 경우는
    // 국세청이 실제로 다른 답을 줬을 때(폐업·휴업·불일치)다.
    @Nested
    @DisplayName("refreshIfExpired (발행 직전 자동 재질의)")
    class RefreshIfExpired {

        private static final Long VERIFICATION_ID = 99L;

        @Test
        @DisplayName("아직 유효한 인증이면 국세청을 부르지 않고 아무것도 바꾸지 않는다 (멱등)")
        void usableVerification_isLeftAlone() {
            given(writer.loadOwned(USER_ID, VERIFICATION_ID))
                    .willReturn(CompanyVerificationFixture.usable(VERIFICATION_ID, USER_ID));

            service.refreshIfExpired(USER_ID, VERIFICATION_ID);

            verify(ntsClient, never()).verify(any(), any(), any());
            verify(writer, never()).refresh(any(), any(), any(), any(), any());
            verify(writer, never()).markExpired(any(), any(), any(), any());
        }

        // 이 테스트가 이 작업 전체의 목적이다: 며칠 걸려 폼을 쓴 사용자가 저장을 눌렀을 때
        // 만료 화면이 아니라 등록 성공을 봐야 한다.
        @Test
        @DisplayName("만료됐지만 국세청이 여전히 통과시키면 저장값 그대로 재질의해 조용히 되살린다")
        void expiredButStillValid_isRefreshedSilently() {
            given(writer.loadOwned(USER_ID, VERIFICATION_ID))
                    .willReturn(CompanyVerificationFixture.expired(VERIFICATION_ID, USER_ID));
            givenNtsReturns(VerificationOutcome.VALID);

            service.refreshIfExpired(USER_ID, VERIFICATION_ID);

            // 사용자 입력이 아니라 저장된 검증값 그대로 다시 묻는다 — 타사 번호를 끼워 넣을 자리가 없다.
            verify(ntsClient).verify(CompanyVerificationFixture.BUSINESS_NUMBER,
                    CompanyVerificationFixture.CEO_NAME, CompanyVerificationFixture.START_DATE);
            verify(writer).refresh(eq(USER_ID), eq(VERIFICATION_ID), any(), any(), any());
            verify(writer, never()).markExpired(any(), any(), any(), any());
        }

        @Test
        @DisplayName("국세청이 폐업으로 답하면 만료를 확정하고 판정별 안내(VERIFICATION_CLOSED)로 막는다")
        void expiredAndClosed_isRejectedAndMarked() {
            given(writer.loadOwned(USER_ID, VERIFICATION_ID))
                    .willReturn(CompanyVerificationFixture.expired(VERIFICATION_ID, USER_ID));
            givenNtsReturns(VerificationOutcome.CLOSED);

            assertThatThrownBy(() -> service.refreshIfExpired(USER_ID, VERIFICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_CLOSED);

            verify(writer).markExpired(eq(USER_ID), eq(VERIFICATION_ID), eq(VerificationOutcome.CLOSED), any());
            verify(writer, never()).refresh(any(), any(), any(), any(), any());
        }

        // 국세청 장애로 만료를 확정하면 멀쩡한 인증이 남의 사정 때문에 죽는다. 잠시 뒤 다시 누르면 통과해야 한다.
        @Test
        @DisplayName("국세청 장애면 만료를 확정하지 않고 503(VERIFICATION_UNAVAILABLE)만 돌려준다")
        void ntsUnavailable_doesNotMarkExpired() {
            given(writer.loadOwned(USER_ID, VERIFICATION_ID))
                    .willReturn(CompanyVerificationFixture.expired(VERIFICATION_ID, USER_ID));
            willThrow(new NtsClient.UnavailableException("점검"))
                    .given(ntsClient).verify(any(), any(), any());

            assertThatThrownBy(() -> service.refreshIfExpired(USER_ID, VERIFICATION_ID))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_UNAVAILABLE);

            verify(writer).recordAutomaticAttempt(eq(USER_ID), any(), any(), any(),
                    eq(VerificationOutcome.UNAVAILABLE), eq(null));
            verify(writer, never()).markExpired(any(), any(), any(), any());
            verify(writer, never()).refresh(any(), any(), any(), any(), any());
        }

        // 한도는 사용자가 누른 시도만 센다. 자동 재질의가 한도를 보면 "가만히 있었는데 등록이 막히는" 상태가 된다.
        @Test
        @DisplayName("일일 시도 한도와 무관하게 동작한다 — 한도를 채운 사용자도 발행은 된다")
        void ignoresDailyAttemptLimit() {
            given(writer.loadOwned(USER_ID, VERIFICATION_ID))
                    .willReturn(CompanyVerificationFixture.expired(VERIFICATION_ID, USER_ID));
            givenNtsReturns(VerificationOutcome.VALID);

            service.refreshIfExpired(USER_ID, VERIFICATION_ID);

            verify(writer, never()).countTodayAttempts(any(), any());
            verify(writer).refresh(eq(USER_ID), eq(VERIFICATION_ID), any(), any(), any());
        }

        @Test
        @DisplayName("인증 식별자가 없으면 국세청을 부르지 않고 400 (VERIFICATION_NOT_FOUND)")
        void nullVerificationId_isRejected() {
            assertThatThrownBy(() -> service.refreshIfExpired(USER_ID, null))
                    .hasFieldOrPropertyWithValue("errorCode", CompanyErrorCode.VERIFICATION_NOT_FOUND);

            verify(ntsClient, never()).verify(any(), any(), any());
        }
    }
}
