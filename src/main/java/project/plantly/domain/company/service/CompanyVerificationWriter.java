package project.plantly.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.CompanyVerificationAttempt;
import project.plantly.domain.company.enums.VerificationOutcome;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationAttemptRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 인증 흐름의 DB 접근만 담당한다.
 *
 * <p>{@link CompanyVerificationService} 에서 분리한 이유는 트랜잭션 경계 때문이다. 국세청 호출은
 * 수백 ms~수 초가 걸리는데, 그걸 트랜잭션 안에서 하면 응답을 기다리는 내내 DB 커넥션을 붙잡는다.
 * 국세청이 느려지는 순간 커넥션 풀이 말라 서비스 전체가 멈춘다. 그래서 오케스트레이션(서비스)은
 * 트랜잭션 없이 두고, 짧은 DB 작업만 이 컴포넌트의 메서드 단위 트랜잭션으로 감싼다.
 *
 * <p>(같은 클래스 안에 두고 @Transactional 을 붙이면 자기 호출이라 프록시를 타지 않아 트랜잭션이
 * 아예 걸리지 않는다 — 별도 빈으로 나와야 하는 실질적인 이유이기도 하다.)
 */
@Component
@RequiredArgsConstructor
public class CompanyVerificationWriter {

    private final CompanyVerificationRepository verificationRepository;
    private final CompanyVerificationAttemptRepository attemptRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    // 재인증은 회사 본체(대표자명·개업일자)까지 갱신하므로 소유 검증과 검색 재색인이 필요하다.
    // 이 컴포넌트가 인증 흐름의 DB 접근을 전담하는 자리라 여기서 함께 다룬다.
    private final CompanyMemberRepository companyMemberRepository;
    private final CompanySearchDocumentWriter searchDocumentWriter;

    /**
     * 사용자 시도 1건의 자리를 일일 한도 안에서 예약한다. 국세청을 부르기 <b>전에</b> 불러야 한다.
     *
     * <p><b>왜 예약인가:</b> 예전엔 "오늘 시도 수를 센다 → 국세청을 부른다 → 결과를 기록한다" 였다. 세는 시점과 기록하는
     * 시점 사이에 국세청 대기가 끼어 있어, 같은 사용자의 동시 요청이 모두 "아직 0회" 를 보고 한도를 넘겨 국세청을 불렀다.
     * 그래서 기록을 앞으로 당긴다 — 결말이 나기 전에 {@code PENDING} 행부터 세워 두면 뒤따르는 요청이 그 행까지 센다.
     *
     * <p><b>왜 사용자 행 락인가:</b> 예약만으로는 부족하다. 두 요청이 동시에 세면 서로의 커밋 전 INSERT 를 못 보고 둘 다
     * 4회로 통과한다. 그래서 세기 전에 사용자 행을 잠가 같은 사용자의 예약을 줄 세운다. 락은 이 짧은 트랜잭션 동안만
     * 쥐고, 국세청 호출은 락·트랜잭션 밖에서 한다(커넥션 풀 보호, {@link CompanyVerificationService} 참고).
     * 한도 집계에서 빠지는 두 종류(국세청 장애·자동 재질의)는 {@link CompanyVerificationAttemptRepository} 참고.
     *
     * @return 예약된 시도의 id. 결말이 나면 {@link #completeAttempt} 로 채운다.
     * @throws BusinessException 오늘 한도를 이미 다 썼으면 {@code VERIFICATION_LIMIT_EXCEEDED}(아무것도 남기지 않는다)
     */
    @Transactional
    public Long reserveAttempt(Long userId, String businessNumber, String ceoName, LocalDate businessStartDate,
                               LocalDateTime startOfDay, int dailyLimit) {
        // 인증된 요청의 사용자가 없다면 데이터 정합성이 깨진 것이다. 조용히 락 없이 넘어가면 직렬화가 소리 없이 풀리므로 막는다.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 사용자 행이 없다: userId=" + userId));

        long used = attemptRepository.countByUserIdAndCreatedAtAfterAndOutcomeNotAndAutomaticFalse(
                userId, startOfDay, VerificationOutcome.UNAVAILABLE);
        if (used >= dailyLimit) {
            throw new BusinessException(CompanyErrorCode.VERIFICATION_LIMIT_EXCEEDED);
        }
        return attemptRepository.save(
                CompanyVerificationAttempt.reserve(userId, businessNumber, ceoName, businessStartDate)).getId();
    }

    /**
     * 예약해 둔 시도에 결말을 채운다. 실패도 반드시 채운다 — 재시도 제한 집계와 분쟁 근거가 여기서 나온다.
     *
     * <p>국세청 장애({@code UNAVAILABLE})로 채우면 그 자리는 한도에서 빠진다(예약이 풀린다).
     * 결말을 채우는 쪽이 업무 트랜잭션(발급·재인증 반영)과 따로 도는 것은 의도다 — 업무가 롤백돼도 국세청은 실제로
     * 불렸으므로 결말은 남아야 한다.
     */
    @Transactional
    public void completeAttempt(Long attemptId, VerificationOutcome outcome, String rawResponse) {
        attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("예약된 시도가 없다: attemptId=" + attemptId))
                .complete(outcome, rawResponse);
    }

    /** 자동 재질의 1건을 감사 로그에 남긴다. 기록은 사용자 시도와 동일하되 일일 한도는 소모하지 않는다. */
    @Transactional
    public void recordAutomaticAttempt(Long userId, String businessNumber, String ceoName,
                                       LocalDate businessStartDate, VerificationOutcome outcome, String rawResponse) {
        attemptRepository.save(CompanyVerificationAttempt.automatic(
                userId, businessNumber, ceoName, businessStartDate, outcome, rawResponse));
    }

    /**
     * 본인이 받은 인증을 읽어온다. 국세청 재질의에 쓸 세 값을 트랜잭션 밖으로 빼내는 창구다.
     *
     * <p>{@code findByIdAndUserId} 로 userId 를 함께 거는 것은 등록 경로와 같은 방어다 —
     * 남의 인증 식별자를 주워 쓰면 남의 사업자번호로 국세청을 호출하게 된다.
     */
    @Transactional(readOnly = true)
    public CompanyVerification loadOwned(Long userId, Long verificationId) {
        return verificationRepository.findByIdAndUserId(verificationId, userId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.VERIFICATION_NOT_FOUND));
    }

    /**
     * 자동 재질의 통과분을 반영한다 — 판정 시각과 유효기간만 새로 찍는다.
     *
     * <p>검증값(대표자명·개업일자)은 건드리지 않는다. 저장된 값 그대로 다시 물어 통과한 경우에만 불리므로
     * 바꿀 것이 없기 때문이다. 값이 실제로 바뀌었다면 국세청이 MISMATCH 를 돌려주고 이 메서드는 불리지 않는다
     * (그 경우 사용자가 재인증 경로로 새 값을 넣어야 한다).
     *
     * <p><b>동시성:</b> 만료 판단(서비스의 {@code needsRefresh})은 국세청을 기다리는 사이 낡을 수 있다. 만료된 인증으로
     * 발행을 두 번 누르면 먼저 끝난 요청이 인증을 소비(CONSUMED)한 뒤에 늦은 요청의 반영이 도착한다. 그래서
     * 인증 행을 비관적 락으로 잡고 <i>최종 상태</i>로 다시 확인해, 이미 소비·회수된 인증이면 건드리지 않는다
     * ({@link #applyReverification} 과 같은 이유로 둘 다 필요하다 — 락이 없으면 커밋 전 소비를 못 보고, 재확인이
     * 없으면 커밋된 소비를 덮는다). 건너뛰어도 예외는 던지지 않는다 — 이어지는 등록 단계가 "이미 사용된 인증"으로
     * 거절하는 것이 사용자에게 맞는 응답이다. 국세청은 실제로 불렸으므로 시도 기록은 그대로 남긴다.
     */
    @Transactional
    public void refresh(Long userId, Long verificationId, LocalDateTime now, Duration ttl, String rawResponse) {
        CompanyVerification verification = loadOwnedForUpdate(userId, verificationId);
        if (verification.isAutoRefreshable()) {
            verification.refresh(now, now.plus(ttl));
        }

        attemptRepository.save(CompanyVerificationAttempt.automatic(
                userId, verification.getBusinessNumber(), verification.getCeoName(),
                verification.getBusinessStartDate(), VerificationOutcome.VALID, rawResponse));
    }

    /**
     * 자동 재질의가 국세청 판정에서 떨어진 경우. 만료를 확정 기록하고 시도를 감사 로그에 남긴다.
     *
     * <p>상태를 지금 찍어두는 이유는 등록 경로가 뒤이어 같은 인증을 읽기 때문이다 — 확정해 두지 않으면
     * 매 발행 시도마다 국세청을 다시 부른다. 되살릴 길은 남아 있다: 사용자가 재인증하면 새 인증이 발급되고,
     * 초안은 사업자번호로 키잉돼 있어 그대로 이어진다.
     *
     * <p>{@link #refresh} 와 같은 이유로 락 아래에서 최종 상태를 다시 보고, 이미 소비·회수된 인증은 만료로 바꾸지 않는다.
     */
    @Transactional
    public void markExpired(Long userId, Long verificationId, VerificationOutcome outcome, String rawResponse) {
        CompanyVerification verification = loadOwnedForUpdate(userId, verificationId);
        if (verification.isAutoRefreshable()) {
            verification.markExpired();
        }

        attemptRepository.save(CompanyVerificationAttempt.automatic(
                userId, verification.getBusinessNumber(), verification.getCeoName(),
                verification.getBusinessStartDate(), outcome, rawResponse));
    }

    /**
     * 중복 검사 후 인증 레코드를 발급한다.
     *
     * <p>중복 검사와 저장을 한 트랜잭션에 묶지만, 이것만으로 동시성이 완전히 막히지는 않는다. 인증 시점엔
     * 아직 회사가 없어서 두 사용자가 같은 번호로 동시에 인증하면 둘 다 통과할 수 있다. 최종 방어선은
     * 회사 등록 시점의 활성 부분 유니크 인덱스다({@code CompanyBusinessNumberIndexInitializer}).
     *
     * <p>시도 기록은 여기서 하지 않는다 — 중복으로 던지면 이 트랜잭션이 롤백돼 함께 사라지기 때문이다. 결말(VALID/DUPLICATE)은
     * 서비스가 {@link #completeAttempt} 로 따로 채운다.
     */
    @Transactional
    public CompanyVerification issue(Long userId, String businessNumber, String ceoName,
                                     LocalDate businessStartDate, LocalDateTime now, Duration ttl) {
        if (companyRepository.existsByBusinessNumberAndDeletedFalse(businessNumber)) {
            throw new BusinessException(CompanyErrorCode.BUSINESS_NUMBER_TAKEN);
        }
        return verificationRepository.save(
                CompanyVerification.issue(userId, businessNumber, ceoName, businessStartDate, now, ttl));
    }

    /**
     * 재인증 대상 회사의 사업자번호를 돌려준다(국세청 재질의에 쓸 값).
     *
     * <p>사업자번호를 요청 본문으로 받지 않고 DB 저장값을 쓰는 것이 재인증의 핵심이다 — 번호를 새로
     * 입력받으면 오타나 타사 번호로 인증을 갈아끼워 배지를 탈취할 수 있다. 국세청 호출 전에 필요한 값이라
     * 짧은 읽기 트랜잭션으로 먼저 뽑아오면서, 소유(멤버) 검증과 선행 조건(businessVerified)도 함께 확인한다.
     */
    @Transactional(readOnly = true)
    public String loadOwnedVerifiedBusinessNumber(Long companyId, Long userId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));
        assertReverifiable(company, userId);
        return company.getBusinessNumber();
    }

    /**
     * 국세청 재인증 통과분을 반영한다.
     *
     * <p>회사의 대표자명·개업일자를 검증값으로 덮어쓰고 인증 시각을 갱신하며, 연결된 인증 레코드도 같은
     * 값으로 맞춘다. 대표자명(ceo_name)이 검색 도큐먼트에 색인되므로 재색인한다. 국세청 호출이 끝난 뒤에만 부르므로
     * 이 트랜잭션은 짧게 유지된다. 시도의 결말(VALID)은 반영 성공·거부와 무관하게 서비스가 따로 채운다.
     *
     * <p><b>동시성:</b> 1단계({@link #loadOwnedVerifiedBusinessNumber})의 확인은 국세청을 기다리는 사이 낡을 수
     * 있다 — 그 틈에 관리자가 인증을 회수하면, 확인 없이 반영하는 순간 회수가 되돌려진다. 그래서 회사 행을
     * 비관적 락으로 잡은 뒤 같은 조건을 <i>최종 상태</i>로 다시 확인한다. 둘 다 필요하다.
     * <ul>
     *   <li>재확인만 있으면: 회수 트랜잭션이 아직 커밋 전일 때 커밋된 옛 값(true)을 읽고 통과한 뒤, 커밋 시
     *       전체 컬럼 UPDATE 로 회수를 덮는다.</li>
     *   <li>락만 있으면: 이미 커밋된 회수를 그대로 덮는다.</li>
     * </ul>
     * 회수 쪽({@code CompanyUpdateService#revokeBusinessVerificationByAdmin})도 같은 락을 잡으므로 두 경로는
     * 회사 단위로 직렬화되고, 나중에 락을 얻은 쪽이 앞선 쪽의 커밋 결과를 보고 판단한다.
     *
     * @return 갱신된 인증 시각(= now). 응답에 그대로 실어 최초 인증 후 1년 재인증 주기의 기준점을 알린다.
     */
    @Transactional
    public LocalDateTime applyReverification(Long userId, Long companyId,
                                             String ceoName, LocalDate businessStartDate, LocalDateTime now) {
        Company company = companyRepository.findByIdForUpdate(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));
        assertReverifiable(company, userId);
        company.applyBusinessReverification(ceoName, businessStartDate, now);

        // 권위 있는 인증 레코드도 새 검증값으로 맞춘다. 소비된 인증이 존재하는 게 정상이지만, 없더라도
        // 회사 플래그가 비정규화 진실이므로 갱신은 계속 진행한다(revoke 경로와 같은 방어적 처리).
        verificationRepository.findByCompanyId(companyId)
                .ifPresent(verification -> verification.reverify(ceoName, businessStartDate, now));

        searchDocumentWriter.write(companyId);
        return now;
    }

    // 자동 재질의 반영 경로(refresh·markExpired)의 인증 로드. 소비와 직렬화하려고 행을 잠근다.
    private CompanyVerification loadOwnedForUpdate(Long userId, Long verificationId) {
        return verificationRepository.findByIdAndUserIdForUpdate(verificationId, userId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.VERIFICATION_NOT_FOUND));
    }

    // 재인증 선행 조건. 국세청 호출 전(1단계)과 반영 직전(2단계, 락 아래)에 같은 기준으로 두 번 확인한다.
    // 삭제된 회사는 소유자에게 없는 회사라 404 다 — 1단계에서 걸러 국세청 호출·일일 한도를 쓰지 않고, 국세청을
    // 기다리는 사이 삭제가 커밋되면 2단계에서 반영을 막는다.
    // 미인증 회사는 재인증 대상이 아니다(인증이 회수됐거나 관리자 대신등록 등). businessVerified=true 는
    // 등록 시 인증본을 소비했다는 뜻이라 businessNumber 가 반드시 채워져 있다.
    private void assertReverifiable(Company company, Long userId) {
        if (company.isDeleted()) {
            throw new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND);
        }
        if (!companyMemberRepository.existsByCompanyIdAndUserId(company.getId(), userId)) {
            throw new BusinessException(CompanyErrorCode.COMPANY_ACCESS_DENIED);
        }
        if (!company.isBusinessVerified()) {
            throw new BusinessException(CompanyErrorCode.COMPANY_NOT_BUSINESS_VERIFIED);
        }
    }
}
