package project.plantly.domain.auth.social;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.auth.social.exception.SocialSignupErrorCode;
import project.plantly.domain.auth.social.repository.SocialAccountRepository;
import project.plantly.domain.user.User;
import project.plantly.domain.user.consent.ConsentProperties;
import project.plantly.domain.user.consent.ConsentType;
import project.plantly.domain.user.consent.UserConsent;
import project.plantly.domain.user.consent.repository.UserConsentRepository;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

// 소셜 가입(회원 도메인층). 가입 대기 중인 SocialProfile 과 동의 내용으로 회원·소셜 연결·동의 기록을 한 번에 만든다.
// 로그인은 하지 않는다 — 가입 뒤 프론트가 재인가(/oauth2/authorization/{제공자})로 로그인한다(로그인 경로를 하나로).
@Service
@RequiredArgsConstructor
public class SocialSignupService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final UserConsentRepository userConsentRepository;
    private final ConsentProperties consentProperties;
    private final PasswordEncoder passwordEncoder;

    // 가입 화면에 보여 줄 동의 항목(표시 순서 = ConsentType 선언 순서).
    public List<ConsentTerm> consentTerms() {
        return Arrays.stream(ConsentType.values())
                .map(type -> new ConsentTerm(type, type.isRequired(), consentProperties.versionOf(type)))
                .toList();
    }

    @Transactional
    public User signup(SocialProfile profile, String requestedName, Set<ConsentType> agreed) {
        for (ConsentType type : ConsentType.values()) {
            if (type.isRequired() && !agreed.contains(type)) {
                throw new BusinessException(SocialSignupErrorCode.REQUIRED_CONSENT_MISSING);
            }
        }
        String name = nameOf(profile, requestedName);

        // 가입 대기가 시작된 뒤(판정 시점)와 지금 사이에 상태가 바뀌었을 수 있어 다시 확인한다.
        if (socialAccountRepository.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId()).isPresent()) {
            throw new BusinessException(SocialSignupErrorCode.ALREADY_SIGNED_UP);
        }
        if (userRepository.existsByEmail(profile.email())) {
            throw new BusinessException(SocialSignupErrorCode.EMAIL_IN_USE);
        }

        User user;
        try {
            user = userRepository.saveAndFlush(User.createSocial(profile.email(), unusablePassword(), name));
            socialAccountRepository.saveAndFlush(SocialAccount.link(
                    user.getId(), profile.provider(), profile.providerUserId(), profile.email()));
        } catch (DataIntegrityViolationException e) {
            // 위 확인과 저장 사이에 같은 요청이 먼저 들어간 경우(가입 버튼 연타). 유니크 제약이 최종 판정이다.
            throw new BusinessException(SocialSignupErrorCode.ALREADY_SIGNED_UP);
        }

        Long userId = user.getId();
        userConsentRepository.saveAll(Arrays.stream(ConsentType.values())
                .map(type -> UserConsent.record(userId, type, consentProperties.versionOf(type), agreed.contains(type)))
                .toList());
        return user;
    }

    // 제공자가 준 이름이 우선이다. 제공자가 이름을 주지 않았을 때만 가입 화면에서 입력받은 이름을 쓴다.
    private static String nameOf(SocialProfile profile, String requestedName) {
        if (profile.name() != null) {
            return profile.name();
        }
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        throw new BusinessException(SocialSignupErrorCode.NAME_REQUIRED);
    }

    // 아무도 모르는 비밀번호. 자체 로그인을 꺼 두더라도 추측 가능한 값을 넣지 않는다.
    // 256비트 난수의 Base64(43자) — BCrypt 는 72바이트를 넘는 입력을 거부한다.
    private String unusablePassword() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return passwordEncoder.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    public record ConsentTerm(ConsentType type, boolean required, String version) {
    }
}
