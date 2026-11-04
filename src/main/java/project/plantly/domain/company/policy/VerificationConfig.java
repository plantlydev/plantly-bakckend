package project.plantly.domain.company.policy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 인증 정책 설정 바인딩. 값이 코드가 아니라 설정에 있어 별도 배선이 필요하다. */
@Configuration
@EnableConfigurationProperties(VerificationProperties.class)
public class VerificationConfig {
}
