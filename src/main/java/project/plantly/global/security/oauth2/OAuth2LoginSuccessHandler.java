package project.plantly.global.security.oauth2;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

// 소셜 로그인 성공 → 프론트 첫 화면으로 보낸다.
// 세션 고정 방지(세션 ID 교체), CSRF 토큰 교체, 인증 정보의 세션 저장은 이 핸들러보다 먼저 필터가 끝낸다.
// 로그인 전에 보던 화면(next)으로 돌려보내는 건 4단계에서 붙인다.
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    public OAuth2LoginSuccessHandler(@Value("${app.frontend.base-url}") String frontendBaseUrl) {
        super(frontendBaseUrl + "/");
        setAlwaysUseDefaultTargetUrl(true);
    }
}
