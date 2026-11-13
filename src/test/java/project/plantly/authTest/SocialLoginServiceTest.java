package project.plantly.authTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialLoginResult;
import project.plantly.domain.auth.social.SocialLoginResult.LoggedIn;
import project.plantly.domain.auth.social.SocialLoginResult.Rejected;
import project.plantly.domain.auth.social.SocialLoginResult.RejectReason;
import project.plantly.domain.auth.social.SocialLoginResult.SignupRequired;
import project.plantly.domain.auth.social.SocialLoginService;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    private static final SocialProfile PROFILE =
            new SocialProfile(SocialProvider.NAVER, "naver-1", "member@naver.com", "홍길동");

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SocialLoginService socialLoginService;

    @Test
    @DisplayName("연결된 활성 회원이면 로그인")
    void linkedActiveUser_loggedIn() {
        User user = user(UserStatus.ACTIVE);
        givenLinkedTo(10L);
        given(userRepository.findById(10L)).willReturn(Optional.of(user));

        SocialLoginResult result = socialLoginService.resolve(PROFILE);

        assertThat(result).isInstanceOf(LoggedIn.class);
        assertThat(((LoggedIn) result).user()).isSameAs(user);
    }

    @Test
    @DisplayName("연결된 회원이 정지 상태면 거절(ACCOUNT_SUSPENDED)")
    void linkedSuspendedUser_rejected() {
        givenLinkedTo(10L);
        given(userRepository.findById(10L)).willReturn(Optional.of(user(UserStatus.SUSPENDED)));

        assertThat(socialLoginService.resolve(PROFILE))
                .isEqualTo(new Rejected(RejectReason.ACCOUNT_SUSPENDED));
    }

    @Test
    @DisplayName("연결된 회원이 탈퇴 상태면 거절 — 탈퇴 때 연결을 지우므로 정상이면 오지 않지만 오면 막는다")
    void linkedWithdrawnUser_rejected() {
        givenLinkedTo(10L);
        given(userRepository.findById(10L)).willReturn(Optional.of(user(UserStatus.WITHDRAWN)));

        assertThat(socialLoginService.resolve(PROFILE))
                .isEqualTo(new Rejected(RejectReason.ACCOUNT_SUSPENDED));
    }

    @Test
    @DisplayName("연결이 없는 회원을 가리키면 데이터 이상으로 보고 예외")
    void linkToMissingUser_fails() {
        givenLinkedTo(10L);
        given(userRepository.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> socialLoginService.resolve(PROFILE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("연결은 없고 같은 이메일의 회원이 있으면 거절(EMAIL_IN_USE) — 먼저 가입한 쪽을 살린다")
    void noLinkButEmailTaken_rejected() {
        given(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-1"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("member@naver.com")).willReturn(true);

        assertThat(socialLoginService.resolve(PROFILE))
                .isEqualTo(new Rejected(RejectReason.EMAIL_IN_USE));
    }

    @Test
    @DisplayName("연결도 같은 이메일의 회원도 없으면 가입 필요")
    void noLinkNoEmail_signupRequired() {
        given(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-1"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("member@naver.com")).willReturn(false);

        assertThat(socialLoginService.resolve(PROFILE)).isEqualTo(new SignupRequired(PROFILE));
    }

    @Test
    @DisplayName("연결이 있으면 이메일 충돌 검사를 하지 않는다 — 우리 쪽 이메일이 바뀌어도 연결로 로그인한다")
    void linkedUser_skipsEmailCheck() {
        givenLinkedTo(10L);
        given(userRepository.findById(10L)).willReturn(Optional.of(user(UserStatus.ACTIVE)));

        socialLoginService.resolve(PROFILE);

        verify(userRepository, never()).existsByEmail("member@naver.com");
    }

    private void givenLinkedTo(Long userId) {
        given(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-1"))
                .willReturn(Optional.of(SocialAccount.link(userId, SocialProvider.NAVER, "naver-1", "member@naver.com")));
    }

    private static User user(UserStatus status) {
        return User.builder()
                .email("member@naver.com")
                .password("encoded")
                .name("홍길동")
                .userStatus(status)
                .build();
    }
}
