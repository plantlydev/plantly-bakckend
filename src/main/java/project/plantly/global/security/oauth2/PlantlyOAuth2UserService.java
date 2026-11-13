package project.plantly.global.security.oauth2;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import project.plantly.domain.auth.social.SocialLoginResult;
import project.plantly.domain.auth.social.SocialLoginResult.LoggedIn;
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
//  4) 판정 결과 → 로그인이면 OAuth2UserPrincipal, 아니면 OAuth2AuthenticationException(SocialLoginErrorCode).
//     예외는 필터가 받아 실패 핸들러로 넘기고, 실패 핸들러가 프론트로 오류 코드를 전달한다.
//
// OAuth2 provider 는 UserDetails.isEnabled() 를 보지 않는다. 정지 검사는 3) 판정이 대신한다.
@Component
public class PlantlyOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final DefaultOAuth2UserService userInfoClient;
    private final Map<SocialProvider, SocialProfileExtractor> extractors = new EnumMap<>(SocialProvider.class);
    private final SocialLoginService socialLoginService;

    public PlantlyOAuth2UserService(DefaultOAuth2UserService userInfoClient,
                                    List<SocialProfileExtractor> extractors,
                                    SocialLoginService socialLoginService) {
        this.userInfoClient = userInfoClient;
        extractors.forEach(extractor -> this.extractors.put(extractor.provider(), extractor));
        this.socialLoginService = socialLoginService;
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
            throw switch (rejected.reason()) {
                case ACCOUNT_SUSPENDED -> SocialLoginErrorCode.ACCOUNT_SUSPENDED.exception("정지·탈퇴한 회원이다");
                case EMAIL_IN_USE -> SocialLoginErrorCode.EMAIL_IN_USE.exception("같은 이메일로 가입된 회원이 있다");
            };
        }
        // SignupRequired: 3단계에서 가입 대기 정보를 세션에 넣고 가입 화면으로 보낸다.
        throw SocialLoginErrorCode.SIGNUP_REQUIRED.exception("연결된 회원이 없다");
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
