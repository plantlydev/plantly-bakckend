package project.plantly.authTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import project.plantly.AcceptanceTest;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.rejoin.RejoinRestriction;
import project.plantly.domain.auth.social.rejoin.RejoinTrace;
import project.plantly.domain.auth.social.rejoin.RejoinTraceHasher;
import project.plantly.domain.auth.social.rejoin.RejoinTracePurgeJob;
import project.plantly.domain.auth.social.rejoin.RejoinTraceRepository;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 재가입 흔적의 저장·판정·정리를 실제 DB 로 확인한다. 새 컨텍스트를 만들지 않으려고 인수 테스트 기반 클래스를 쓴다
// (설정이 같은 컨텍스트를 공유 — 공유 H2 가 엇갈리는 문제를 피한다).
class RejoinRestrictionTest extends AcceptanceTest {

    @Autowired
    private RejoinRestriction rejoinRestriction;

    @Autowired
    private RejoinTraceRepository rejoinTraceRepository;

    @Autowired
    private RejoinTraceHasher hasher;

    @Autowired
    private RejoinTracePurgeJob purgeJob;

    @Value("${app.rejoin.block-duration}")
    private Duration blockDuration;

    @Test
    @DisplayName("탈퇴 흔적이 남은 같은 소셜 계정은 제한된다 — 흔적에는 원문 식별값이 없다")
    void recordedAccountIsRestricted() {
        rejoinRestriction.record(SocialProvider.NAVER, "naver-1");

        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, "naver-1")).isTrue();
        assertThat(rejoinTraceRepository.findAll())
                .singleElement()
                .satisfies(trace -> assertThat(trace.getAccountHash()).doesNotContain("naver-1"));
    }

    @Test
    @DisplayName("다른 계정·다른 제공자는 제한되지 않는다")
    void otherAccountsAreNotRestricted() {
        rejoinRestriction.record(SocialProvider.NAVER, "naver-1");

        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, "naver-2")).isFalse();
        assertThat(rejoinRestriction.isRestricted(SocialProvider.GOOGLE, "naver-1")).isFalse();
    }

    @Test
    @DisplayName("제한 기간이 지난 흔적은 제한하지 않고, 정리 작업이 지운다 — 기간 안의 흔적은 남긴다")
    void expiredTraceIsIgnoredAndPurged() {
        saveTrace("old-id", LocalDateTime.now().minus(blockDuration).minusMinutes(1));
        saveTrace("fresh-id", LocalDateTime.now().minus(blockDuration).plusMinutes(10));

        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, "old-id")).isFalse();
        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, "fresh-id")).isTrue();

        purgeJob.purge();

        assertThat(rejoinTraceRepository.findAll())
                .singleElement()
                .satisfies(trace -> assertThat(trace.getAccountHash())
                        .isEqualTo(hasher.hash(SocialProvider.NAVER, "fresh-id")));
    }

    @Test
    @DisplayName("재가입 후 다시 탈퇴해 옛 흔적과 새 흔적이 함께 있어도 저장된다(유니크 아님)")
    void duplicateTracesAllowed() {
        saveTrace("naver-1", LocalDateTime.now().minus(blockDuration).minusMinutes(5));
        rejoinRestriction.record(SocialProvider.NAVER, "naver-1");

        assertThat(rejoinTraceRepository.count()).isEqualTo(2);
        assertThat(rejoinRestriction.isRestricted(SocialProvider.NAVER, "naver-1")).isTrue();
    }

    private void saveTrace(String providerUserId, LocalDateTime withdrawnAt) {
        rejoinTraceRepository.save(RejoinTrace.of(
                SocialProvider.NAVER, hasher.hash(SocialProvider.NAVER, providerUserId), withdrawnAt));
    }
}
