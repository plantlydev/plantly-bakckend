package project.plantly.domain.user.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.plantly.domain.user.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * 사용자 행을 {@code SELECT ... FOR UPDATE} 로 잠근다. 사용자 단위로 "확인 후 기록" 을 직렬화할 때 쓴다
     * (현재 사용처: 국세청 일일 시도 한도 예약, {@code CompanyVerificationWriter#reserveAttempt}).
     *
     * <p>락만이 목적이라 돌려받은 엔티티의 값은 쓰지 않는다. JPQL 은 영속성 컨텍스트에 같은 사용자가 이미 있어도
     * 항상 SQL 을 내보내므로 OSIV 아래에서도 행 락은 확실히 잡힌다(값이 갱신되지 않을 뿐이다 —
     * {@code CompanyLockRepositoryImpl} 이 refresh 까지 하는 이유와 달리 여기선 값이 필요 없다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
