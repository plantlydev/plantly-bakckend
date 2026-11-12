package project.plantly.domain.user;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(unique = true, nullable = false)
    private String email;

    @NotNull
    @Column(nullable = false, length = 60)
    private String password;

    @NotNull
    private String name;

    // 소셜 가입자는 null 이다(네이버는 휴대폰을 수집하지 않고, 구글은 주지 않는다).
    // 자체 회원가입은 요청 검증(SignUpRequest)에서 필수로 받는다.
    // 로컬 DB 는 ddl-auto=update 가 NOT NULL 을 풀지 않으므로 수동으로 실행한다:
    //   ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
    private String phone;

    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus userStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole userRole;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @Builder
    public User(String email, String password, String name, String phone, String nickname, UserStatus userStatus, UserRole userRole, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.nickname = nickname;
        this.userStatus = (userStatus != null) ? userStatus : UserStatus.ACTIVE;
        this.userRole = (userRole != null) ? userRole : UserRole.MEMBER;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    public static User create (String email, String encodedPassword, String name, String phone){

        return User.builder()
                .email(email)
                .password(encodedPassword)
                .name(name)
                .phone(phone)
                .build();
    }

    public void update (String name, String nickname, String phone){
        if (name != null) {this.name = name;}
        if (nickname != null ) {this.nickname = nickname;}
        if (phone != null) {this.phone = phone;}
    }
}
