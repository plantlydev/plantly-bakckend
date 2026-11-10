package project.plantly.companyTest.companyServiceTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.companyTest.support.CompanyCreateRequestBuilder;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyEquipment;
import project.plantly.domain.company.entity.CompanyMaterial;
import project.plantly.domain.company.entity.CompanyTag;
import project.plantly.domain.company.repository.CompanyEquipmentRepository;
import project.plantly.domain.company.repository.CompanyMaterialRepository;
import project.plantly.domain.company.repository.CompanyTagRepository;
import project.plantly.domain.company.service.CompanyChildWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 태그·소재·장비 이름 목록의 "회사당 같은 이름 1건" — 실제 Postgres 에서만 의미가 있는 테스트.
 *
 * <p>정책(2026-11-10): 앞뒤 공백을 걷어내고 대소문자를 무시해 같으면 같은 이름이다("AI" = " ai ").
 * <ul>
 *   <li><b>앱</b> — 쓰기 경로({@code CompanyChildWriter})가 이름을 trim 해 저장하고, 같은 이름이 또 오면 첫 번째만
 *       남기고 조용히 합친다(순서 유지). 링크 교체의 중복 제거와 같은 태도.</li>
 *   <li><b>DB</b> — {@code (company_id, lower(btrim(이름)))} 유니크 인덱스가 앱을 우회한 쓰기도 막는다.</li>
 * </ul>
 * 인덱스가 생기면 교체(PUT)의 쓰기 순서가 중요해진다 — "기존 태그는 그대로 두고 하나 추가" 하는 흔한 교체가
 * 삭제보다 INSERT 가 먼저 나가면 인덱스에 물린다({@code CompanyRepresentativeUniquenessTest} 와 같은 함정).
 */
@Transactional
@DisplayName("태그·소재·장비 이름 유일성: 회사당 같은 이름 1건 (공백·대소문자 무시)")
class CompanyNameListUniquenessTest extends PostgresContainerTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired private CompanyChildWriter childWriter;
    @Autowired private CompanyTagRepository tagRepository;
    @Autowired private CompanyMaterialRepository materialRepository;
    @Autowired private CompanyEquipmentRepository equipmentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("이름 유니크 인덱스 세 개가 실제로 만들어져 있다")
    void uniqueIndexesExist() {
        @SuppressWarnings("unchecked")
        List<String> indexNames = em.createNativeQuery(
                        "SELECT indexname FROM pg_indexes "
                                + "WHERE tablename IN ('company_tag', 'company_material', 'company_equipment')")
                .getResultList();

        assertThat(indexNames)
                .contains("ux_company_tag_name", "ux_company_material_name", "ux_company_equipment_name");
    }

    // 위반 한 번이면 Postgres 트랜잭션이 aborted 되어 다음 문장이 다른 예외로 실패하므로, 테이블마다 테스트를 나눈다.

    @Test
    @DisplayName("앱을 우회해 공백·대소문자만 다른 태그를 넣어도 DB 가 거부한다")
    void rejectsTagVariantAtDb() {
        Company company = persistCompany();
        tagRepository.saveAndFlush(new CompanyTag(company, "AI", 0));

        assertThatThrownBy(() -> tagRepository.saveAndFlush(new CompanyTag(company, " ai ", 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_company_tag_name");
    }

    @Test
    @DisplayName("앱을 우회해 대소문자만 다른 소재를 넣어도 DB 가 거부한다")
    void rejectsMaterialVariantAtDb() {
        Company company = persistCompany();
        materialRepository.saveAndFlush(new CompanyMaterial(company, "Steel", 0));

        assertThatThrownBy(() -> materialRepository.saveAndFlush(new CompanyMaterial(company, "steel", 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_company_material_name");
    }

    @Test
    @DisplayName("앱을 우회해 공백·대소문자만 다른 장비를 넣어도 DB 가 거부한다")
    void rejectsEquipmentVariantAtDb() {
        Company company = persistCompany();
        equipmentRepository.saveAndFlush(new CompanyEquipment(company, "CNC", 0));

        assertThatThrownBy(() -> equipmentRepository.saveAndFlush(new CompanyEquipment(company, "cnc ", 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_company_equipment_name");
    }

    @Test
    @DisplayName("다른 회사라면 같은 이름이어도 된다")
    void sameNameAcrossCompaniesAllowed() {
        Company company = persistCompany();
        Company other = persistCompany();
        tagRepository.saveAndFlush(new CompanyTag(company, "스마트팜", 0));

        assertThatCode(() -> tagRepository.saveAndFlush(new CompanyTag(other, "스마트팜", 0)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("등록 요청에 같은 이름이 섞여 오면 trim 후 첫 번째만 남기고 순서대로 저장한다")
    void createMergesDuplicatesKeepingFirst() {
        Company company = persistCompany();

        childWriter.write(company, CompanyCreateRequestBuilder.aRequest()
                .nameLists(
                        List.of("Steel", " steel", "구리"),
                        List.of("CNC ", "레이저", "cnc"),
                        List.of("스마트팜", " 스마트팜 ", "AI", "ai", "팜"))
                .build());
        em.flush();

        assertThat(names("company_material", "material_name", company)).containsExactly("Steel", "구리");
        assertThat(names("company_equipment", "equipment_name", company)).containsExactly("CNC", "레이저");
        assertThat(names("company_tag", "tag_name", company)).containsExactly("스마트팜", "AI", "팜");
        assertThat(jdbcTemplate.queryForList(
                "select display_order from company_tag where company_id = ? order by display_order",
                Integer.class, company.getId()))
                .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("교체(PUT) 요청에 같은 이름이 섞여 와도 합쳐서 저장한다")
    void replaceMergesDuplicates() {
        Company company = persistCompany();

        childWriter.replaceTags(company, List.of("AI", "ai ", "팜"));
        childWriter.replaceMaterials(company, List.of("구리", "구리"));
        childWriter.replaceEquipment(company, List.of(" CNC", "Cnc"));
        em.flush();

        assertThat(names("company_tag", "tag_name", company)).containsExactly("AI", "팜");
        assertThat(names("company_material", "material_name", company)).containsExactly("구리");
        assertThat(names("company_equipment", "equipment_name", company)).containsExactly("CNC");
    }

    @Test
    @DisplayName("기존 이름을 그대로 두고 하나만 추가하는 교체가 성공한다")
    void replaceKeepingExistingNamesSucceeds() {
        Company company = persistCompany();
        childWriter.replaceTags(company, List.of("AI", "팜"));
        childWriter.replaceMaterials(company, List.of("구리"));
        childWriter.replaceEquipment(company, List.of("CNC"));
        em.flush();

        // 삭제가 INSERT 보다 늦게 나가면 기존 이름이 남은 채 같은 이름이 INSERT 되어 인덱스가 문다.
        childWriter.replaceTags(company, List.of("AI", "팜", "로봇"));
        childWriter.replaceMaterials(company, List.of("구리", "알루미늄"));
        childWriter.replaceEquipment(company, List.of("CNC", "레이저"));
        em.flush();

        assertThat(names("company_tag", "tag_name", company)).containsExactly("AI", "팜", "로봇");
        assertThat(names("company_material", "material_name", company)).containsExactly("구리", "알루미늄");
        assertThat(names("company_equipment", "equipment_name", company)).containsExactly("CNC", "레이저");
    }

    private List<String> names(String table, String column, Company company) {
        return jdbcTemplate.queryForList(
                "select " + column + " from " + table + " where company_id = ? order by display_order",
                String.class, company.getId());
    }

    private Company persistCompany() {
        Company company = Company.createByUser(1L, null, "회사", "대표", null,
                Address.of("06236", "서울 강남구", null, "테헤란로 1"), null, "logo", null,
                null, null, null, null, null, null, null, null);
        em.persist(company);
        em.flush();
        return company;
    }
}
