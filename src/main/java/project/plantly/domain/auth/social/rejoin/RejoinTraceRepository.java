package project.plantly.domain.auth.social.rejoin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.plantly.domain.auth.social.SocialProvider;

import java.time.LocalDateTime;

public interface RejoinTraceRepository extends JpaRepository<RejoinTrace, Long> {

    boolean existsByProviderAndAccountHashAndWithdrawnAtAfter(
            SocialProvider provider, String accountHash, LocalDateTime cutoff);

    @Modifying
    @Query("delete from RejoinTrace t where t.withdrawnAt <= :cutoff")
    int deleteWithdrawnAtOrBefore(@Param("cutoff") LocalDateTime cutoff);
}
