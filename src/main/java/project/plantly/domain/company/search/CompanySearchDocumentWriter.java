package project.plantly.domain.company.search;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검색 동기화 writer. 회사 애그리거트(본체 + 자식 + 카테고리)로부터 비정규화 검색 도큐먼트
 * ({@code company_search_document})와 카테고리 조상 closure({@code company_category_closure})를
 * 재생성한다. 등록/수정 트랜잭션에서 호출하며, 기존 데이터 백필({@link #backfillAll()})도 겸한다.
 *
 * <p>검색 테이블은 {@code @Entity} 매핑 없이 네이티브 SQL 로만 다룬다(ddl-auto 권한과 분리). 자식·링크는
 * 호출 직전 JPA 로 저장돼 영속성 컨텍스트에만 있을 수 있으므로, 네이티브가 보도록 먼저 {@code flush} 한다.
 * ES 전환 시 이 컴포넌트만 "문서 빌드 + 색인 전송"으로 교체된다(읽기 포트와 별개 seam).
 */
@Component
public class CompanySearchDocumentWriter {

    @PersistenceContext
    private EntityManager em;

    // Company 스칼라 6 + 자식 string_agg 4(레퍼런스/장비/소재/태그) + search_all 을 만들어 upsert. %s 자리에 대상 필터를 끼운다.
    // 도큐먼트 address 컬럼은 도로명+지번을 concat_ws 로 합쳐 넣는다 — 고급검색 "주소"가 도로명/지번 어느 쪽 조각으로도 매칭되게 한다.
    // 태그는 전용 컬럼 없이 search_all 에만 접어넣는다 — 통합 키워드 매칭 대상이지 고급검색(필드별) 필드가 아니므로.
    // 인스타 해시태그식 정확매칭 탐색축은 태그 정규화 선행이라 별개(차후).
    private static final String UPSERT_DOCUMENT = """
            INSERT INTO company_search_document (
                company_id, company_name, intro_title, content, ceo_name, address, detail_address,
                reference_text, equipment_text, material_text, search_all, updated_at)
            SELECT c.id, c.company_name, c.intro_title, c.content, c.ceo_name,
                   concat_ws(' ', c.road_address, c.jibun_address), c.detail_address,
                   r.txt, e.txt, m.txt,
                   concat_ws(' ', c.company_name, c.intro_title, c.content, c.ceo_name,
                                  c.road_address, c.jibun_address,
                                  c.detail_address, r.txt, e.txt, m.txt, t.txt),
                   now()
            FROM company c
            LEFT JOIN (SELECT company_id, string_agg(concat_ws(' ', project_title, achievements, partners), ' ') AS txt
                       FROM company_project_reference GROUP BY company_id) r ON r.company_id = c.id
            LEFT JOIN (SELECT company_id, string_agg(equipment_name, ' ') AS txt
                       FROM company_equipment GROUP BY company_id) e ON e.company_id = c.id
            LEFT JOIN (SELECT company_id, string_agg(material_name, ' ') AS txt
                       FROM company_material GROUP BY company_id) m ON m.company_id = c.id
            LEFT JOIN (SELECT company_id, string_agg(tag_name, ' ') AS txt
                       FROM company_tag GROUP BY company_id) t ON t.company_id = c.id
            WHERE %s
            ON CONFLICT (company_id) DO UPDATE SET
                company_name = EXCLUDED.company_name, intro_title = EXCLUDED.intro_title,
                content = EXCLUDED.content, ceo_name = EXCLUDED.ceo_name, address = EXCLUDED.address,
                detail_address = EXCLUDED.detail_address, reference_text = EXCLUDED.reference_text,
                equipment_text = EXCLUDED.equipment_text, material_text = EXCLUDED.material_text,
                search_all = EXCLUDED.search_all, updated_at = EXCLUDED.updated_at
            """;

    // 카테고리 조상 closure 적재. company_category 의 각 카테고리에서 parent_id 체인을 root 까지 펼친다.
    private static final String INSERT_CLOSURE = """
            INSERT INTO company_category_closure (company_id, category_id)
            WITH RECURSIVE anc AS (
                SELECT cc.company_id, cat.id AS category_id, cat.parent_id
                FROM company_category cc
                JOIN category cat ON cat.id = cc.category_id
                WHERE %s
                UNION ALL
                SELECT a.company_id, p.id, p.parent_id
                FROM anc a
                JOIN category p ON p.id = a.parent_id
            )
            SELECT DISTINCT company_id, category_id FROM anc
            ON CONFLICT DO NOTHING
            """;

    /** 단건(등록/수정 훅): 해당 회사의 도큐먼트·closure 를 재생성한다. */
    @Transactional
    public void write(Long companyId) {
        em.flush(); // 직전에 JPA 로 저장한 자식/링크를 DB 로 내려 네이티브가 보게 한다.
        em.createNativeQuery(UPSERT_DOCUMENT.formatted("c.id = :companyId"))
                .setParameter("companyId", companyId).executeUpdate();
        em.createNativeQuery("DELETE FROM company_category_closure WHERE company_id = :companyId")
                .setParameter("companyId", companyId).executeUpdate();
        em.createNativeQuery(INSERT_CLOSURE.formatted("cc.company_id = :companyId"))
                .setParameter("companyId", companyId).executeUpdate();
    }

    /** 백필: 삭제되지 않은 전체 회사의 도큐먼트·closure 를 재생성한다. */
    @Transactional
    public void backfillAll() {
        em.flush();
        em.createNativeQuery(UPSERT_DOCUMENT.formatted("c.deleted = false")).executeUpdate();
        em.createNativeQuery("DELETE FROM company_category_closure").executeUpdate();
        em.createNativeQuery(INSERT_CLOSURE.formatted("true")).executeUpdate();
    }

    /** 소프트삭제 훅(미연동): 해당 회사의 검색 흔적을 제거한다. */
    @Transactional
    public void remove(Long companyId) {
        em.createNativeQuery("DELETE FROM company_search_document WHERE company_id = :companyId")
                .setParameter("companyId", companyId).executeUpdate();
        em.createNativeQuery("DELETE FROM company_category_closure WHERE company_id = :companyId")
                .setParameter("companyId", companyId).executeUpdate();
    }
}
