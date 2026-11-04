package project.plantly.domain.company.search;

import org.springframework.jdbc.core.RowMapper;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.search.dto.CompanySummary;
import project.plantly.domain.company.support.RegionLabels;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 요약 카드 프로젝션 SQL 과 RowMapper. 공개 검색({@link PostgresTrigramCompanySearch})과 내 회사 목록 등
 * '카드 형태로 회사를 나열'하는 읽기 경로가 공통으로 재사용한다.
 *
 * <p>RowMapper 가 만드는 것은 응답({@link CompanySummary})이 아니라 행({@link CompanyCardProjection})이다.
 * 같은 행이 경로마다 다른 응답이 되기 때문이다 — 호출부가 {@code .map(CompanySummary::fromPublic)} 또는
 * {@code ::fromOwner} 로 무엇을 내보낼지 고른다. 이유는 {@link CompanyCardProjection} 주석 참고.
 *
 * <p>{@link #CARD_COLUMNS} 는 별칭 {@code c = company} 를 전제로 한 SELECT 컬럼 목록이다(SELECT/FROM 은 호출부가 붙인다).
 * 회사 스칼라 + 회사가 연결한 카테고리/태그/산업군 이름을 회사당 array_agg 로 집계한다. 카테고리는 직접 링크
 * (company_category)만 — closure 조상은 제외. 셋 다 회사가 등록 시 고른 순서(링크/태그의 display_order)로 정렬한다.
 *
 * <p>관리자 목록({@link AdminCompanyCardSql})도 이 컬럼을 그대로 재사용해 같은 기준으로 본다 — 관리자 카드의
 * 용도가 '사용자에게 어떻게 보이는지' 확인이라서다.
 *
 * <p>{@code brand_color} 는 메인 스팟라이트 배너 배경에만 쓰이지만 이 공유 프로젝션에 둔다 — company 행의 스칼라라
 * 조인이 늘지 않는 반면, 레일 전용 프로젝션을 따로 파면 다섯 읽기 경로가 공유하던 카드 매퍼가 갈라진다.
 * 나머지 경로는 이 값을 안 읽으면 그만이다. {@code cover_image_url} 도 같은 이유로 스칼라다 — 갤러리
 * ({@code company_image})에 두면 '회사당 최대 1장'을 정책으로 세우고 서브쿼리로 뽑아야 한다.
 *
 * <p>주소는 {@code road_address} 원본을 뽑아 오지만 카드에는 시도+시군구까지만 나간다 — 자르는 규칙
 * ({@link RegionLabels})은 SQL 이 아니라 RowMapper 에 둔다. 세종처럼 시군구 단계가 없는 예외가 있어
 * 규칙에 분기가 생기는데, 이를 SQL 문자열 함수로 옮기면 이 공유 프로젝션에 박혀 테스트가 어려워진다.
 */
public final class CompanyCardSql {

    private CompanyCardSql() {
    }

    public static final String CARD_COLUMNS = """
            c.id, c.company_name, c.intro_title, c.logo_url, c.cover_image_url, c.brand_color,
            c.road_address AS address,
            c.verified, c.featured, c.spotlight, c.visibility,
            c.business_verified, c.created_at, c.updated_at,
            (SELECT array_agg(cat.category_name ORDER BY cc.display_order)
               FROM company_category cc JOIN category cat ON cat.id = cc.category_id
               WHERE cc.company_id = c.id) AS category_names,
            (SELECT array_agg(t.tag_name ORDER BY t.display_order)
               FROM company_tag t WHERE t.company_id = c.id) AS tag_names,
            (SELECT array_agg(ind.industry_name ORDER BY ci.display_order)
               FROM company_industry ci JOIN industry ind ON ind.id = ci.industry_id
               WHERE ci.company_id = c.id) AS industry_names
            """;

    public static final RowMapper<CompanyCardProjection> ROW_MAPPER = (rs, i) -> new CompanyCardProjection(
            rs.getLong("id"),
            rs.getString("company_name"),
            rs.getString("intro_title"),
            rs.getString("logo_url"),
            rs.getString("cover_image_url"),
            rs.getString("brand_color"),
            // 카드는 전체 도로명이 아니라 시도+시군구까지만 보여준다("서울시 강남구"). 파생은 읽기 시점 1회.
            RegionLabels.fromRoadAddress(rs.getString("address")),
            rs.getBoolean("verified"),
            rs.getBoolean("featured"),
            rs.getBoolean("spotlight"),
            toList(rs.getArray("category_names")),
            toList(rs.getArray("tag_names")),
            toList(rs.getArray("industry_names")),
            CompanyVisibility.valueOf(rs.getString("visibility")),
            rs.getBoolean("business_verified"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));

    // PG text[] → List<String>. 매칭 행이 없으면 array_agg 는 NULL → 빈 리스트. null 원소는 제거.
    // 관리자 카드 매퍼(AdminCompanyCardSql)도 같은 패키지에서 재사용한다.
    static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return Arrays.stream(values).filter(Objects::nonNull).toList();
    }
}
