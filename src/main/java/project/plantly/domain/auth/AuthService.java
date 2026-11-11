package project.plantly.domain.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import project.plantly.domain.user.User;
import project.plantly.domain.auth.dto.request.LoginRequest;
import project.plantly.domain.auth.dto.response.LoginResponse;
import project.plantly.domain.user.exception.UserErrorCode;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.security.UserPrincipal;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;


//     세션 기반 로그인.
//     1) AuthenticationManager로 자격증명 검증 (실패 → BusinessException)
//     2) 세션 고정 보호: 기존 세션이 있으면 세션 ID 교체
//     3) 인증 정보를 담은 SecurityContext를 SecurityContextRepository로 세션에 명시적 저장
//     remember-me 는 비활성화했으므로 LoginRequest.remember 는 받기만 하고 무시한다(SecurityConfig 참고).

    public LoginResponse login(LoginRequest request,
                               HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse) {

        Authentication authentication = authenticate(request);

        // 세션 고정 보호: 인증 전에 존재하던 세션 ID를 교체
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        // 인증 컨텍스트를 세션에 명시적으로 저장 (Spring Security 6+ 명시적 저장 정책)
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        User user = ((UserPrincipal) authentication.getPrincipal()).getUser();
        return LoginResponse.from(user);
    }

    private Authentication authenticate(LoginRequest request) {
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (DisabledException e) {
            // 정지/탈퇴 계정 (UserPrincipal.isEnabled() == false)
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        } catch (AuthenticationException e) {
            // 이메일 없음 / 비밀번호 불일치 등
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
    }
}