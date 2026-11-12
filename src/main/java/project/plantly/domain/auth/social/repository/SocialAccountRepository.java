package project.plantly.domain.auth.social.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;

import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    // 소셜 로그인 판정의 조회 키. (provider, providerUserId) 유니크 제약이 결과를 최대 1건으로 보장한다.
    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
}
