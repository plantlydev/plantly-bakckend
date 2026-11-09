package project.plantly.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.plantly.domain.company.dto.CompanyReverificationRequest;
import project.plantly.domain.company.dto.CompanyReverificationResponse;
import project.plantly.domain.company.dto.CompanyVerificationRequest;
import project.plantly.domain.company.dto.CompanyVerificationResponse;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.policy.VerificationProperties;
import project.plantly.domain.company.support.BusinessNumbers;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDateTime;

/**
 * 회사 등록 전 사업자 인증(선행 인증).
 *
 * <p>클래스 레벨 @Transactional 이 없는 것은 의도다 — 이 메서드 한가운데에 국세청 HTTP 호출이 있고,
 * 그걸 트랜잭션으로 감싸면 외부 응답을 기다리는 동안 DB 커넥션을 점유한다. DB 작업은 전부
 * {@link CompanyVerificationWriter} 의 짧은 트랜잭션으로 위임한다.
 */
@Service
@RequiredArgsConstructor
public class CompanyVerificationService {

    // 유효기간·일일 한도는 설정값이다(기본 7일 / 5회). 두 값의 근거는 VerificationProperties 주석 참고.
    private final VerificationProperties properties;

    private final NtsClient ntsClient;
    private final CompanyVerificationWriter writer;

    public CompanyVerificationResponse verify(Long userId, CompanyVerificationRequest request) {
        // 국세청에 물어보기 전에 형식으로 거른다 — 오타로 쿼터를 태우지 않는다.
        String businessNumber = BusinessNumbers.normalize(request.businessNumber());
        if (businessNumber == null) {
            throw new BusinessException(CompanyErrorCode.BUSINESS_NUMBER_INVALID_FORMAT);
        }

        // 국세청을 부르기 전에 한도 안의 자리부터 잡는다 — 세는 것과 기록하는 것 사이에 국세청 대기가 끼면
        // 동시 요청이 한도를 넘긴다(근거는 CompanyVerificationWriter#reserveAttempt).
        LocalDateTime now = LocalDateTime.now();
        Long attemptId = writer.reserveAttempt(userId, businessNumber, request.ceoName(), request.businessStartDate(),
                now.toLocalDate().atStartOfDay(), properties.dailyAttemptLimit());

        NtsClient.Result result;
        try {
            result = ntsClient.verify(businessNumber, request.ceoName(), request.businessStartDate());
        } catch (NtsClient.UnavailableException e) {
            // 국세청 쪽 문제다. 감사 로그엔 남기되 재시도 제한은 소모시키지 않고(예약을 푼다), 사용자에게도
            // 입력 오류가 아니라 시스템 지연임을 알린다.
            writer.completeAttempt(attemptId, VerificationOutcome.UNAVAILABLE, null);
            throw new BusinessException(CompanyErrorCode.VERIFICATION_UNAVAILABLE);
        }

        if (!result.isValid()) {
            writer.completeAttempt(attemptId, result.outcome(), result.rawResponse());
            throw new BusinessException(toErrorCode(result.outcome()));
        }

        CompanyVerification verification;
        try {
            verification = writer.issue(userId, businessNumber, request.ceoName(),
                    request.businessStartDate(), now, properties.ttl());
        } catch (BusinessException e) {
            // 국세청 판정은 통과했지만 이미 활성 회사가 쓰는 번호다. 발급 트랜잭션은 롤백됐으므로 결말을 따로 채운다.
            writer.completeAttempt(attemptId, VerificationOutcome.DUPLICATE, result.rawResponse());
            throw e;
        }
        writer.completeAttempt(attemptId, VerificationOutcome.VALID, result.rawResponse());

        return CompanyVerificationResponse.from(verification);
    }

    /**
     * 발행 직전, 만료된 인증을 사용자 모르게 되살린다. 만료가 아니면 아무 일도 하지 않는다(멱등).
     *
     * <p><b>사용자에게 다시 받을 값이 없다</b>는 것이 이 경로의 핵심이다. 국세청에 물어볼 세 값
     * (사업자번호·대표자명·개업일자)은 이미 인증 레코드에 검증된 상태로 저장돼 있으므로, 저장값 그대로
     * 다시 질의하면 된다. 사용자 입력이 개입하지 않으니 재인증(reverify)과 같은 안전성을 갖는다 —
     * 타사 번호를 끼워 넣어 배지를 갈아끼울 자리가 없다.
     *
     * <p>그래서 정상 사용자는 만료라는 개념 자체를 만나지 않는다. 폼을 며칠 붙들고 있다가 저장을 눌러도
     * 저장이 될 뿐이다. 만료가 사용자에게 드러나는 것은 국세청이 실제로 "이제 이 사업자는 아니다" 라고
     * 답했을 때(휴업·폐업·정보 불일치)뿐이고, 그건 정말로 등록을 막아야 하는 상태다.
     *
     * <p>이 호출은 일일 시도 한도를 소모하지 않는다 — 사용자가 누른 적 없는 호출이기 때문이다
     * ({@code CompanyVerificationAttempt#automatic}).
     *
     * <p>등록 트랜잭션 <b>밖에서</b> 불려야 한다. 국세청 HTTP 호출이 끼어 있어 트랜잭션 안에서 부르면
     * 응답을 기다리는 내내 DB 커넥션을 붙잡는다. 그 경계는 {@link CompanyRegistrationService} 가 세운다.
     */
    public void refreshIfExpired(Long userId, Long verificationId) {
        if (verificationId == null) {
            // 본문 검증(@NotNull)이 먼저 걸러주지만, 이 경로가 검증 없는 호출부에서도 안전해야 한다.
            throw new BusinessException(CompanyErrorCode.VERIFICATION_NOT_FOUND);
        }

        CompanyVerification verification = writer.loadOwned(userId, verificationId);
        LocalDateTime now = LocalDateTime.now();
        if (!verification.needsRefresh(now)) {
            // 아직 유효하거나, 만료가 아닌 다른 사유(CONSUMED/REVOKED)다. 후자의 판단은 등록 경로가 한다 —
            // 여기서 같이 던지면 "만료 갱신" 이라는 이 메서드의 책임을 넘는다.
            return;
        }

        NtsClient.Result result;
        try {
            result = ntsClient.verify(verification.getBusinessNumber(), verification.getCeoName(),
                    verification.getBusinessStartDate());
        } catch (NtsClient.UnavailableException e) {
            // 국세청 장애다. 인증을 만료로 확정하지 않는다 — 사용자 잘못이 아니라서 잠시 뒤 다시 누르면
            // 그대로 통과해야 하고, 여기서 EXPIRED 로 찍으면 멀쩡한 인증이 장애 때문에 죽는다.
            writer.recordAutomaticAttempt(userId, verification.getBusinessNumber(), verification.getCeoName(),
                    verification.getBusinessStartDate(), VerificationOutcome.UNAVAILABLE, null);
            throw new BusinessException(CompanyErrorCode.VERIFICATION_UNAVAILABLE);
        }

        if (!result.isValid()) {
            // 국세청이 실제로 다른 답을 줬다(폐업·휴업·불일치). 이건 되살릴 수 없는 만료라 확정 기록하고
            // 판정별 안내를 그대로 내보낸다. 작성분은 사업자번호로 키잉된 초안에 그대로 남아 있다.
            writer.markExpired(userId, verificationId, result.outcome(), result.rawResponse());
            throw new BusinessException(toErrorCode(result.outcome()));
        }

        writer.refresh(userId, verificationId, now, properties.ttl(), result.rawResponse());
    }

    /**
     * 사업자 재인증. 이미 등록·인증된 회사의 대표자명·개업일자를 국세청에 다시 확인해 갱신한다.
     *
     * <p>선행 인증(verify)과 결정적으로 다른 점은 <b>사업자번호를 요청으로 받지 않는다는 것</b>이다. 번호는
     * DB 저장값(최초 인증본에서 온 불변값)을 그대로 국세청에 질의한다 — 재인증 때 번호를 새로 입력받으면
     * 타사 번호로 배지를 갈아끼울 수 있기 때문이다. 국세청 정보가 바뀌었을 수 있으므로 이전 값과 비교하지
     * 않고, 새 대표자명·개업일자가 통과하면 그대로 DB 를 덮어쓰고 인증 시각을 갱신한다.
     *
     * <p>트랜잭션 경계는 verify 와 동일하다 — 국세청 HTTP 호출을 트랜잭션 밖에 두고 DB 작업만 writer 의
     * 짧은 트랜잭션으로 위임한다. 일일 시도 한도·감사 로그도 선행 인증과 같은 집계(같은 예약)를 공유한다.
     */
    public CompanyReverificationResponse reverify(Long userId, Long companyId, CompanyReverificationRequest request) {
        // 사업자번호는 요청이 아니라 DB 저장값을 쓴다(탈취 방지). 소유·선행조건(businessVerified)도 여기서 확인한다.
        String businessNumber = writer.loadOwnedVerifiedBusinessNumber(companyId, userId);

        LocalDateTime now = LocalDateTime.now();
        Long attemptId = writer.reserveAttempt(userId, businessNumber, request.ceoName(), request.businessStartDate(),
                now.toLocalDate().atStartOfDay(), properties.dailyAttemptLimit());

        NtsClient.Result result;
        try {
            result = ntsClient.verify(businessNumber, request.ceoName(), request.businessStartDate());
        } catch (NtsClient.UnavailableException e) {
            writer.completeAttempt(attemptId, VerificationOutcome.UNAVAILABLE, null);
            throw new BusinessException(CompanyErrorCode.VERIFICATION_UNAVAILABLE);
        }

        if (!result.isValid()) {
            writer.completeAttempt(attemptId, result.outcome(), result.rawResponse());
            throw new BusinessException(toErrorCode(result.outcome()));
        }

        // 국세청은 통과했으므로 결말은 VALID 다. 기다리는 사이 상태가 바뀌어(인증 회수 등) 반영이 거부돼도 국세청은
        // 실제로 불렸으니 감사 로그와 일일 한도 집계에서 빠지면 안 된다 — 반영 성공·거부와 무관하게 채운다.
        LocalDateTime verifiedAt;
        try {
            verifiedAt = writer.applyReverification(userId, companyId,
                    request.ceoName(), request.businessStartDate(), now);
        } finally {
            writer.completeAttempt(attemptId, VerificationOutcome.VALID, result.rawResponse());
        }

        return CompanyReverificationResponse.of(businessNumber, request.ceoName(), request.businessStartDate(), verifiedAt);
    }

    // 판정별로 다른 안내를 준다. 사용자가 고쳐야 할 것이 각각 다르기 때문이다 —
    // 불일치는 입력을 고치면 되지만, 미등록·폐업은 고칠 수 있는 입력이 없다.
    private CompanyErrorCode toErrorCode(VerificationOutcome outcome) {
        return switch (outcome) {
            case MISMATCH -> CompanyErrorCode.VERIFICATION_MISMATCH;
            case NOT_REGISTERED -> CompanyErrorCode.VERIFICATION_NOT_REGISTERED;
            case SUSPENDED -> CompanyErrorCode.VERIFICATION_SUSPENDED;
            case CLOSED -> CompanyErrorCode.VERIFICATION_CLOSED;
            // NtsClient 는 이 넷을 반환하지 않는다(VALID 는 위에서 걸러지고, 나머지 셋은 서비스가 붙이는 값).
            case VALID, UNAVAILABLE, DUPLICATE, PENDING -> throw new IllegalStateException("국세청 판정이 아닌 값: " + outcome);
        };
    }
}
