package project.plantly.global.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import project.plantly.global.exception.CommonErrorCode;

import java.io.IOException;

// 자체(이메일·비밀번호) 로그인과 회원가입을 막는다. 첫 배포는 소셜 로그인 전용이다.
// app.auth.local-login-enabled=false 일 때만 SecurityConfig 가 체인에 넣는다(빈이 아니다 — 빈이면 서블릿 필터로도 등록된다).
//
// 두 API 는 없는 API 처럼 404 로 답한다. CSRF 필터보다 앞에 둬서 토큰 유무와 상관없이 같은 응답이 나간다.
// 코드는 지우지 않았다 — 자체 로그인을 다시 열 때는 설정값만 바꾼다.
public class LocalLoginDisabledFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOCAL_LOGIN_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/users/sign-up"));

    private final ObjectMapper objectMapper;

    public LocalLoginDisabledFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOCAL_LOGIN_ENDPOINTS.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityResponseWriter.write(response, objectMapper, CommonErrorCode.NOT_FOUND);
    }
}
