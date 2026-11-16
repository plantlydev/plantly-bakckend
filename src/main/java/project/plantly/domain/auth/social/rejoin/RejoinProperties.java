package project.plantly.domain.auth.social.rejoin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// 탈퇴 후 재가입 제한 설정: app.rejoin.*
//  - traceKey: 재가입 흔적의 소셜 식별값 해시(HMAC)에 쓰는 서버 비밀키. 시크릿이라 커밋된 기본값 없이
//    환경변수(REJOIN_TRACE_KEY)로만 주입한다. 비어 있으면 부팅을 실패시킨다(국세청·네이버 키와 같은 원칙).
//  - blockDuration: 탈퇴 후 같은 소셜 계정으로 재가입을 막는 기간. 이 기간이 지난 흔적은 정기 작업이 지운다.
@ConfigurationProperties(prefix = "app.rejoin")
public record RejoinProperties(String traceKey, Duration blockDuration) {

    public RejoinProperties {
        if (traceKey == null || traceKey.isBlank()) {
            throw new IllegalStateException("재가입 흔적 해시 키가 없다: app.rejoin.trace-key (환경변수 REJOIN_TRACE_KEY)");
        }
        if (blockDuration == null || blockDuration.isNegative() || blockDuration.isZero()) {
            throw new IllegalStateException("재가입 제한 기간이 올바르지 않다: app.rejoin.block-duration");
        }
    }
}
