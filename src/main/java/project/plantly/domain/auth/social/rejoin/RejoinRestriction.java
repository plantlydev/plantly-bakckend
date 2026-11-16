package project.plantly.domain.auth.social.rejoin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.auth.social.SocialProvider;

import java.time.LocalDateTime;

// 탈퇴 후 재가입 제한. 탈퇴할 때 흔적을 남기고(record), 소셜 로그인 판정·가입 때 확인하고(isRestricted),
// 기간이 지난 흔적은 지운다(purgeExpired).
//
// 같은 소셜 계정만 막는다. 다른 제공자 계정(네이버로 탈퇴 → 구글로 가입)은 기간 안에도 가입할 수 있다(정책 확정).
@Service
@RequiredArgsConstructor
public class RejoinRestriction {

    private final RejoinTraceRepository rejoinTraceRepository;
    private final RejoinTraceHasher hasher;
    private final RejoinProperties properties;

    // 탈퇴 트랜잭션 안에서 부른다(탈퇴 서비스, 2단계).
    @Transactional
    public void record(SocialProvider provider, String providerUserId) {
        rejoinTraceRepository.save(RejoinTrace.of(provider, hasher.hash(provider, providerUserId), LocalDateTime.now()));
    }

    @Transactional(readOnly = true)
    public boolean isRestricted(SocialProvider provider, String providerUserId) {
        return rejoinTraceRepository.existsByProviderAndAccountHashAndWithdrawnAtAfter(
                provider, hasher.hash(provider, providerUserId), cutoff());
    }

    // 제한 기간이 지난 흔적을 지운다. 지운 개수를 돌려준다.
    @Transactional
    public int purgeExpired() {
        return rejoinTraceRepository.deleteWithdrawnAtOrBefore(cutoff());
    }

    private LocalDateTime cutoff() {
        return LocalDateTime.now().minus(properties.blockDuration());
    }
}
