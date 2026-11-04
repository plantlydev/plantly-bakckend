package project.plantly.domain.company.search;

import org.springframework.jdbc.core.RowMapper;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.RegistrationSource;
import project.plantly.domain.company.search.dto.AdminCompanySummary;
import project.plantly.domain.company.support.RegionLabels;

import java.time.LocalDateTime;

/**
 * 관리자 회사 목록 카드({@link AdminCompanySummary}) 프로젝션 SQL·RowMapper.
 *
 * <p>공개 카드 컬럼({@link CompanyCardSql#CARD_COLUMNS})을 그대로 이어붙이고 운영 컬럼(deleted/owner_user_id/
 * registration_source)을 추가한다 — 카테고리/태그/산업군 array_agg 집계는 복붙 없이 공유한다.
 * 소유자는 company_member(role=OWNER) 스칼라 서브쿼리로 뽑는다(미연동이면 null).
 * 별칭 {@code c = company} 전제(SELECT/FROM 은 호출부가 붙인다).
 */
public final class AdminCompanyCardSql {

    private AdminCompanyCardSql() {
    }

    public static final String COLUMNS = CompanyCardSql.CARD_COLUMNS
            // visibility·cover_image_url·business_verified·created_at·updated_at 은 공개 카드 컬럼(CARD_COLUMNS)에 이미 있다 — 여기서 다시 붙이면 같은 컬럼이 두 번 나간다.
            + ", c.deleted"
            + ", (SELECT cm.user_id FROM company_member cm WHERE cm.company_id = c.id AND cm.role = 'OWNER' LIMIT 1) AS owner_user_id"
            + ", c.registration_source";

    public static final RowMapper<AdminCompanySummary> ROW_MAPPER = (rs, i) -> new AdminCompanySummary(
            rs.getLong("id"),
            rs.getString("company_name"),
            rs.getString("intro_title"),
            rs.getString("logo_url"),
            rs.getString("cover_image_url"),
            RegionLabels.fromRoadAddress(rs.getString("address")), // 공개 카드와 같은 시도+시군구 표기
            rs.getBoolean("verified"),
            rs.getBoolean("business_verified"),
            rs.getBoolean("featured"),
            rs.getBoolean("spotlight"),
            rs.getBoolean("deleted"),
            CompanyVisibility.valueOf(rs.getString("visibility")),
            rs.getObject("owner_user_id", Long.class),           // 미연동이면 null (getLong 은 0 으로 뭉갬)
            RegistrationSource.valueOf(rs.getString("registration_source")),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            CompanyCardSql.toList(rs.getArray("category_names")),
            CompanyCardSql.toList(rs.getArray("tag_names")),
            CompanyCardSql.toList(rs.getArray("industry_names")));
}
