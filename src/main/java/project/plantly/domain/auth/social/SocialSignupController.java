package project.plantly.domain.auth.social;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.plantly.domain.auth.social.dto.PendingSocialSignupResponse;
import project.plantly.domain.auth.social.dto.SocialSignupRequest;
import project.plantly.domain.auth.social.dto.SocialSignupResponse;
import project.plantly.domain.auth.social.exception.SocialSignupErrorCode;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.response.ApiResponse;
import project.plantly.global.security.oauth2.PendingSocialSignupStore;

// 소셜 가입 화면(/signup/social)이 쓰는 API. 둘 다 로그인 전에 호출된다 — 근거는 인증이 아니라 세션의 가입 대기 정보다.
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/social")
public class SocialSignupController {

    private final SocialSignupService socialSignupService;
    private final PendingSocialSignupStore pendingSignupStore;

    // 가입 대기 조회: 누구로 가입하는지와 동의 항목.
    @GetMapping("/pending")
    public ApiResponse<PendingSocialSignupResponse> pending(HttpServletRequest request) {
        SocialProfile profile = pendingProfile(request);
        return ApiResponse.success(PendingSocialSignupResponse.of(profile, socialSignupService.consentTerms()));
    }

    // 가입: 회원·소셜 연결·동의 기록 생성. 로그인은 하지 않고 재인가 주소를 돌려준다.
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SocialSignupResponse> signup(@Valid @RequestBody SocialSignupRequest request,
                                                    HttpServletRequest httpRequest) {
        SocialProfile profile = pendingProfile(httpRequest);
        socialSignupService.signup(profile, request.name(), request.agreements());
        pendingSignupStore.remove(httpRequest);

        String loginUrl = "/oauth2/authorization/" + profile.provider().name().toLowerCase();
        return ApiResponse.success("회원가입이 완료되었습니다.", new SocialSignupResponse(loginUrl));
    }

    private SocialProfile pendingProfile(HttpServletRequest request) {
        return pendingSignupStore.find(request)
                .orElseThrow(() -> new BusinessException(SocialSignupErrorCode.SIGNUP_SESSION_EXPIRED));
    }
}
