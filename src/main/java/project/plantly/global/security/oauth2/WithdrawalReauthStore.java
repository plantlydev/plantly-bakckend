package project.plantly.global.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import project.plantly.domain.auth.social.SocialProvider;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

// 탈퇴 의도 보관소. "이 세션의 회원이 탈퇴를 시작했고, 이 제공자로 본인 확인(재인가)을 받는 중이다"를 세션에 둔다.
//  - 넣는 곳: 탈퇴 시작 API(WithdrawalController). 1회용 난수(nonce)를 함께 만들어 재인가 주소에 싣는다.
//  - 의도는 세션에 있다는 것만으로 쓰이지 않는다. 그 난수를 실은 탈퇴 전용 인가 요청(WithdrawalAuthorization)의
//    콜백만 의도를 꺼낼 수 있다 — 같은 세션의 일반 소셜 로그인 콜백은 의도를 건드리지 않고 그냥 로그인이다.
//  - 꺼내면 지운다(1회용). 재인가가 성공하든 실패하든 의도는 한 번만 쓰인다 — 다시 하려면 시작 API 부터.
//  - 유효 시간이 지난 의도는 탈퇴로 이어지지 않는다(콜백이 만료로 처리한다).
@Component
public class WithdrawalReauthStore {

    public static final String SESSION_ATTRIBUTE = WithdrawalReauthStore.class.getName() + ".INTENT";
    public static final Duration TTL = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    // 의도를 저장하고 난수를 돌려준다. 다시 시작하면 이전 의도·난수는 덮어써져 더 쓸 수 없다.
    public String save(HttpServletRequest request, Long userId, SocialProvider provider) {
        String nonce = newNonce();
        request.getSession().setAttribute(SESSION_ATTRIBUTE,
                new WithdrawalReauth(userId, provider, Instant.now().plus(TTL), nonce));
        return nonce;
    }

    // 난수가 맞으면 의도를 지우면서 돌려준다. 없거나 난수가 다르면 empty(세션의 의도는 그대로 둔다 — 더 최근에 시작한 것일 수 있다).
    // 만료 여부는 호출부가 본다(만료도 '탈퇴 시도'라 안내가 달라야 한다).
    public Optional<WithdrawalReauth> take(HttpServletRequest request, String nonce) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof WithdrawalReauth reauth)
                || !reauth.matches(nonce)) {
            return Optional.empty();
        }
        session.removeAttribute(SESSION_ATTRIBUTE);
        return Optional.of(reauth);
    }

    private static String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 세션에 들어가는 값. Serializable — 세션 저장소를 DB(Spring Session JDBC)로 옮겨도 그대로 쓴다.
    public record WithdrawalReauth(Long userId, SocialProvider provider, Instant expiresAt, String nonce)
            implements Serializable {

        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }

        // 길이·내용에 따라 비교 시간이 달라지지 않게 한다.
        boolean matches(String candidate) {
            return candidate != null && MessageDigest.isEqual(
                    nonce.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
        }
    }
}
