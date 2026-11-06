package project.plantly.domain.company.repository;

import project.plantly.domain.company.entity.CompanyVerification;

import java.util.Optional;

/**
 * 인증 레코드 비관적 락 조회. {@link CompanyVerificationRepository} 에 섞여 들어가는 Spring Data 커스텀 조각이다.
 *
 * <p>{@code @Lock} 쿼리 메서드가 아니라 직접 구현한 이유는 {@link CompanyLockRepositoryImpl} 과 같다 — OSIV 로
 * 같은 요청이 인증을 이미 읽어 둔 경우에도 락을 얻은 시점의 DB 값을 보장하려면 refresh 로 다시 읽어야 한다.
 */
public interface CompanyVerificationLockRepository {

    /**
     * 본인 인증 레코드를 {@code SELECT ... FOR UPDATE} 로 잠그고 DB 의 최신 상태로 읽어 돌려준다.
     * 남의 인증이면 비어 있다({@code findByIdAndUserId} 와 같은 방어).
     *
     * <p>쓰기 트랜잭션 안에서, 레코드를 고치기 <b>전에</b> 불러야 한다(아직 flush 되지 않은 변경은 사라진다).
     *
     * <p>현재 사용처: 만료 인증 자동 재질의 반영 ↔ 회사 등록의 인증 소비. 늦은 재질의가 소비된 인증을 되살리지 않게 한다.
     */
    Optional<CompanyVerification> findByIdAndUserIdForUpdate(Long id, Long userId);
}
