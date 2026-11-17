package project.plantly.domain.auth.social.rejoin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 재가입 흔적 정리(RejoinTracePurgeJob)가 앱의 첫 정기 작업이라 스케줄링을 여기서 켠다.
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RejoinProperties.class)
public class RejoinConfig {
}
