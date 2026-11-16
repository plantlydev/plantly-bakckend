package project.plantly.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {

    private final User user;


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(authorityOf(user.getUserRole()));
    }

    // 역할 → 권한 변환 규칙의 단일 출처. 세션 재검증(SessionUserRevalidationFilter)도 같은 규칙으로 비교한다.
    public static GrantedAuthority authorityOf(UserRole role) {
        return new SimpleGrantedAuthority("ROLE_" + role.name());
    }

    @Override public String getPassword() { return user.getPassword(); }
    @Override public String getUsername() { return user.getEmail(); }

    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }

    // 정지/탈퇴 계정은 비활성으로 처리 → 인증 시 DisabledException 발생(= ACCOUNT_SUSPENDED 매핑)
    @Override public boolean isEnabled() { return user.getUserStatus() == UserStatus.ACTIVE; }

    public User getUser() {
        return user;
    }
}
