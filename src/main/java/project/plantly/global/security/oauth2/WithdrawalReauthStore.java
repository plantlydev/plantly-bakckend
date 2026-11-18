package project.plantly.global.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import project.plantly.domain.auth.social.SocialProvider;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 탈퇴 의도 보관소. "이 세션의 회원이 탈퇴를 시작했고, 이 제공자로 본인 확인(재인가)을 받는 중이다"를 세션에 둔다.
//  - 넣는 곳: 탈퇴 시작 API(WithdrawalController). 꺼내는 곳: 소셜 로그인 성공·실패 핸들러.
//  - 꺼내면 지운다(1회용). 재인가가 성공하든 실패하든 의도는 한 번만 쓰인다 — 다시 하려면 시작 API 부터.
//  - 의도가 있는 세션의 다음 소셜 로그인 콜백은 '로그인'이 아니라 '탈퇴 본인 확인'으로 처리된다.
//  - 유효 시간이 지난 의도는 탈퇴로 이어지지 않는다(콜백이 만료로 처리한다).
@Component
public class WithdrawalReauthStore {

    public static final String SESSION_ATTRIBUTE = WithdrawalReauthStore.class.getName() + ".INTENT";
    public static final Duration TTL = Duration.ofMinutes(10);

    public void save(HttpServletRequest request, Long userId, SocialProvider provider) {
        request.getSession().setAttribute(SESSION_ATTRIBUTE,
                new WithdrawalReauth(userId, provider, Instant.now().plus(TTL)));
    }

    // 세션에 의도가 있으면 지우면서 돌려준다. 만료 여부는 호출부가 본다(만료도 '탈퇴 시도'라 안내가 달라야 한다).
    public Optional<WithdrawalReauth> take(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof WithdrawalReauth reauth)) {
            return Optional.empty();
        }
        session.removeAttribute(SESSION_ATTRIBUTE);
        return Optional.of(reauth);
    }

    // 세션에 들어가는 값. Serializable — 세션 저장소를 DB(Spring Session JDBC)로 옮겨도 그대로 쓴다.
    public record WithdrawalReauth(Long userId, SocialProvider provider, Instant expiresAt) implements Serializable {
        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
