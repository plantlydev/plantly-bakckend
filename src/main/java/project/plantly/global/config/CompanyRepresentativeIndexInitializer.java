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
 * 대표 연락처·대표 레퍼런스 "회사당 1건" 부분 유니크 인덱스 생성기 — DB 최종 방어선.
 *
 * <p>평소 이 불변식은 앱이 지킨다. 쓰기 경로({@code CompanyChildWriter})가 1건만 받아 그 건을 대표로 표시하고,
 * 동시 교체(PUT)는 회사 행 비관적 락({@code CompanyUpdateService#loadCompany})이 직렬화한다. 이 인덱스는
 * 그 앱 로직을 거치지 않는 쓰기(락을 빠뜨린 새 경로, 수동 SQL 등)가 와도 대표 2건째를 DB 가 거부하게 하는 마지막 층이다.
 * 정상 경로에선 위반이 나지 않으므로 별도 에러 매핑은 두지 않는다(나면 버그 — 500 으로 드러나는 게 맞다).
 *
 * <p>{@code representative = true} 행만 인덱스에 들어가므로, 추후 다건 허용('더보기') 시 비대표 행은 몇 건이든 된다.
 *
 * <p><b>왜 Flyway 가 아니라 ApplicationRunner 인가</b> — {@link CompanyBusinessNumberIndexInitializer} 와 같은
 * 이유다. 부분 인덱스는 JPA 로 표현할 수 없고, Flyway 는 ddl-auto 보다 먼저 돌아 엔티티 테이블을 아직 못 본다.
 *
 * <p><b>기존 데이터에 대표 중복이 있으면</b> 인덱스 생성이 실패한다. 그때 Postgres 의 막연한 에러 대신 어느 회사가
 * 문제인지 담아 기동을 멈춘다 — 조용히 건너뛰면 방어선이 없는 채로 돌게 되기 때문이다. 정리는 사람이 판단한다
 * (어느 건을 대표로 남길지는 데이터를 봐야 안다).
 *
 * <p>Postgres 일 때만 실행한다(H2 슬라이스 테스트는 건너뛴다 — 그쪽은 앱 레벨 1건 제한에 위임).
 */
@Component
@Order(22)
public class CompanyRepresentativeIndexInitializer implements ApplicationRunner {

    private static final List<String> TABLES = List.of("company_contact", "company_project_reference");

    private final DataSource dataSource;

    public CompanyRepresentativeIndexInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("postgresql")) {
                return; // H2 등 비-Postgres: 부분 인덱스 미지원 → 앱 레벨 1건 제한에 위임
            }
            try (Statement st = conn.createStatement()) {
                for (String table : TABLES) {
                    requireNoDuplicateRepresentatives(st, table);
                    st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + table + "_representative "
                            + "ON " + table + " (company_id) WHERE representative");
                }
            }
        }
    }

    private void requireNoDuplicateRepresentatives(Statement st, String table) throws Exception {
        List<Long> companyIds = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT company_id FROM " + table
                + " WHERE representative GROUP BY company_id HAVING count(*) > 1 ORDER BY company_id LIMIT 20")) {
            while (rs.next()) {
                companyIds.add(rs.getLong(1));
            }
        }
        if (!companyIds.isEmpty()) {
            throw new IllegalStateException(table + " 에 대표가 2건 이상인 회사가 있어 부분 유니크 인덱스를 만들 수 없습니다. "
                    + "대표를 1건으로 정리한 뒤 다시 기동하세요. company_id(최대 20개): " + companyIds);
        }
    }
}
