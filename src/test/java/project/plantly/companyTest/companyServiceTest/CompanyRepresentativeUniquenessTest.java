package project.plantly.companyTest.companyServiceTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.companyTest.support.PostgresContainerTest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ContactRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ReferenceRequest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyContact;
import project.plantly.domain.company.entity.CompanyProjectReference;
import project.plantly.domain.company.repository.CompanyContactRepository;
import project.plantly.domain.company.repository.CompanyProjectReferenceRepository;
import project.plantly.domain.company.service.CompanyChildWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대표 연락처·대표 레퍼런스 "회사당 1건" 의 DB 최종 방어선 — 실제 Postgres 에서만 의미가 있는 테스트.
 *
 * <p>평소 이 불변식은 앱이 지킨다: 쓰기 경로가 1건만 받아 그 건을 대표로 표시하고({@code CompanyChildWriter}),
 * 동시 PUT 은 회사 행 락이 직렬화한다({@code CompanyCollectionReplaceConcurrencyTest}). 이 테스트는 그 앱 로직을
 * 우회한 쓰기(락을 안 거치는 새 경로, 수동 SQL 등)가 와도 DB 의 부분 유니크 인덱스가 대표 2건째를 거부하는지 고정한다.
 *
 * <p>또 하나 — 인덱스가 생기면 교체(PUT)의 쓰기 순서가 중요해진다. 파생 delete 는 DELETE 를 flush 까지 미루는데
 * PK 가 IDENTITY 라 새 행 INSERT 는 persist 즉시 나간다. 삭제를 먼저 flush 하지 않으면 "기존 대표를 새 대표로 바꾸는"
 * 평범한 수정이 옛 대표가 아직 남은 채 INSERT 되어 인덱스에 물린다. 교체 테스트가 그걸 지킨다.
 */
@Transactional
@DisplayName("대표 연락처·레퍼런스 유일성: 회사당 대표 1건 (Postgres 부분 인덱스)")
class CompanyRepresentativeUniquenessTest extends PostgresContainerTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private CompanyChildWriter childWriter;

    @Autowired
    private CompanyContactRepository contactRepository;

    @Autowired
    private CompanyProjectReferenceRepository referenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("부분 유니크 인덱스 두 개가 실제로 만들어져 있다")
    void partialUniqueIndexesExist() {
        @SuppressWarnings("unchecked")
        List<String> indexNames = em.createNativeQuery(
                        "SELECT indexname FROM pg_indexes "
                                + "WHERE tablename IN ('company_contact', 'company_project_reference')")
                .getResultList();

        assertThat(indexNames)
                .contains("ux_company_contact_representative", "ux_company_project_reference_representative");
    }

    @Test
    @DisplayName("한 회사에 대표 연락처를 두 건 넣으면 DB 가 거부한다")
    void rejectsSecondRepresentativeContact() {
        Company company = persistCompany();
        insertContact(company, "첫대표", true);

        assertThatThrownBy(() -> insertContact(company, "둘째대표", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_company_contact_representative");
    }

    @Test
    @DisplayName("한 회사에 대표 레퍼런스를 두 건 넣으면 DB 가 거부한다")
    void rejectsSecondRepresentativeReference() {
        Company company = persistCompany();
        insertReference(company, "첫대표", true);

        assertThatThrownBy(() -> insertReference(company, "둘째대표", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_company_project_reference_representative");
    }

    @Test
    @DisplayName("대표가 아닌 행은 여러 건이어도, 다른 회사의 대표와도 겹치지 않는다")
    void allowsNonRepresentativesAndOtherCompanies() {
        Company company = persistCompany();
        Company other = persistCompany();
        insertContact(company, "대표", true);
        insertReference(company, "대표", true);

        // 추후 다건 허용('더보기') 확장 시의 비대표 행 — 인덱스는 representative=true 만 본다.
        assertThatCode(() -> {
            insertContact(company, "비대표1", false);
            insertContact(company, "비대표2", false);
            insertReference(company, "비대표1", false);
            insertReference(company, "비대표2", false);
        }).doesNotThrowAnyException();

        // 유일성 범위는 회사 단위다.
        assertThatCode(() -> {
            insertContact(other, "다른회사대표", true);
            insertReference(other, "다른회사대표", true);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기존 대표 연락처를 교체(PUT)하면 새 연락처 1건이 대표로 남는다")
    void replaceExistingRepresentativeContact() {
        Company company = persistCompany();
        childWriter.replaceContacts(company, List.of(contact("옛대표")));
        em.flush();

        // 삭제가 INSERT 보다 늦게 나가면 옛 대표가 남은 채 새 대표가 INSERT 되어 여기서 인덱스가 문다.
        childWriter.replaceContacts(company, List.of(contact("새대표")));
        em.flush();

        assertThat(jdbcTemplate.queryForList(
                "select contact_name from company_contact where company_id = ? and representative",
                String.class, company.getId()))
                .containsExactly("새대표");
    }

    @Test
    @DisplayName("기존 대표 레퍼런스를 교체(PUT)하면 새 레퍼런스 1건이 대표로 남는다(딸린 이미지 포함)")
    void replaceExistingRepresentativeReference() {
        Company company = persistCompany();
        childWriter.replaceReferences(company, List.of(reference("옛대표", "https://img.test/old.png")));
        em.flush();

        childWriter.replaceReferences(company, List.of(reference("새대표", "https://img.test/new.png")));
        em.flush();

        assertThat(jdbcTemplate.queryForList(
                "select project_title from company_project_reference where company_id = ? and representative",
                String.class, company.getId()))
                .containsExactly("새대표");
        assertThat(jdbcTemplate.queryForList(
                "select image_url from company_image where company_id = ? and project_reference_id is not null",
                String.class, company.getId()))
                .containsExactly("https://img.test/new.png");
    }

    // 쓰기 경로(1건 제한·대표 표시)를 우회해 DB 제약만 때린다.
    private void insertContact(Company company, String name, boolean representative) {
        CompanyContact contact = new CompanyContact(company, name, null, "010-0000-0000", "c@plantly.test", 0);
        if (representative) {
            contact.markAsRepresentative();
        }
        contactRepository.saveAndFlush(contact);
    }

    private void insertReference(Company company, String title, boolean representative) {
        CompanyProjectReference reference = new CompanyProjectReference(company, title, null, null, null, 0);
        if (representative) {
            reference.markAsRepresentative();
        }
        referenceRepository.saveAndFlush(reference);
    }

    private ContactRequest contact(String name) {
        return new ContactRequest(name, null, "010-0000-0000", "c@plantly.test");
    }

    private ReferenceRequest reference(String title, String imageUrl) {
        return new ReferenceRequest(title, null, null, null, List.of(imageUrl));
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
