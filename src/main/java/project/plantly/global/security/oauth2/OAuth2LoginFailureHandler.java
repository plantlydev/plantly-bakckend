package project.plantly.global.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

// 소셜 로그인이 '로그인'으로 끝나지 않은 모든 경우. 콜백은 브라우저가 페이지째 이동해 오는 요청이라
// JSON 이 아니라 프론트 화면으로 리다이렉트한다.
//  - 가입 필요: 가입 대기 정보를 세션에 담고 {프론트}/signup/social
//  - 그 밖:     {프론트}/login?error={SocialLoginErrorCode}
@Slf4j
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private final String frontendBaseUrl;
    private final PendingSocialSignupStore pendingSignupStore;

    public OAuth2LoginFailureHandler(@Value("${app.frontend.base-url}") String frontendBaseUrl,
                                     PendingSocialSignupStore pendingSignupStore) {
        this.frontendBaseUrl = frontendBaseUrl;
        this.pendingSignupStore = pendingSignupStore;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        if (exception instanceof SocialSignupRequiredException signupRequired) {
            pendingSignupStore.save(request, signupRequired.getProfile());
            response.sendRedirect(frontendUrl("/signup/social").toUriString());
            return;
        }

        SocialLoginErrorCode code = SocialLoginErrorCode.from(exception);

        // 판정 결과(정지·이메일 충돌)는 정상 흐름이다. 그 밖의 실패만 원인을 남긴다.
        if (code == SocialLoginErrorCode.SOCIAL_LOGIN_FAILED) {
            log.warn("소셜 로그인 실패: {}", exception.getMessage());
        }

        response.sendRedirect(frontendUrl("/login").queryParam("error", code.name()).toUriString());
    }

    private UriComponentsBuilder frontendUrl(String path) {
        return UriComponentsBuilder.fromUriString(frontendBaseUrl).path(path);
    }
}
