package project.plantly.domain.user.consent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConsentProperties.class)
public class ConsentConfig {
}
