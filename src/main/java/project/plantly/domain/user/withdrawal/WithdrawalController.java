package project.plantly.domain.user.withdrawal;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.global.response.ApiResponse;
import project.plantly.global.security.UserPrincipal;
import project.plantly.global.security.oauth2.WithdrawalReauthStore;

import java.util.Locale;

// 회원 탈퇴 화면이 쓰는 API. 탈퇴 자체는 API 가 아니라 소셜 재인가 콜백에서 일어난다(WithdrawalReauthHandler).
//  1) GET  /api/v1/users/me/withdrawal → 확인 화면에 보여 줄 것(함께 지워질 기업·초안 수, 본인 확인 제공자)
//  2) POST /api/v1/users/me/withdrawal → 탈퇴 의도를 세션에 두고 재인가 주소를 돌려준다.
//     프론트는 그 주소로 페이지째 이동한다 → 제공자 확인 → 콜백 → 탈퇴 → {프론트}/withdrawn
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/withdrawal")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final WithdrawalReauthStore reauthStore;

    @GetMapping
    public ApiResponse<WithdrawalPreview> preview(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(withdrawalService.preview(principal.getUser().getId()));
    }

    @PostMapping
    public ApiResponse<WithdrawalStartResponse> start(@AuthenticationPrincipal UserPrincipal principal,
                                                      HttpServletRequest request) {
        Long userId = principal.getUser().getId();
        SocialProvider provider = withdrawalService.reauthProvider(userId);
        reauthStore.save(request, userId, provider);

        String reauthUrl = "/oauth2/authorization/" + provider.name().toLowerCase(Locale.ROOT);
        return ApiResponse.success("본인 확인을 진행해 주세요.", new WithdrawalStartResponse(reauthUrl));
    }

    // reauthUrl: 프론트가 페이지째 이동할 재인가 시작 주소(상대 경로 — 프론트 프록시가 백엔드로 넘긴다).
    public record WithdrawalStartResponse(String reauthUrl) {
    }
}
