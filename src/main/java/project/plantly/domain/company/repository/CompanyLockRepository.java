package project.plantly.domain.company.repository;

import project.plantly.domain.company.entity.Company;

import java.util.Optional;

/**
 * 회사 행 비관적 락 조회. {@link CompanyRepository} 에 섞여 들어가는 Spring Data 커스텀 조각이다.
 *
 * <p>파생 쿼리나 {@code @Lock} + {@code @Query} 로 두지 않고 직접 구현한 이유는 OSIV 때문이다 —
 * {@link CompanyLockRepositoryImpl} 참고.
 */
public interface CompanyLockRepository {

    /**
     * 회사 행을 {@code SELECT ... FOR UPDATE} 로 잠그고, DB 의 최신 상태로 읽어 돌려준다.
     *
     * <p>같은 회사에 대한 쓰기 트랜잭션을 직렬화해야 하는 경로에서 쓴다. 락은 트랜잭션이 끝날 때 풀리므로
     * 반드시 쓰기 트랜잭션 안에서, 외부 호출 없이 짧게 써야 한다. 영속성 컨텍스트의 기존 값을 DB 값으로
     * 덮어쓰므로, 같은 트랜잭션에서 회사를 고치기 <b>전에</b> 불러야 한다(아직 flush 되지 않은 변경은 사라진다).
     *
     * <p>현재 사용처: 재인증 반영 ↔ 관리자 인증 회수. 두 경로가 businessVerified 를 서로 덮어쓰지 않게 한다.
     */
    Optional<Company> findByIdForUpdate(Long id);
}
