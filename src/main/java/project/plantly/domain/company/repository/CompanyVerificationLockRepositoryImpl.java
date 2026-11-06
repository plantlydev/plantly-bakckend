package project.plantly.domain.company.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import project.plantly.domain.company.entity.CompanyVerification;

import java.util.Objects;
import java.util.Optional;

/** {@link CompanyVerificationLockRepository} 구현. find 후 refresh(PESSIMISTIC_WRITE) 를 쓰는 이유는 인터페이스 주석 참고. */
class CompanyVerificationLockRepositoryImpl implements CompanyVerificationLockRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<CompanyVerification> findByIdAndUserIdForUpdate(Long id, Long userId) {
        CompanyVerification verification = em.find(CompanyVerification.class, id);
        // userId 는 발급 후 바뀌지 않으므로 락 전에 걸러도 된다 — 남의 행은 잠그지도 않는다.
        if (verification == null || !Objects.equals(verification.getUserId(), userId)) {
            return Optional.empty();
        }
        em.refresh(verification, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(verification);
    }
}
