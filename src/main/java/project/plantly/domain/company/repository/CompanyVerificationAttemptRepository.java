package project.plantly.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.plantly.domain.company.entity.CompanyVerificationAttempt;
import project.plantly.domain.company.enums.VerificationOutcome;

import java.time.LocalDateTime;

public interface CompanyVerificationAttemptRepository extends JpaRepository<CompanyVerificationAttempt, Long> {

    /**
     * 일일 재시도 제한 집계. 두 종류를 뺀다.
     *
     * <ul>
     *   <li>{@code UNAVAILABLE} — 국세청 장애로 판정을 못 받은 시도. 사용자 잘못이 아니다.</li>
     *   <li>{@code automatic} — 발행 시점의 자동 재질의. 사용자가 누른 적 없는 호출이라
     *       이걸 세면 "가만히 있었는데 한도를 다 썼다" 가 성립한다.</li>
     * </ul>
     *
     * <p>결말을 기다리는 {@code PENDING}(예약된 시도)은 센다 — 동시 요청이 한도를 넘지 못하게 하는 장치다.
     * 동시성 보장은 이 쿼리가 아니라 호출부의 사용자 행 락이 맡는다({@code CompanyVerificationWriter#reserveAttempt}).
     */
    long countByUserIdAndCreatedAtAfterAndOutcomeNotAndAutomaticFalse(
            Long userId, LocalDateTime since, VerificationOutcome excluded);
}
