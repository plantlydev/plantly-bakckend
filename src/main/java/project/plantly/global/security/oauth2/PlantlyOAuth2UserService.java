package project.plantly.global.security.oauth2;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import project.plantly.domain.auth.social.SocialLoginResult;
import project.plantly.domain.auth.social.SocialLoginResult.LoggedIn;
import project.plantly.domain.auth.social.SocialLoginResult.RejectReason;
import project.plantly.domain.auth.social.SocialLoginResult.Rejected;
import project.plantly.domain.auth.social.SocialLoginService;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 외부 인증 번역층의 입구. OAuth2 로그인 필터가 토큰을 받은 직후 사용자 정보를 얻으려고 부른다.
//  1) 사용자 정보 호출은 프레임워크 기본 구현(DefaultOAuth2UserService)에 맡긴다.
//  2) 제공자 응답 → SocialProfile (extractor)
//  3) 회원 판정은 도메인층(SocialLoginService)에 맡긴다.
//  4) 판정 결과 → 로그인이면 OAuth2UserPrincipal, 가입 필요면 SocialSignupRequiredException(프로필을 싣고 감),
//     그 밖에는 OAuth2AuthenticationException(SocialLoginErrorCode).
//     예외는 필터가 받아 실패 핸들러로 넘기고, 실패 핸들러가 프론트로 오류 코드를 전달한다.
//
// OAuth2 provider 는 UserDetails.isEnabled() 를 보지 않는다. 정지 검사는 3) 판정이 대신한다.
//
// 거절하면서 연동 해제: 제공자는 우리 판정보다 먼저(동의 화면에서) 앱 연결을 만든다. 그래서 회원이 생기지 않는 거절
// (재가입 제한, 다른 제공자로 가입된 이메일)에도 제공자의 '연결된 앱' 목록에는 플랜틀리가 다시 뜬다 — 탈퇴로 끊은
// 연결이 재가입 시도만으로 되살아나 보인다. 이 두 거절에서는 방금 받은 토큰으로 바로 연동 해제한다(best effort,
// 실패는 SocialTokenRevoker 가 로그만 남긴다).
// 정지(ACCOUNT_SUSPENDED)는 회원과 연결이 실제로 있는 계정이라 끊지 않는다.
// 가입 화면으로 보낸 뒤 가입하지 않고 떠난 경우는 다루지 않는다(토큰을 보관하지 않으므로 끊을 시점이 없다).
@Component
public class PlantlyOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final DefaultOAuth2UserService userInfoClient;
    private final Map<SocialProvider, SocialProfileExtractor> extractors = new EnumMap<>(SocialProvider.class);
    private final SocialLoginService socialLoginService;
    private final SocialTokenRevoker tokenRevoker;

    public PlantlyOAuth2UserService(DefaultOAuth2UserService userInfoClient,
                                    List<SocialProfileExtractor> extractors,
                                    SocialLoginService socialLoginService,
                                    SocialTokenRevoker tokenRevoker) {
        this.userInfoClient = userInfoClient;
        extractors.forEach(extractor -> this.extractors.put(extractor.provider(), extractor));
        this.socialLoginService = socialLoginService;
        this.tokenRevoker = tokenRevoker;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User providerUser = userInfoClient.loadUser(userRequest);

        SocialProfile profile = extractorFor(userRequest.getClientRegistration().getRegistrationId())
                .extract(providerUser.getAttributes());

        SocialLoginResult result = socialLoginService.resolve(profile);
        if (result instanceof LoggedIn loggedIn) {
            return new OAuth2UserPrincipal(loggedIn.user());
        }
        if (result instanceof Rejected rejected) {
            if (rejected.reason() == RejectReason.REJOIN_RESTRICTED || rejected.reason() == RejectReason.EMAIL_IN_USE) {
                unlink(userRequest, profile);
            }
            throw switch (rejected.reason()) {
                case ACCOUNT_SUSPENDED -> SocialLoginErrorCode.ACCOUNT_SUSPENDED.exception("정지·탈퇴한 회원이다");
                case EMAIL_IN_USE -> SocialLoginErrorCode.EMAIL_IN_USE.exception("같은 이메일로 가입된 회원이 있다");
                case REJOIN_RESTRICTED -> SocialLoginErrorCode.REJOIN_RESTRICTED.exception("탈퇴 후 재가입 제한 기간이다");
            };
        }
        // SignupRequired: 실패 핸들러가 이 프로필을 가입 대기 정보로 세션에 넣고 가입 화면으로 보낸다.
        throw new SocialSignupRequiredException(profile);
    }

    // 거절한 로그인의 토큰으로 제공자 연동 해제. 결과는 보지 않는다 — 실패해도 거절 안내는 그대로 간다.
    private void unlink(OAuth2UserRequest userRequest, SocialProfile profile) {
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(
                userRequest.getClientRegistration(), profile.providerUserId(), userRequest.getAccessToken());
        tokenRevoker.revoke(profile.provider(), client);
    }

    // registrationId("naver") ↔ SocialProvider(NAVER). 설정에 등록됐는데 extractor 가 없으면 로그인을 막는다.
    private SocialProfileExtractor extractorFor(String registrationId) {
        SocialProfileExtractor extractor = null;
        for (SocialProvider provider : SocialProvider.values()) {
            if (provider.name().equalsIgnoreCase(registrationId)) {
                extractor = extractors.get(provider);
            }
        }
        if (extractor == null) {
            throw SocialLoginErrorCode.SOCIAL_LOGIN_FAILED.exception("지원하지 않는 제공자: " + registrationId);
        }
        return extractor;
    }
}
