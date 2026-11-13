package project.plantly.domain.auth.social.dto;

import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.SocialSignupService.ConsentTerm;

import java.util.List;

// 가입 화면이 그리는 정보: 어느 제공자의 누구로 가입하는지 + 동의 항목.
// name 이 null 이면 제공자가 이름을 주지 않은 것이다 — 가입 화면에서 이름을 입력받는다.
public record PendingSocialSignupResponse(
        SocialProvider provider,
        String email,
        String name,
        List<ConsentTerm> consents
) {
    public static PendingSocialSignupResponse of(SocialProfile profile, List<ConsentTerm> consents) {
        return new PendingSocialSignupResponse(profile.provider(), profile.email(), profile.name(), consents);
    }
}
