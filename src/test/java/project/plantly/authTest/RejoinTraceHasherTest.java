package project.plantly.authTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.rejoin.RejoinProperties;
import project.plantly.domain.auth.social.rejoin.RejoinTraceHasher;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RejoinTraceHasherTest {

    private static final Duration DAY = Duration.ofHours(24);

    private final RejoinTraceHasher hasher = new RejoinTraceHasher(new RejoinProperties("key-a", DAY));

    @Test
    @DisplayName("같은 계정은 늘 같은 해시, 원문은 남지 않는다(16진수 64자)")
    void deterministicAndOpaque() {
        String hash = hasher.hash(SocialProvider.NAVER, "naver-1");

        assertThat(hasher.hash(SocialProvider.NAVER, "naver-1")).isEqualTo(hash);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("naver-1");
    }

    @Test
    @DisplayName("제공자가 다르면 같은 식별값이어도 해시가 다르다")
    void providerIsPartOfHash() {
        assertThat(hasher.hash(SocialProvider.NAVER, "same-id"))
                .isNotEqualTo(hasher.hash(SocialProvider.GOOGLE, "same-id"));
    }

    @Test
    @DisplayName("비밀키가 다르면 해시가 다르다 — 키 없이 DB 만으로는 대조할 수 없다")
    void keyIsPartOfHash() {
        RejoinTraceHasher other = new RejoinTraceHasher(new RejoinProperties("key-b", DAY));

        assertThat(other.hash(SocialProvider.NAVER, "naver-1"))
                .isNotEqualTo(hasher.hash(SocialProvider.NAVER, "naver-1"));
    }

    @Test
    @DisplayName("비밀키가 비어 있으면 설정 단계에서 실패한다(부팅 실패)")
    void blankKeyFails() {
        assertThatThrownBy(() -> new RejoinProperties("", DAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RejoinProperties(null, DAY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("제한 기간이 없거나 0 이하면 실패한다")
    void invalidDurationFails() {
        assertThatThrownBy(() -> new RejoinProperties("key", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RejoinProperties("key", Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    }
}
