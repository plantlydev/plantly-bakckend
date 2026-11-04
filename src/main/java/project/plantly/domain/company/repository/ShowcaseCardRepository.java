package project.plantly.domain.company.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import project.plantly.domain.company.search.CompanyCardSql;
import project.plantly.domain.company.search.dto.CompanySummary;

import java.util.ArrayList;
import java.util.List;

/**
 * 메인 화면 노출 레일(스팟라이트 / 추천 / 최근 등록) 읽기 전용 리포지토리. 검색·내 회사 목록과 동일한 카드 프로젝션
 * ({@link CompanyCardSql})을 재사용하되, 페이징이 아니라 '자리 수(slots)만큼 잘라 오는' 조회다.
 *
 * <p>스팟라이트·추천 두 레일은 관리자가 켠 플래그({@code company.spotlight} / {@code company.featured})로만
 * 후보를 고른다. 최근 등록 레일은 플래그를 보지 않는 순수 시간순이다.
 */
@Repository
public class ShowcaseCardRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ShowcaseCardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 자격 기반 레일(스팟라이트/추천) 1개의 조회 결과. {@code cards} 는 자리 수만큼 잘린 노출분이고,
     * {@code candidateCount} 는 자르기 전 후보 총수다 — 자리보다 많아졌는지 판단하는 근거.
     *
     * <p>최근 등록 레일은 이 타입을 쓰지 않는다. 거기선 초과가 이상 신호가 아니라 정상이라
     * 셀 이유가 없고, 세면 공개 회사 전체를 훑는 비용만 남는다.
     */
    public record ShowcaseRail(List<CompanySummary> cards, int candidateCount) {
    }

    // 세 레일 공통: 삭제되지 않고 공개된 회사만. 공개 검색과 같은 가시성 규칙을 미러한다.
    // 검색과 달리 company_search_document 는 조인하지 않는다 — 색인이 밀렸다는 이유로
    // 관리자가 띄운 회사가 메인에서 사라지면 안 된다. 최신 레일에도 같은 근거가 적용된다:
    // 방금 등록한 회사가 색인 지연으로 '최근 등록'에서 빠지면 그 지면의 의미가 사라진다.
    private static final String VISIBLE = " c.deleted = false AND c.visibility = 'PUBLIC' ";

    // 자르기 전 후보 총수를 같은 쿼리에서 얻는다(윈도우 함수). 초과 감지 때문에 count 쿼리를 한 번 더
    // 던지지 않기 위한 것으로, LIMIT 이전에 평가되므로 잘린 뒤에도 총수는 그대로다.
    private static final String CANDIDATE_COUNT = ", count(*) OVER () AS candidate_count ";

    private static final String SPOTLIGHT_SQL = """
            SELECT %s %s
              FROM company c
             WHERE %s
               AND c.spotlight = true
             ORDER BY c.spotlight_order ASC, c.id DESC
             LIMIT :slots
            """.formatted(CompanyCardSql.CARD_COLUMNS, CANDIDATE_COUNT, VISIBLE);

    // 최근 등록 레일. 큐레이션 플래그를 보지 않는 순수 시간순이라 조건이 가시성뿐이다.
    //
    // 이 레일이 공개 목록/검색(CompanySearchRepository)을 재사용하지 않는 이유는 정렬 때문이다. 그쪽 기본
    // 정렬(spotlight → featured → 최신)은 "어떤 검색어·패싯을 넣어도 관리자가 띄운 회사를 상위로"라는
    // 운영 규칙이다. 그런데 메인 상단은 이미 스팟라이트·추천 레일이 그 노출을 끝낸 자리여서,
    // 같은 정렬을 바로 아래에 한 번 더 적용하면 방금 본 회사가 같은 순서로 재등장한다.
    // 정렬 파라미터로 끄는 대신 지면을 나눈다 — 그 규칙은 목록/검색 화면에 그대로 남고, 여기는 최신순만 한다.
    //
    // 위 두 레일과 달리 CANDIDATE_COUNT 를 싣지 않는다. 후보가 공개 회사 전체라 count(*) OVER() 가
    // LIMIT 보다 먼저 전체를 훑는데, 이 레일에는 그 값을 읽을 사람이 없다 — 초과가 정상 상태라 경고를
    // 붙이지 않고, 응답에도 나가지 않는다. 회사 수에 비례해 커지는 비용을 아무도 안 쓰는 값에 낼 이유가 없다.
    // ('더보기' 노출 조건이 나중에 필요해지면 총수 대신 slots + 1 건을 조회해 판단한다 — 전체 집계 없이
    //  "자리 밖에 더 있는가"만 알면 되고, 그 비용은 인덱스 스캔 한 행이다.)
    private static final String LATEST_SQL = """
            SELECT %s
              FROM company c
             WHERE %s
             ORDER BY c.created_at DESC, c.id DESC
             LIMIT :slots
            """.formatted(CompanyCardSql.CARD_COLUMNS, VISIBLE);

    private static final String FEATURED_SQL = """
            SELECT %s %s
              FROM company c
             WHERE %s
               AND c.featured = true
             ORDER BY c.created_at DESC, c.id DESC
             LIMIT :slots
            """.formatted(CompanyCardSql.CARD_COLUMNS, CANDIDATE_COUNT, VISIBLE);

    /**
     * 스팟라이트 레일. 관리자가 고정(pin)한 회사만, {@code spotlight_order} 오름차순으로 온다.
     *
     * <p>고정 수가 자리보다 많으면 순서가 뒤인 회사는 노출되지 않는다(호출부가 경고 로그로 감지한다).
     * 순서는 관리자가 정하는 값이라 자리 수 안으로 맞추는 것도 관리자 몫이다.
     */
    public ShowcaseRail findSpotlight(int slots) {
        return queryRail(SPOTLIGHT_SQL, slots);
    }

    /**
     * 추천 레일. 순수 관리자 큐레이션({@code company.featured})이다.
     */
    public ShowcaseRail findFeatured(int slots) {
        return queryRail(FEATURED_SQL, slots);
    }

    /**
     * 최근 등록 레일. 앞의 두 레일과 달리 <b>후보 초과가 정상 상태</b>다 — 후보가 공개 회사 전체라
     * 회사가 자리 수보다 많아지는 순간부터 항상 초과이고, 그게 이 레일이 의도한 동작이다.
     * 그래서 초과를 셀 이유가 없고, {@link ShowcaseRail} 이 아니라 카드 목록만 반환한다.
     */
    public List<CompanySummary> findLatest(int slots) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("slots", slots);
        return jdbc.query(LATEST_SQL, params, CompanyCardSql.ROW_MAPPER)
                .stream().map(CompanySummary::fromPublic).toList();
    }

    private ShowcaseRail queryRail(String sql, int slots) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("slots", slots);

        return jdbc.query(sql, params, rs -> {
            List<CompanySummary> cards = new ArrayList<>();
            int candidateCount = 0;
            int row = 0;
            while (rs.next()) {
                cards.add(CompanySummary.fromPublic(CompanyCardSql.ROW_MAPPER.mapRow(rs, row++)));
                // 모든 행에 같은 값이 실려 온다. 0 건이면 루프를 안 타므로 초기값 0 이 그대로 맞다.
                candidateCount = rs.getInt("candidate_count");
            }
            return new ShowcaseRail(cards, candidateCount);
        });
    }
}
