package project.plantly.authTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserAuthState;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.security.SessionUserRevalidationFilter;
import project.plantly.global.security.UserPrincipal;
import project.plantly.global.security.oauth2.OAuth2UserPrincipal;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 세션 재검증 필터 단독 검증. 스프링 컨텍스트 없이 필터만 돌린다.
class SessionUserRevalidationFilterTest {

    private static final long USER_ID = 7L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SessionUserRevalidationFilter filter = new SessionUserRevalidationFilter(userRepository);

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpSession session = new MockHttpSession();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("상태 ACTIVE·역할 그대로면 인증과 세션을 유지한다")
    void keepsValidSession() throws Exception {
        Authentication auth = loginAs(UserRole.MEMBER);
        dbState(UserStatus.ACTIVE, UserRole.MEMBER);

        doFilter();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(auth);
        assertThat(session.isInvalid()).isFalse();
        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("상태가 ACTIVE 가 아니면 세션을 무효화하고 익명으로 체인을 계속한다")
    void cutsInactiveUser(UserStatus status) throws Exception {
        loginAs(UserRole.MEMBER);
        dbState(status, UserRole.MEMBER);

        doFilter();

        assertCut();
    }

    @Test
    @DisplayName("사용자 행이 없으면 끊는다")
    void cutsMissingUser() throws Exception {
        loginAs(UserRole.MEMBER);
        when(userRepository.findAuthStateById(USER_ID)).thenReturn(Optional.empty());

        doFilter();

        assertCut();
    }

    @Test
    @DisplayName("강등(ADMIN → MEMBER)되면 끊는다 — 현재 요청도 옛 관리자 권한을 쓰지 않는다")
    void cutsDemotedAdmin() throws Exception {
        loginAs(UserRole.ADMIN);
        dbState(UserStatus.ACTIVE, UserRole.MEMBER);

        doFilter();

        assertCut();
    }

    @Test
    @DisplayName("승격(MEMBER → ADMIN)도 갱신하지 않고 끊는다")
    void cutsPromotedMember() throws Exception {
        loginAs(UserRole.MEMBER);
        dbState(UserStatus.ACTIVE, UserRole.ADMIN);

        doFilter();

        assertCut();
    }

    @Test
    @DisplayName("비교 기준은 principal 의 User 가 아니라 Authentication 에 실린 권한이다")
    void comparesAgainstAuthenticationAuthorities() throws Exception {
        // principal 의 User 는 MEMBER 인데 토큰 권한이 ADMIN 인 (있어선 안 되는) 상태 — 판정에 쓰이는 토큰 쪽을 본다.
        UserPrincipal principal = new UserPrincipal(user(UserRole.MEMBER));
        setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        dbState(UserStatus.ACTIVE, UserRole.MEMBER);

        doFilter();

        assertCut();
    }

    @Test
    @DisplayName("소셜 로그인 토큰(OAuth2AuthenticationToken)도 같은 규칙으로 검사한다")
    void checksOAuth2Authentication() throws Exception {
        OAuth2UserPrincipal principal = new OAuth2UserPrincipal(user(UserRole.MEMBER));
        setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "naver"));
        dbState(UserStatus.SUSPENDED, UserRole.MEMBER);

        doFilter();

        assertCut();
    }

    @Test
    @DisplayName("익명 요청은 DB 를 조회하지 않는다")
    void skipsAnonymous() throws Exception {
        setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        doFilter();

        verify(userRepository, never()).findAuthStateById(any());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("인증 정보가 없는 요청도 DB 를 조회하지 않는다")
    void skipsUnauthenticated() throws Exception {
        doFilter();

        verify(userRepository, never()).findAuthStateById(any());
        assertThat(chain.getRequest()).isNotNull();
    }

    // ---- helpers ----

    private Authentication loginAs(UserRole role) {
        UserPrincipal principal = new UserPrincipal(user(role));
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        setAuthentication(auth);
        return auth;
    }

    private void setAuthentication(Authentication auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.setSession(session);
    }

    private void dbState(UserStatus status, UserRole role) {
        when(userRepository.findAuthStateById(USER_ID)).thenReturn(Optional.of(new UserAuthState(status, role)));
    }

    private void doFilter() throws Exception {
        filter.doFilter(request, response, chain);
    }

    private void assertCut() {
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 응답을 직접 쓰지 않고 체인을 계속한다(보호 API 의 401 은 뒤쪽 진입점이 낸다).
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.isCommitted()).isFalse();
    }

    private static User user(UserRole role) {
        User user = User.builder()
                .email("member@example.com").password("x").name("홍길동").userRole(role).build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }
}
