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

// 소셜 로그인 실패 → 프론트 로그인 화면으로 오류 코드를 붙여 보낸다: {프론트}/login?error={SocialLoginErrorCode}
// 콜백은 브라우저가 페이지째 이동해 오는 요청이라 JSON 이 아니라 리다이렉트로 답한다.
@Slf4j
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private final String frontendBaseUrl;

    public OAuth2LoginFailureHandler(@Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        SocialLoginErrorCode code = SocialLoginErrorCode.from(exception);

        // 판정 결과(가입 필요·정지·이메일 충돌)는 정상 흐름이다. 그 밖의 실패만 원인을 남긴다.
        if (code == SocialLoginErrorCode.SOCIAL_LOGIN_FAILED) {
            log.warn("소셜 로그인 실패: {}", exception.getMessage());
        }

        String target = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                .path("/login")
                .queryParam("error", code.name())
                .build()
                .toUriString();
        response.sendRedirect(target);
    }
}
