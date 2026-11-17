package project.plantly.domain.auth.social.rejoin;

import org.springframework.stereotype.Component;
import project.plantly.domain.auth.social.SocialProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

// 소셜 식별값(네이버 id·구글 sub) → 되돌릴 수 없는 해시. 서버 비밀키(app.rejoin.trace-key)로 HMAC-SHA256 을 만든다.
// 비밀키를 쓰는 이유: 식별값만 알면 누구나 같은 해시를 만들 수 있는 단순 해시와 달리, DB 만 유출돼서는
// 특정 계정이 탈퇴했는지 대조할 수 없다.
// 같은 계정은 늘 같은 해시가 나와야 한다 — 네이버 id 는 앱별로 고정이라 연동 해제 뒤 다시 와도 같다.
@Component
public class RejoinTraceHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public RejoinTraceHasher(RejoinProperties properties) {
        this.key = new SecretKeySpec(properties.traceKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(SocialProvider provider, String providerUserId) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM); // Mac 은 스레드 안전하지 않아 호출마다 만든다
            mac.init(key);
            byte[] digest = mac.doFinal((provider.name() + ":" + providerUserId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("재가입 흔적 해시를 만들 수 없다", e);
        }
    }
}
