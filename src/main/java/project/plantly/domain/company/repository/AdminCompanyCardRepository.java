package project.plantly.domain.company.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import project.plantly.domain.company.search.AdminCompanyCardSql;
import project.plantly.domain.company.search.AdminCompanySearchCriteria;
import project.plantly.domain.company.search.LikePatterns;
import project.plantly.domain.company.search.dto.AdminCompanySummary;

import java.util.ArrayList;
import java.util.List;

/**
 * 관리자 회사 목록 읽기 전용 리포지토리. 기본은 전체 회사(삭제 포함)이며, 지정된 조건만 AND(교집합)로 좁힌다.
 *
 * <p>공개 검색과 달리 검색 도큐먼트({@code company_search_document})를 쓰지 않는다 — 그 도큐먼트는 삭제 회사를
 * 색인하지 않기 때문이다. 관리자는 삭제 회사도 봐야 하므로 {@code company} 본체에서 직접 조회한다. 카드 프로젝션은
 * {@link AdminCompanyCardSql}(공개 카드 + 운영 컬럼)을 재사용한다. 기본 정렬은 최신 등록순.
 */
@Repository
public class AdminCompanyCardRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminCompanyCardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String FROM = " FROM company c ";

    // 최신 등록순. id 는 안정 페이징용 tie-breaker.
    private static final String ORDER_BY = " ORDER BY c.created_at DESC, c.id DESC ";

    public Page<AdminCompanySummary> findForAdmin(AdminCompanySearchCriteria criteria, Pageable pageable) {
        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        // 불리언 3-상태: null 이면 조건 자체를 안 붙인다(=상관없음).
        appendBool(conditions, params, "verified", "c.verified", criteria.verified());
        appendBool(conditions, params, "featured", "c.featured", criteria.featured());
        appendBool(conditions, params, "spotlight", "c.spotlight", criteria.spotlight());
        appendBool(conditions, params, "deleted", "c.deleted", criteria.deleted());
        appendCompanyName(conditions, params, criteria.companyName());
        appendOwner(conditions, params, criteria.ownerUserId());

        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());

        List<AdminCompanySummary> content = jdbc.query(
                "SELECT " + AdminCompanyCardSql.COLUMNS + FROM + where + ORDER_BY + " LIMIT :limit OFFSET :offset",
                params, AdminCompanyCardSql.ROW_MAPPER);

        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, params, Long.class);
            return total == null ? 0L : total;
        });
    }

    private void appendBool(List<String> conditions, MapSqlParameterSource params,
                            String name, String column, Boolean value) {
        if (value == null) {
            return;
        }
        conditions.add(column + " = :" + name);
        params.addValue(name, value);
    }

    // 회사명 부분일치(대소문자 무시). LIKE 메타문자는 이스케이프한다.
    private void appendCompanyName(List<String> conditions, MapSqlParameterSource params, String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return;
        }
        conditions.add("c.company_name ILIKE :companyName ESCAPE '\\'");
        params.addValue("companyName", LikePatterns.contains(companyName));
    }

    // 소유자 정확 등치. 소유는 company_member(role=OWNER) 로 판정한다. (멤버 없는 미연동 회사는 자연히 제외된다)
    private void appendOwner(List<String> conditions, MapSqlParameterSource params, Long ownerUserId) {
        if (ownerUserId == null) {
            return;
        }
        conditions.add("EXISTS (SELECT 1 FROM company_member cm" +
                " WHERE cm.company_id = c.id AND cm.user_id = :ownerUserId AND cm.role = 'OWNER')");
        params.addValue("ownerUserId", ownerUserId);
    }
}
