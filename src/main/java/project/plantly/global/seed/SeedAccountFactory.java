package project.plantly.global.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserRepository;

/**
 * 시드 계정 생성.
 *
 * <p>가입 API({@code POST /api/v1/users/sign-up})는 MEMBER 만 만들 수 있고 상태도 항상 ACTIVE 라,
 * 관리자와 정지 계정은 빌더로 직접 만든다. 비밀번호는 기존 인수테스트 규약({@code Password1!})을
 * 그대로 써서 프론트·테스트가 같은 자격증명을 공유한다.
 */
@Slf4j
@Component
@Profile(SeedConfig.PROFILE)
@RequiredArgsConstructor
public class SeedAccountFactory {

    /** 모든 시드 계정의 공통 비밀번호. 인수테스트(AcceptanceTest.VALID_PASSWORD)와 같은 값. */
    public static final String PASSWORD = "Password1!";

    /** 시드가 만드는 첫 행. 중간에 깨진 시드의 잔여물을 판정할 때 이 이메일의 존재를 본다. */
    public static final String OWNER1_EMAIL = "owner1@plantly.local";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SeedAccounts create() {
        SeedAccounts accounts = new SeedAccounts(
                save("U1", OWNER1_EMAIL, "김소유", "010-1000-0001", UserRole.MEMBER, UserStatus.ACTIVE,
                        "회사 다수 소유 + 즐겨찾기 다수 + 인증/초안 보유. 프론트 주 작업 계정"),
                save("U2", "owner2@plantly.local", "박이웃", "010-1000-0002", UserRole.MEMBER, UserStatus.ACTIVE,
                        "회사 1건 소유. U1 이 이 회사를 수정 시도하면 차단되어야 한다"),
                save("U3", "empty@plantly.local", "이빈손", "010-1000-0003", UserRole.MEMBER, UserStatus.ACTIVE,
                        "소유 회사 0 / 즐겨찾기 0. 빈 상태 화면 + 인증 실패 감사로그 보유"),
                save("U4", "suspended@plantly.local", "정지원", "010-1000-0004", UserRole.MEMBER, UserStatus.SUSPENDED,
                        "정지 계정. 로그인이 막혀야 한다"),
                save("A1", "admin@plantly.local", "관리자", "010-1000-0005", UserRole.ADMIN, UserStatus.ACTIVE,
                        "관리자. 관리자 목록·플래그·복구")
        );
        log.info("[seed] 계정 {}개 생성", accounts.all().size());
        return accounts;
    }

    private SeedAccount save(String code, String email, String name, String phone,
                             UserRole role, UserStatus status, String note) {
        User user = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .name(name)
                .phone(phone)
                .nickname(name)
                .userRole(role)
                .userStatus(status)
                .build());

        return new SeedAccount(code, user.getId(), email, PASSWORD, name, role, status, note);
    }
}
