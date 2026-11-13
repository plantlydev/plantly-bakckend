package project.plantly.global.security.oauth2;

import org.springframework.security.oauth2.core.user.OAuth2User;
import project.plantly.domain.user.User;
import project.plantly.global.security.UserPrincipal;

import java.util.Map;

// 소셜 로그인 결과의 주체. OAuth2 로그인 필터는 주체가 OAuth2User 이기를 요구하므로,
// 앱 회원 주체(UserPrincipal)를 그대로 물려받고 OAuth2User 가 요구하는 메서드 둘만 채운다.
// 덕분에 컨트롤러의 @AuthenticationPrincipal UserPrincipal 은 소셜 로그인에서도 그대로 동작한다.
//
// 규칙
//  - 제공자 응답(네이버·구글 속성)은 담지 않는다. 로그인 처리 중에 쓰고 버린다 → getAttributes() 는 빈 Map.
//  - 앱 코드는 UserPrincipal 에만 의존한다. instanceof OAuth2UserPrincipal 로 분기하지 않는다.
public class OAuth2UserPrincipal extends UserPrincipal implements OAuth2User {

    public OAuth2UserPrincipal(User user) {
        super(user);
    }

    // OAuth2 쪽에서 쓰는 주체 이름(authorized client 저장 키 등). 자체 로그인의 username 과 같은 값으로 맞춘다.
    @Override
    public String getName() {
        return getUsername();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of();
    }
}
