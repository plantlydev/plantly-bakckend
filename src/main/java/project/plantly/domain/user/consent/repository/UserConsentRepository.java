package project.plantly.domain.user.consent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.plantly.domain.user.consent.UserConsent;

import java.util.List;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    List<UserConsent> findByUserIdOrderByIdAsc(Long userId);
}
