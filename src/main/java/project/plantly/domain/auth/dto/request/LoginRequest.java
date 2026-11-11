package project.plantly.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Email @NotBlank(message = "이메일을 입력해주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(min = 10, max = 60, message = "비밀번호는 최소 10자 최대 60자입니다.")
        @Pattern(regexp = "^(?=.*[^A-Za-z0-9]).+$", message = "비밀번호에 특수문자를 최소 1개 포함해야 합니다.")
        String password,

        // remember-me 비활성화로 현재는 무시된다. 요청 계약을 깨지 않으려고 남겨 두며,
        // 자체 로그인을 formLogin 으로 전환할 때 이 DTO 와 함께 정리한다.
        boolean remember
) {
}
