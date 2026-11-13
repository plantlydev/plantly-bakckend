package project.plantly.global.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import project.plantly.domain.auth.social.SocialProfile;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 가입 대기 정보 보관소. "제공자가 확인해 준 사람인데 아직 우리 회원은 아니다"라는 상태를 세션에 둔다.
//  - 인증이 아니다. SecurityContext 에 넣지 않으므로 이 상태로는 어떤 API 도 인증되지 않는다.
//  - 넣는 곳: 판정이 '가입 필요'일 때 실패 핸들러. 꺼내는 곳: 가입 API. 지우는 곳: 가입 완료.
//  - 유효 시간이 지나면 없는 것으로 본다. 동의 화면을 띄워 둔 채 오래 자리를 비우면 소셜 로그인부터 다시 한다.
@Component
public class PendingSocialSignupStore {

    public static final String SESSION_ATTRIBUTE = PendingSocialSignupStore.class.getName() + ".PENDING";
    public static final Duration TTL = Duration.ofMinutes(10);

    public void save(HttpServletRequest request, SocialProfile profile) {
        request.getSession().setAttribute(SESSION_ATTRIBUTE, new PendingSocialSignup(profile, Instant.now().plus(TTL)));
    }

    public Optional<SocialProfile> find(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof PendingSocialSignup pending)) {
            return Optional.empty();
        }
        if (pending.isExpiredAt(Instant.now())) {
            session.removeAttribute(SESSION_ATTRIBUTE);
            return Optional.empty();
        }
        return Optional.of(pending.profile());
    }

    public void remove(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }

    // 세션에 들어가는 값. Serializable — 세션 저장소를 DB(Spring Session JDBC)로 옮겨도 그대로 쓴다.
    public record PendingSocialSignup(SocialProfile profile, Instant expiresAt) implements Serializable {
        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
