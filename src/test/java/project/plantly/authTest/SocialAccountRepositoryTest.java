package project.plantly.authTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class SocialAccountRepositoryTest {

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Test
    @DisplayName("제공자와 제공자 계정 ID 로 연결된 회원을 찾는다")
    void findByProviderAndProviderUserId() {
        // given
        socialAccountRepository.saveAndFlush(SocialAccount.link(1L, SocialProvider.NAVER, "naver-123", "a@naver.com"));

        // when
        Optional<SocialAccount> found =
                socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-123");

        // then
        assertThat(found).hasValueSatisfying(account -> {
            assertThat(account.getUserId()).isEqualTo(1L);
            assertThat(account.getProviderEmail()).isEqualTo("a@naver.com");
            assertThat(account.getCreatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("제공자 계정 ID 가 같아도 제공자가 다르면 찾지 않는다 — ID 는 제공자 안에서만 유일하다")
    void providerUserIdIsScopedByProvider() {
        // given
        socialAccountRepository.saveAndFlush(SocialAccount.link(1L, SocialProvider.NAVER, "same-id", null));

        // when
        Optional<SocialAccount> found =
                socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "same-id");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("같은 제공자 계정을 두 회원에 연결하면 유니크 제약 예외")
    void sameProviderAccountCannotLinkTwoUsers() {
        // given
        socialAccountRepository.saveAndFlush(SocialAccount.link(1L, SocialProvider.NAVER, "naver-123", null));

        // when & then - 제약 위반은 flush 시점에 터지므로 saveAndFlush 로 강제
        assertThatThrownBy(() -> socialAccountRepository.saveAndFlush(
                SocialAccount.link(2L, SocialProvider.NAVER, "naver-123", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("한 회원에 같은 제공자 계정을 둘 연결하면 유니크 제약 예외")
    void userCannotHaveTwoAccountsOfSameProvider() {
        // given
        socialAccountRepository.saveAndFlush(SocialAccount.link(1L, SocialProvider.NAVER, "naver-123", null));

        // when & then
        assertThatThrownBy(() -> socialAccountRepository.saveAndFlush(
                SocialAccount.link(1L, SocialProvider.NAVER, "naver-456", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("한 회원은 서로 다른 제공자 계정을 각각 연결할 수 있다")
    void userCanLinkDifferentProviders() {
        // given
        socialAccountRepository.saveAndFlush(SocialAccount.link(1L, SocialProvider.NAVER, "naver-123", null));

        // when
        socialAccountRepository.saveAndFlush(SocialAccount.link(1L, SocialProvider.GOOGLE, "google-123", null));

        // then
        assertThat(socialAccountRepository.count()).isEqualTo(2);
    }
}
