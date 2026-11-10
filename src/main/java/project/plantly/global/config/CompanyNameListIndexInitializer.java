package project.plantly.global.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 태그·소재·장비 이름 "회사당 같은 이름 1건" 유니크 인덱스 생성기 — DB 최종 방어선.
 *
 * <p>정책: 앞뒤 공백을 걷어내고 대소문자를 무시해 같으면 같은 이름이다("AI" = " ai "). 평소엔 쓰기 경로
 * ({@code CompanyChildWriter#distinctNames})가 trim·중복 합치기를 하고, 이 인덱스는 그 앱 로직을 우회한 쓰기를 막는다.
 * 정상 경로에선 위반이 나지 않으므로 별도 에러 매핑은 두지 않는다(나면 버그 — 500 으로 드러나는 게 맞다).
 *
 * <p><b>왜 Initializer 인가</b> — 대소문자 무시라 {@code lower(btrim(이름))} 식(expression) 인덱스가 필요하고,
 * 이건 JPA {@code @UniqueConstraint} 로 표현할 수 없다. Flyway 가 아닌 이유는
 * {@link CompanyBusinessNumberIndexInitializer} 와 같다(ddl-auto 가 테이블을 Flyway 보다 늦게 만든다).
 *
 * <p><b>기존 데이터에 중복이 있으면</b> 어느 회사가 문제인지 담아 기동을 멈춘다
 * ({@link CompanyRepresentativeIndexInitializer} 와 같은 태도 — 조용히 건너뛰면 방어선 없이 돈다).
 *
 * <p>Postgres 일 때만 실행한다(H2 슬라이스 테스트는 건너뛴다 — 그쪽은 쓰기 경로의 중복 합치기에 위임).
 */
@Component
@Order(23)
public class CompanyNameListIndexInitializer implements ApplicationRunner {

    // (테이블, 이름 컬럼, 인덱스 이름)
    private static final List<String[]> TARGETS = List.of(
            new String[]{"company_tag", "tag_name", "ux_company_tag_name"},
            new String[]{"company_material", "material_name", "ux_company_material_name"},
            new String[]{"company_equipment", "equipment_name", "ux_company_equipment_name"});

    private final DataSource dataSource;

    public CompanyNameListIndexInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("postgresql")) {
                return; // H2 등 비-Postgres: 식 인덱스 생략 → 쓰기 경로의 중복 합치기에 위임
            }
            try (Statement st = conn.createStatement()) {
                for (String[] target : TARGETS) {
                    String table = target[0];
                    String key = "lower(btrim(" + target[1] + "))";
                    requireNoDuplicates(st, table, key);
                    st.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + target[2]
                            + " ON " + table + " (company_id, " + key + ")");
                }
            }
        }
    }

    private void requireNoDuplicates(Statement st, String table, String key) throws Exception {
        List<Long> companyIds = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT DISTINCT company_id FROM " + table
                + " GROUP BY company_id, " + key + " HAVING count(*) > 1 ORDER BY company_id LIMIT 20")) {
            while (rs.next()) {
                companyIds.add(rs.getLong(1));
            }
        }
        if (!companyIds.isEmpty()) {
            throw new IllegalStateException(table + " 에 (공백·대소문자 무시) 같은 이름이 2건 이상인 회사가 있어 유니크 인덱스를 "
                    + "만들 수 없습니다. 중복을 정리한 뒤 다시 기동하세요. company_id(최대 20개): " + companyIds);
        }
    }
}
