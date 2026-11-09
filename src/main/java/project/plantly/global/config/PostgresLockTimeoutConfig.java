package project.plantly.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Postgres 연결에 락 대기 상한({@code lock_timeout})을 건다. 값은 {@code app.db.lock-timeout}(이유는
 * application.properties 주석).
 *
 * <p><b>왜 설정 파일이 아니라 코드인가:</b> 연결 옵션({@code spring.datasource.hikari.data-source-properties.options})
 * 은 H2 가 모르는 설정이라 공통 설정에 둘 수 없고, Postgres 프로파일 파일(application-local)은 계정 정보 때문에
 * git 에서 제외돼 있어 거기 두면 다른 개발 환경·운영에 전해지지 않는다. 그래서 커밋되는 코드에서 접속 주소가
 * Postgres 일 때만 붙인다 — 어떤 프로파일이든 Postgres 로 붙으면 상한이 걸리고, H2 인수 테스트는 그대로다.
 *
 * <p>풀은 첫 연결 때 만들어지므로 데이터소스 빈이 만들어진 직후에 옵션을 더해도 모든 연결에 적용된다.
 */
@Configuration
public class PostgresLockTimeoutConfig {

    // BeanPostProcessor 는 다른 빈보다 먼저 만들어져야 하므로 static 으로 둔다.
    @Bean
    static BeanPostProcessor postgresLockTimeoutApplier(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource dataSource && isPostgres(dataSource.getJdbcUrl())) {
                    String lockTimeout = environment.getRequiredProperty("app.db.lock-timeout");
                    String option = "-c lock_timeout=" + lockTimeout;
                    // 다른 곳에서 이미 연결 옵션을 줬다면 덮지 않고 이어 붙인다.
                    Object existing = dataSource.getDataSourceProperties().get("options");
                    dataSource.addDataSourceProperty("options", existing == null ? option : existing + " " + option);
                }
                return bean;
            }
        };
    }

    private static boolean isPostgres(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }
}
