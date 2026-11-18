package project.plantly.global.security.oauth2;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import project.plantly.global.security.oauth2.WithdrawalReauthStore.WithdrawalReauth;

import java.io.IOException;
import java.util.Optional;

// 소셜 로그인 성공 → 프론트 첫 화면으로 보낸다.
// 세션 고정 방지(세션 ID 교체), CSRF 토큰 교체, 인증 정보의 세션 저장은 이 핸들러보다 먼저 필터가 끝낸다.
// 로그인 전에 보던 화면(next)으로 돌려보내는 건 프론트가 한다(sessionStorage).
//
// 단, 세션에 탈퇴 의도가 있으면 이 콜백은 로그인이 아니라 탈퇴 본인 확인이다 → WithdrawalReauthHandler.
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final WithdrawalReauthStore withdrawalReauthStore;
    private final WithdrawalReauthHandler withdrawalReauthHandler;

    public OAuth2LoginSuccessHandler(@Value("${app.frontend.base-url}") String frontendBaseUrl,
                                     WithdrawalReauthStore withdrawalReauthStore,
                                     WithdrawalReauthHandler withdrawalReauthHandler) {
        super(frontendBaseUrl + "/");
        setAlwaysUseDefaultTargetUrl(true);
        this.withdrawalReauthStore = withdrawalReauthStore;
        this.withdrawalReauthHandler = withdrawalReauthHandler;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        Optional<WithdrawalReauth> reauth = withdrawalReauthStore.take(request);
        if (reauth.isPresent()) {
            withdrawalReauthHandler.onSuccess(request, response, authentication, reauth.get());
            return;
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
