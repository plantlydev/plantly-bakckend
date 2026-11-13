package project.plantly.domain.auth.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import project.plantly.domain.user.consent.ConsentType;

import java.util.Set;

public record SocialSignupRequest(
        // 동의한 항목. 필수 항목이 빠지면 REQUIRED_CONSENT_MISSING, 선택 항목은 빠지면 미동의로 기록한다.
        @NotNull(message = "동의 항목은 필수입니다.")
        Set<ConsentType> agreements,

        // 제공자가 이름을 주지 않았을 때만 쓴다(가입 대기 조회의 name 이 null 일 때). 규칙은 자체 회원가입과 같다.
        @Size(min = 2, max = 30, message = "이름은 최소 2자 최대 30자입니다.")
        @Pattern(regexp = "^[a-zA-Z가-힣]+$", message = "이름은 한글 또는 영문만 입력 가능합니다.")
        String name
) {
}
