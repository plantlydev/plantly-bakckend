package project.plantly.domain.user.consent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

// 동의 항목별 현재 약관 버전: app.consent.versions.{ConsentType}=버전
// 약관을 개정하면 버전만 올린다. 동의 기록(UserConsent)에 그때의 버전이 남는다.
// 항목 하나라도 버전이 없으면 부팅을 실패시킨다 — 버전 없는 동의 기록을 남기지 않기 위해서다.
@ConfigurationProperties(prefix = "app.consent")
public record ConsentProperties(Map<ConsentType, String> versions) {

    public ConsentProperties {
        Map<ConsentType, String> copy = new EnumMap<>(ConsentType.class);
        if (versions != null) {
            copy.putAll(versions);
        }
        for (ConsentType type : ConsentType.values()) {
            String version = copy.get(type);
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("동의 항목의 약관 버전이 없다: app.consent.versions." + type.name());
            }
        }
        versions = Map.copyOf(copy);
    }

    public String versionOf(ConsentType type) {
        return versions.get(type);
    }
}
