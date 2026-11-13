package project.plantly.domain.auth.social;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.auth.social.SocialLoginResult.LoggedIn;
import project.plantly.domain.auth.social.SocialLoginResult.Rejected;
import project.plantly.domain.auth.social.SocialLoginResult.RejectReason;
import project.plantly.domain.auth.social.SocialLoginResult.SignupRequired;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserRepository;

import java.util.Optional;

// 소셜 로그인 판정(회원 도메인층). 제공자가 확인해 준 사용자가 우리 회원 중 누구인지 정한다.
// Spring Security OAuth2 타입을 모른다 — 입력은 SocialProfile, 출력은 SocialLoginResult 다.
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public SocialLoginResult resolve(SocialProfile profile) {
        Optional<SocialAccount> account = socialAccountRepository
                .findByProviderAndProviderUserId(profile.provider(), profile.providerUserId());

        if (account.isPresent()) {
            // social_account → users 는 FK 없이 id 값으로만 잇는다. 연결이 가리키는 회원이 없으면 데이터가 깨진 것이다.
            User user = userRepository.findById(account.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "소셜 연결이 존재하지 않는 회원을 가리킨다: socialAccountId=" + account.get().getId()));

            // 자체 로그인(UserPrincipal.isEnabled)과 같은 기준: ACTIVE 만 로그인한다.
            // WITHDRAWN 은 탈퇴 때 연결을 지우기로 했으므로(복기 5.5) 여기 오지 않아야 하지만, 오면 막는다.
            if (user.getUserStatus() != UserStatus.ACTIVE) {
                return new Rejected(RejectReason.ACCOUNT_SUSPENDED);
            }
            return new LoggedIn(user);
        }

        // 이메일 비교는 전체 문자열 그대로다. 제공자가 다르면 같은 사람이어도 이메일이 같을 때만 겹친다.
        if (userRepository.existsByEmail(profile.email())) {
            return new Rejected(RejectReason.EMAIL_IN_USE);
        }
        return new SignupRequired(profile);
    }
}
