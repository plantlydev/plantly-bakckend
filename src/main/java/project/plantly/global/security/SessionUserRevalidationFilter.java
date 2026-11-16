package project.plantly.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserAuthState;
import project.plantly.domain.user.repository.UserRepository;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

// 로그인된 세션이 지금도 유효한 사용자를 가리키는지 요청마다 DB 로 다시 확인한다.
//
// 왜 필요한가: 세션의 Authentication 은 로그인 시점의 스냅샷이다. 정지·탈퇴·역할 변경(SQL 포함)이 일어나도
// 이미 로그인한 세션(다른 기기 포함)은 옛 상태·옛 권한으로 계속 동작한다.
//
// 판정: 사용자 행이 없거나, 상태가 ACTIVE 가 아니거나, DB 역할이 이 Authentication 의 권한과 다르면 끊는다.
//  - 역할은 principal 이 들고 있는 User 가 아니라 authentication.getAuthorities() 와 비교한다 — 인가 판정에 실제로 쓰이는 값이다.
//  - 권한을 새 값으로 갱신하지 않고 끊는다. 승격도 끊긴다(다시 로그인하면 새 권한이 실린다).
//
// 끊는 방법: 세션 무효화(저장된 SecurityContext 제거) + 현재 요청의 컨텍스트 비우기. 응답은 직접 쓰지 않고 체인을 계속한다.
//  - 이 필터는 AnonymousAuthenticationFilter 앞에 있으므로 요청은 익명으로 이어진다.
//  - 보호 API 는 진입점이 401, 공개 API 는 비로그인 응답(200). URL 인가·@PreAuthorize 모두 이 필터 뒤라 옛 권한이 쓰이지 않는다.
//
// 빈이 아니다(빈이면 서블릿 필터로도 등록된다) — SecurityConfig 가 생성해 체인에 넣는다.
public class SessionUserRevalidationFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public SessionUserRevalidationFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 익명·미인증 요청은 조회하지 않는다.
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && !isStillValid(principal, authentication)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }

    private boolean isStillValid(UserPrincipal principal, Authentication authentication) {
        Optional<UserAuthState> state = userRepository.findAuthStateById(principal.getUser().getId());
        if (state.isEmpty() || state.get().userStatus() != UserStatus.ACTIVE) {
            return false;
        }
        Set<String> expected = Set.of(UserPrincipal.authorityOf(state.get().userRole()).getAuthority());
        Set<String> actual = AuthorityUtils.authorityListToSet(authentication.getAuthorities());
        return expected.equals(actual);
    }
}
