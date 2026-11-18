package project.plantly.global.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.user.withdrawal.WithdrawalResult;
import project.plantly.domain.user.withdrawal.WithdrawalService;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.security.UserPrincipal;
import project.plantly.global.security.oauth2.WithdrawalReauthStore.WithdrawalReauth;

import java.io.IOException;
import java.time.Instant;

// 탈퇴 본인 확인(재인가) 콜백 처리. 세션에 탈퇴 의도(WithdrawalReauthStore)가 있을 때 소셜 로그인 성공·실패 핸들러가
// 이쪽으로 넘긴다. 콜백은 브라우저가 페이지째 이동해 오는 요청이라 결과는 전부 프론트 화면으로 리다이렉트한다.
//
// 성공(같은 회원·같은 제공자로 확인):
//   탈퇴(WithdrawalService.withdraw, 커밋) → 연동 해제(best effort) → 세션 무효화·쿠키 삭제 → {프론트}/withdrawn
//   연동 해제가 커밋 뒤인 이유: 외부 호출 실패가 탈퇴를 되돌리면 안 되고, 외부 호출을 DB 트랜잭션 안에 두지 않는다.
//   다른 기기의 세션은 세션 재검증(SessionUserRevalidationFilter)이 다음 요청에서 끊는다.
//
// 탈퇴로 끝나지 않는 경우(error 는 WithdrawalReauthErrorCode 또는 WithdrawalErrorCode 상수명):
//   - 다른 회원으로 확인됨: 로그인 필터가 이미 그 회원으로 세션을 바꿨다 → 로그아웃시키고 {프론트}/login?error=MISMATCH
//   - 같은 회원·다른 제공자, 만료, 탈퇴 거절, 확인 실패: 원래 회원으로 로그인된 채 {프론트}/account/withdraw?error=…
@Slf4j
@Component
public class WithdrawalReauthHandler {

    static final String WITHDRAWN_PATH = "/withdrawn";
    static final String WITHDRAW_PAGE_PATH = "/account/withdraw";
    static final String LOGIN_PATH = "/login";

    private final String frontendBaseUrl;
    private final WithdrawalService withdrawalService;
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final SocialTokenRevoker tokenRevoker;

    // 로그아웃 API 와 같은 정리: 세션 무효화 + 컨텍스트 비우기 + 세션 쿠키 만료.
    private final LogoutHandler sessionTerminator = new CompositeLogoutHandler(
            new SecurityContextLogoutHandler(), new CookieClearingLogoutHandler("JSESSIONID"));

    public WithdrawalReauthHandler(@Value("${app.frontend.base-url}") String frontendBaseUrl,
                                   WithdrawalService withdrawalService,
                                   OAuth2AuthorizedClientRepository authorizedClientRepository,
                                   SocialTokenRevoker tokenRevoker) {
        this.frontendBaseUrl = frontendBaseUrl;
        this.withdrawalService = withdrawalService;
        this.authorizedClientRepository = authorizedClientRepository;
        this.tokenRevoker = tokenRevoker;
    }

    public void onSuccess(HttpServletRequest request, HttpServletResponse response,
                          Authentication authentication, WithdrawalReauth reauth) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        Long userId = ((UserPrincipal) token.getPrincipal()).getUser().getId();

        if (!reauth.userId().equals(userId)) {
            // 다른 회원의 소셜 계정으로 확인했다. 세션은 이미 그 회원으로 바뀌었으므로 둘 다 로그인 상태로 두지 않는다.
            log.warn("탈퇴 본인 확인 불일치(다른 회원) intentUserId={} authenticatedUserId={}", reauth.userId(), userId);
            sessionTerminator.logout(request, response, authentication);
            redirect(response, LOGIN_PATH, WithdrawalReauthErrorCode.WITHDRAWAL_REAUTH_MISMATCH.name());
            return;
        }
        if (!reauth.provider().name().equalsIgnoreCase(registrationId)) {
            // 같은 회원의 다른 제공자로 확인했다. 해제할 토큰이 탈퇴 시작 때 고른 제공자 것이 아니므로 다시 시작시킨다.
            redirect(response, WITHDRAW_PAGE_PATH, WithdrawalReauthErrorCode.WITHDRAWAL_REAUTH_MISMATCH.name());
            return;
        }
        if (reauth.isExpiredAt(Instant.now())) {
            redirect(response, WITHDRAW_PAGE_PATH, WithdrawalReauthErrorCode.WITHDRAWAL_SESSION_EXPIRED.name());
            return;
        }

        // 로그인 필터가 핸들러보다 먼저 방금 받은 토큰을 세션 저장소에 넣어 두었다.
        OAuth2AuthorizedClient client = authorizedClientRepository.loadAuthorizedClient(registrationId, authentication, request);

        WithdrawalResult result;
        try {
            result = withdrawalService.withdraw(userId);
        } catch (BusinessException e) {
            redirect(response, WITHDRAW_PAGE_PATH, e.getErrorCode().name());
            return;
        } catch (RuntimeException e) {
            // 탈퇴 트랜잭션은 롤백됐다. 브라우저가 서버 오류 화면에 멈추지 않게 탈퇴 화면으로 돌려보낸다.
            log.error("회원 탈퇴 처리 실패 userId={}", userId, e);
            redirect(response, WITHDRAW_PAGE_PATH, WithdrawalReauthErrorCode.WITHDRAWAL_REAUTH_FAILED.name());
            return;
        }

        revoke(userId, reauth.provider(), client, result);
        sessionTerminator.logout(request, response, authentication);
        response.sendRedirect(frontendUrl(WITHDRAWN_PATH).toUriString());
    }

    public void onFailure(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException exception, WithdrawalReauth reauth) throws IOException {
        // 로그인 필터는 실패해도 세션에 저장된 원래 회원의 인증을 지우지 않는다 → 로그인된 채 탈퇴 화면으로 돌아간다.
        WithdrawalReauthErrorCode code = switch (SocialLoginErrorCode.from(exception)) {
            // 확인한 소셜 계정이 이 회원의 연결이 아니다(가입 안 된 계정·다른 회원과 이메일이 겹치는 계정·막 탈퇴한 계정).
            case SIGNUP_REQUIRED, EMAIL_IN_USE, REJOIN_RESTRICTED -> WithdrawalReauthErrorCode.WITHDRAWAL_REAUTH_MISMATCH;
            case ACCOUNT_SUSPENDED, SOCIAL_LOGIN_FAILED -> WithdrawalReauthErrorCode.WITHDRAWAL_REAUTH_FAILED;
        };
        if (code == WithdrawalReauthErrorCode.WITHDRAWAL_REAUTH_FAILED) {
            log.warn("탈퇴 본인 확인 실패 userId={}: {}", reauth.userId(), exception.getMessage());
        }
        redirect(response, WITHDRAW_PAGE_PATH, code.name());
    }

    // 재인가한 제공자만 해제할 수 있다. 다른 제공자 연결은 토큰이 없어 로그만 남긴다(계정 연결 기능이 없어 정상 흐름엔 없다).
    private void revoke(Long userId, SocialProvider provider, OAuth2AuthorizedClient client, WithdrawalResult result) {
        if (client == null) {
            log.warn("연동 해제 못 함 — 재인가 토큰이 세션에 없다 userId={} provider={}", userId, provider);
        } else {
            tokenRevoker.revoke(provider, client);
        }
        result.providers().stream()
                .filter(other -> other != provider)
                .forEach(other -> log.warn("연동 해제 못 함 — 재인가하지 않은 제공자 userId={} provider={}", userId, other));
    }

    private void redirect(HttpServletResponse response, String path, String error) throws IOException {
        response.sendRedirect(frontendUrl(path).queryParam("error", error).toUriString());
    }

    private UriComponentsBuilder frontendUrl(String path) {
        return UriComponentsBuilder.fromUriString(frontendBaseUrl).path(path);
    }
}
