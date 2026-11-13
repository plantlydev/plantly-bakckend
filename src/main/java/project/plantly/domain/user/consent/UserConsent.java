package project.plantly.domain.user.consent;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// 동의 기록. 덮어쓰지 않고 행을 쌓는다(이력) — 같은 회원·항목의 가장 최근 행이 현재 상태다.
//  - 가입 때 모든 항목을 한 줄씩 남긴다. 선택 항목에 동의하지 않았으면 agreed=false 로 남긴다
//    ("묻지 않았다"와 "거절했다"를 구분하기 위해서다).
//  - 마케팅 철회·재동의, 약관 개정 재동의도 새 행으로 쌓는다(그 기능은 나중에 만든다).
// 마케팅 수신 동의는 동의 시각과 철회 시각을 증명할 수 있어야 하고, 2년마다 수신 동의 여부를 알려야 한다
// (정보통신망법 제50조) — 그 근거가 이 이력이다.
//
// User 와는 raw id 로 느슨하게 참조한다(SocialAccount 와 같은 규칙).
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "ix_user_consent_user_type", columnList = "user_id, consent_type"))
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConsentType consentType;

    // 동의 당시의 약관 버전(ConsentProperties).
    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false)
    private boolean agreed;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private UserConsent(Long userId, ConsentType consentType, String version, boolean agreed) {
        this.userId = userId;
        this.consentType = consentType;
        this.version = version;
        this.agreed = agreed;
    }

    public static UserConsent record(Long userId, ConsentType consentType, String version, boolean agreed) {
        return new UserConsent(userId, consentType, version, agreed);
    }
}
