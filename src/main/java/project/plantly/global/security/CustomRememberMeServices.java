package project.plantly.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;


// 커스텀 로그인 엔드포인트는 폼 로그인 필터를 거치지 않으므로,
// remember-me 발급 여부를 요청 파라미터가 아닌 LoginRequest.remember 값으로 직접 판단한다.
// AuthService는 remember=true일 때만 loginSuccess()를 호출하므로, 여기서는 항상 발급으로 본다.
// (반대로 autoLogin/쿠키 검증 로직은 부모 구현을 그대로 사용한다.)
//
// 현재 미사용: 첫 배포는 소셜 로그인 전용이고 remember-me 를 비활성화했다(SecurityConfig 에 빈·설정 없음).
// 자체 로그인 재도입을 대비해 남겨 둔다. 단, 자체 로그인을 formLogin() 으로 전환한다면
// formLogin 은 요청 파라미터(remember-me)로 발급 여부를 정하므로 아래의 '항상 true' 재정의는 제거해야 한다
// (그 경우 기본 TokenBasedRememberMeServices 로 충분하다).

public class CustomRememberMeServices extends TokenBasedRememberMeServices {

    public CustomRememberMeServices(String key, UserDetailsService userDetailsService) {
        super(key, userDetailsService, RememberMeTokenAlgorithm.SHA256);
    }

    @Override
    protected boolean rememberMeRequested(HttpServletRequest request, String parameter) {
        return true;
    }
}