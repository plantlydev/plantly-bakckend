package project.plantly.global.seed;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시드 완료 표식.
 *
 * <p>멱등 판정을 "C01 이 있는가"로 하면 시드가 중간에 깨졌을 때가 문제다. C01 은 가장 먼저 만들어지므로
 * 그 뒤(계약 케이스·초안·패딩·즐겨찾기·재색인) 어디서 실패해도 다음 기동은 "이미 시드됨"으로 보고 반쪽짜리
 * DB 를 그대로 통과시킨다. 그래서 <b>모든 단계가 끝난 뒤에</b> 따로 표식을 남기고, 그 표식이 없으면
 * 완료로 보지 않는다 — 판정 기준이 "첫 행이 있다"에서 "마지막까지 갔다"로 바뀐다.
 *
 * <p>테이블은 seed 프로파일에서만 만든다. 엔티티가 아니라 시드 러너의 상태라서 도메인 모델에 넣지 않았고,
 * 리셋 시에는 {@code SeedResetter} 의 보존 목록에 없으므로 함께 비워진다 — 비워지면 자동으로 "미완료"가
 * 되어 다시 심는 것이 맞다.
 */
@Component
@Profile(SeedConfig.PROFILE)
@RequiredArgsConstructor
public class SeedState {

    private final JdbcTemplate jdbcTemplate;

    /** 표식 테이블 보장. 리셋보다 먼저 불러야 TRUNCATE 대상에 함께 들어간다. */
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS seed_state (
                    id           smallint    PRIMARY KEY,
                    completed_at timestamptz NOT NULL
                )
                """);
    }

    public boolean completed() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM seed_state WHERE id = 1", Long.class);
        return count != null && count > 0;
    }

    public void markCompleted() {
        jdbcTemplate.update("""
                INSERT INTO seed_state (id, completed_at) VALUES (1, now())
                     ON CONFLICT (id) DO UPDATE SET completed_at = excluded.completed_at
                """);
    }
}
