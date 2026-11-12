package project.plantly.domain.auth.social;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// 회원(User)과 외부 인증 수단(네이버·구글 계정)을 잇는 연결 고리.
// User 는 애플리케이션 자체 회원이고, 이 엔티티는 "어떤 제공자의 어떤 계정이 그 회원인가"만 기록한다.
// 한 회원이 여러 제공자 계정을 가질 수 있는 구조다(계정 연결 기능은 나중에 필요하면 만든다).
//
// User 와는 raw id 로 느슨하게 참조한다(@ManyToOne 미사용 — CompanyMember·CompanyLike 와 같은 규칙).
//
// 유니크 제약 두 개:
//  - (provider, provider_user_id): 같은 제공자 계정이 두 회원에 붙지 않는다. 로그인 판정의 조회 키이기도 하다.
//  - (user_id, provider): 한 회원에 같은 제공자 계정이 둘 붙지 않는다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "ux_social_account_provider_user",
                columnNames = {"provider", "provider_user_id"}),
        @UniqueConstraint(name = "ux_social_account_user_provider",
                columnNames = {"user_id", "provider"})
})
public class SocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    // 제공자가 준 계정 식별자(네이버 response.id, 구글 sub). 제공자 안에서만 유일하다.
    @Column(nullable = false)
    private String providerUserId;

    // 연결 시점에 제공자가 준 이메일의 스냅샷. 이후 제공자 쪽 이메일이 바뀌어도 동기화하지 않는다.
    // 우리 쪽 이메일은 User.email 이 기준이다.
    private String providerEmail;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private SocialAccount(Long userId, SocialProvider provider, String providerUserId, String providerEmail) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.providerEmail = providerEmail;
    }

    public static SocialAccount link(Long userId, SocialProvider provider, String providerUserId, String providerEmail) {
        return new SocialAccount(userId, provider, providerUserId, providerEmail);
    }
}
