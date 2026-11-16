package project.plantly.domain.auth.social.rejoin;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.plantly.domain.auth.social.SocialProvider;

import java.time.LocalDateTime;

// 탈퇴한 소셜 계정의 흔적. 탈퇴 후 일정 기간(app.rejoin.block-duration, 24시간) 같은 소셜 계정의 재가입을 막는 데만 쓴다.
//
// 탈퇴 때 소셜 연결(SocialAccount)과 회원 개인정보는 지우므로, 판정에 필요한 값만 따로 남긴다.
//  - 소셜 식별값은 원문이 아니라 HMAC 해시로 남긴다(RejoinTraceHasher). 원문으로 되돌릴 수 없다.
//  - 탈퇴한 회원(users 행)과 잇는 값은 두지 않는다 — 재가입 회원을 옛 회원과 연결하지 않기로 했다.
//  - 기간이 지나면 정기 작업이 지운다(RejoinTracePurgeJob).
//
// 유니크가 아니다: 재가입 후 다시 탈퇴하면, 지워지기 전의 옛 흔적과 새 흔적이 잠시 함께 있을 수 있다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "rejoin_trace", indexes = {
        @Index(name = "ix_rejoin_trace_provider_hash", columnList = "provider, account_hash")
})
public class RejoinTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    // HMAC-SHA256 의 16진수 문자열(64자)
    @Column(name = "account_hash", nullable = false, length = 64)
    private String accountHash;

    @Column(nullable = false)
    private LocalDateTime withdrawnAt;

    private RejoinTrace(SocialProvider provider, String accountHash, LocalDateTime withdrawnAt) {
        this.provider = provider;
        this.accountHash = accountHash;
        this.withdrawnAt = withdrawnAt;
    }

    public static RejoinTrace of(SocialProvider provider, String accountHash, LocalDateTime withdrawnAt) {
        return new RejoinTrace(provider, accountHash, withdrawnAt);
    }
}
