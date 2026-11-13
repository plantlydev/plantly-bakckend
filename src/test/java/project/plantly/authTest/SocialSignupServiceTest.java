package project.plantly.authTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import project.plantly.domain.auth.social.SocialAccount;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.SocialSignupService;
import project.plantly.domain.auth.social.exception.SocialSignupErrorCode;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.consent.ConsentProperties;
import project.plantly.domain.user.consent.ConsentType;
import project.plantly.domain.user.consent.UserConsent;
import project.plantly.domain.user.consent.repository.UserConsentRepository;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SocialSignupServiceTest {

    private static final SocialProfile PROFILE =
            new SocialProfile(SocialProvider.NAVER, "naver-1", "member@naver.com", "홍길동");
    private static final Set<ConsentType> REQUIRED_ONLY =
            EnumSet.of(ConsentType.AGE_OVER_14, ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_COLLECTION);

    @Mock
    private UserRepository userRepository;
    @Mock
    private SocialAccountRepository socialAccountRepository;
    @Mock
    private UserConsentRepository userConsentRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private SocialSignupService service;

    @BeforeEach
    void setUp() {
        ConsentProperties consentProperties = new ConsentProperties(Map.of(
                ConsentType.AGE_OVER_14, "v1",
                ConsentType.TERMS_OF_SERVICE, "v2",
                ConsentType.PRIVACY_COLLECTION, "v3",
                ConsentType.MARKETING, "v1"));
        service = new SocialSignupService(userRepository, socialAccountRepository, userConsentRepository,
                consentProperties, passwordEncoder);
    }

    @Test
    @DisplayName("동의 항목은 화면 순서대로 필수 여부·현재 버전과 함께 내려간다")
    void consentTerms() {
        assertThat(service.consentTerms())
                .extracting(SocialSignupService.ConsentTerm::type, SocialSignupService.ConsentTerm::required,
                        SocialSignupService.ConsentTerm::version)
                .containsExactly(
                        tuple(ConsentType.AGE_OVER_14, true, "v1"),
                        tuple(ConsentType.TERMS_OF_SERVICE, true, "v2"),
                        tuple(ConsentType.PRIVACY_COLLECTION, true, "v3"),
                        tuple(ConsentType.MARKETING, false, "v1"));
    }

    @Test
    @DisplayName("가입하면 회원(휴대폰 없음)·소셜 연결·모든 항목의 동의 기록을 만든다 — 선택 미동의도 agreed=false 로 남긴다")
    void signup_createsUserLinkAndConsents() {
        givenSavable();

        User user = service.signup(PROFILE, null, REQUIRED_ONLY);

        assertThat(user.getEmail()).isEqualTo("member@naver.com");
        assertThat(user.getName()).isEqualTo("홍길동");
        assertThat(user.getPhone()).isNull();
        assertThat(user.getPassword()).isEqualTo("unusable-hash");

        ArgumentCaptor<SocialAccount> link = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountRepository).saveAndFlush(link.capture());
        assertThat(link.getValue().getUserId()).isEqualTo(10L);
        assertThat(link.getValue().getProvider()).isEqualTo(SocialProvider.NAVER);
        assertThat(link.getValue().getProviderUserId()).isEqualTo("naver-1");
        assertThat(link.getValue().getProviderEmail()).isEqualTo("member@naver.com");

        assertThat(savedConsents())
                .extracting(UserConsent::getUserId, UserConsent::getConsentType, UserConsent::getVersion, UserConsent::isAgreed)
                .containsExactly(
                        tuple(10L, ConsentType.AGE_OVER_14, "v1", true),
                        tuple(10L, ConsentType.TERMS_OF_SERVICE, "v2", true),
                        tuple(10L, ConsentType.PRIVACY_COLLECTION, "v3", true),
                        tuple(10L, ConsentType.MARKETING, "v1", false));
    }

    @Test
    @DisplayName("랜덤 비밀번호는 매번 다르고 BCrypt 입력 상한(72바이트) 안이다")
    void signup_unusablePasswordFitsBcrypt() {
        givenSavable();

        service.signup(PROFILE, null, REQUIRED_ONLY);
        service.signup(PROFILE, null, REQUIRED_ONLY);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, org.mockito.Mockito.times(2)).encode(raw.capture());
        assertThat(raw.getAllValues()).allSatisfy(value ->
                assertThat(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isBetween(32, 72));
        assertThat(raw.getAllValues().get(0)).isNotEqualTo(raw.getAllValues().get(1));
    }

    @Test
    @DisplayName("마케팅에 동의하면 agreed=true 로 남긴다")
    void signup_marketingAgreed() {
        givenSavable();
        Set<ConsentType> all = EnumSet.allOf(ConsentType.class);

        service.signup(PROFILE, null, all);

        assertThat(savedConsents()).allMatch(UserConsent::isAgreed);
    }

    @Test
    @DisplayName("필수 항목이 하나라도 빠지면 REQUIRED_CONSENT_MISSING — 아무것도 만들지 않는다")
    void signup_requiredConsentMissing() {
        Set<ConsentType> withoutAge = EnumSet.of(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_COLLECTION,
                ConsentType.MARKETING);

        assertError(() -> service.signup(PROFILE, null, withoutAge), SocialSignupErrorCode.REQUIRED_CONSENT_MISSING);
        verifyNoInteractions(userRepository, socialAccountRepository, userConsentRepository);
    }

    @Test
    @DisplayName("이름은 제공자 값이 우선이다 — 요청의 이름은 무시한다")
    void signup_providerNameWins() {
        givenSavable();

        User user = service.signup(PROFILE, "김철수", REQUIRED_ONLY);

        assertThat(user.getName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("제공자가 이름을 주지 않았으면 요청의 이름을 쓴다")
    void signup_requestNameWhenProviderHasNone() {
        givenSavable();
        SocialProfile noName = new SocialProfile(SocialProvider.NAVER, "naver-1", "member@naver.com", null);

        User user = service.signup(noName, " 김철수 ", REQUIRED_ONLY);

        assertThat(user.getName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("제공자도 요청도 이름이 없으면 NAME_REQUIRED")
    void signup_nameRequired() {
        SocialProfile noName = new SocialProfile(SocialProvider.NAVER, "naver-1", "member@naver.com", null);

        assertError(() -> service.signup(noName, null, REQUIRED_ONLY), SocialSignupErrorCode.NAME_REQUIRED);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("대기 중에 같은 소셜 계정이 연결됐으면 ALREADY_SIGNED_UP")
    void signup_alreadyLinked() {
        given(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-1"))
                .willReturn(Optional.of(SocialAccount.link(10L, SocialProvider.NAVER, "naver-1", "member@naver.com")));

        assertError(() -> service.signup(PROFILE, null, REQUIRED_ONLY), SocialSignupErrorCode.ALREADY_SIGNED_UP);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("대기 중에 같은 이메일의 회원이 생겼으면 EMAIL_IN_USE")
    void signup_emailTakenMeanwhile() {
        given(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-1"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("member@naver.com")).willReturn(true);

        assertError(() -> service.signup(PROFILE, null, REQUIRED_ONLY), SocialSignupErrorCode.EMAIL_IN_USE);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("확인 뒤 저장 사이에 같은 요청이 먼저 들어가 유니크 제약에 걸리면 ALREADY_SIGNED_UP")
    void signup_uniqueViolation() {
        given(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-1"))
                .willReturn(Optional.empty());
        given(passwordEncoder.encode(anyString())).willReturn("unusable-hash");
        given(userRepository.saveAndFlush(any(User.class))).willThrow(new DataIntegrityViolationException("dup"));

        assertError(() -> service.signup(PROFILE, null, REQUIRED_ONLY), SocialSignupErrorCode.ALREADY_SIGNED_UP);
        verify(userConsentRepository, never()).saveAll(any());
    }

    // ---- 헬퍼 ----

    private void givenSavable() {
        given(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-1"))
                .willReturn(Optional.empty());
        given(passwordEncoder.encode(anyString())).willReturn("unusable-hash");
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 10L);
            return user;
        });
    }

    @SuppressWarnings("unchecked")
    private List<UserConsent> savedConsents() {
        ArgumentCaptor<List<UserConsent>> captor = ArgumentCaptor.forClass(List.class);
        verify(userConsentRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, SocialSignupErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
