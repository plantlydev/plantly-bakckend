package project.plantly.companyTest.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.category.Category;
import project.plantly.domain.company.certification.Certification;
import project.plantly.domain.company.certification.CertificationType;
import project.plantly.domain.company.country.Continent;
import project.plantly.domain.company.country.Country;
import project.plantly.domain.company.domesticRegion.DomesticRegion;
import project.plantly.domain.company.domesticRegion.RegionLevel;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyContact;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.CompanyEquipment;
import project.plantly.domain.company.entity.CompanyImage;
import project.plantly.domain.company.entity.CompanyMaterial;
import project.plantly.domain.company.entity.CompanyProjectReference;
import project.plantly.domain.company.entity.CompanyTag;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.entity.link.CompanyCertification;
import project.plantly.domain.company.entity.link.CompanyCountry;
import project.plantly.domain.company.entity.link.CompanyDomesticRegion;
import project.plantly.domain.company.entity.link.CompanyIndustry;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.TrlLevel;
import project.plantly.domain.company.industry.Industry;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 인수 테스트용 회사 애그리거트 영속화 시더.
// 실제 DB에 본체 + 멤버 + 부속(대표 연락처/레퍼런스/표지 썸네일/갤러리/소재·장비·태그/링크 마스터)을 저장해
// CompanyAggregateLoader 가 조립하는 '진짜' 응답을 인수 테스트에서 검증할 수 있게 한다.
//
// @Transactional 이라 각 seed 메서드는 호출 즉시 커밋된다 → 별도 스레드(RANDOM_PORT 서버)의 조회 요청이 데이터를 본다.
// 단언에서 재사용할 결정적 값은 public static final 상수로 노출한다.
@Component
public class CompanyAggregateSeeder {

    // ----- 시드된 '발행 회사(seedPublishedCompany)'의 결정적 값 (인수 테스트 단언에서 참조) -----
    public static final String COMPANY_NAME = "플랜틀리테크";
    public static final String CEO_NAME = "김대표";
    public static final String BUSINESS_NUMBER = "1234567890";
    public static final LocalDate ESTABLISHMENT_DATE = LocalDate.of(2018, 3, 2);
    public static final String BRAND_COLOR = "#2E7D32";
    // 동영상은 노출 게이트 없이 저장값 그대로 공개된다(CompanyQueryAcceptanceTest 가 확인).
    public static final String VIDEO_URL = "https://youtu.be/demo";
    // 로고와 다른 URL 이어야 한다 — 카드/상세가 두 자리를 뒤바꿔 채우면 단언이 잡아낸다.
    public static final String COVER_IMAGE_URL = "https://cdn.plantly.test/cover.png";

    public static final String REP_CONTACT_NAME = "이담당";
    public static final String REP_REFERENCE_TITLE = "스마트팩토리 구축";
    public static final String REP_THUMBNAIL_URL = "https://cdn.plantly.test/ref/cover.png";

    public static final String MATERIAL_NAME = "스테인리스";
    public static final String EQUIPMENT_NAME = "CNC 선반";
    public static final String TAG_NAME = "정밀";
    public static final String CATEGORY_NAME = "정밀가공";
    public static final String CERTIFICATION_NAME = "ISO 9001";
    public static final String COUNTRY_NAME_KO = "대한민국";
    public static final String REGION_NAME = "서울특별시";
    // 자식이 없는 광역시라 short/display 가 같다. 부모명이 붙는 표기는 시군구에서만 갈린다.
    public static final String REGION_DISPLAY_NAME = "서울";
    public static final String INDUSTRY_NAME = "기계";

    @PersistenceContext
    private EntityManager em;

    // 유저 자가등록 + 소유자 연동 + 모든 부속을 갖춘 '발행 상태(미삭제)' 회사. 공개/소유자 조회 단언의 기준 데이터.
    @Transactional
    public Long seedPublishedCompany(Long ownerUserId) {
        Company company = Company.createByUser(
                ownerUserId, BUSINESS_NUMBER, COMPANY_NAME, CEO_NAME,
                ESTABLISHMENT_DATE, Address.of("06236", "서울 강남구 테헤란로 1", null, "10층"),
                "https://plantly.test", "https://cdn.plantly.test/logo.png", COVER_IMAGE_URL,
                "정밀 부품 전문", "정밀 가공 20년 경력의 부품 제조사입니다.",
                TrlLevel.MASS_PRODUCTION, VIDEO_URL, "2주", "유선 AS 지원",
                PricingType.CONSULTATION, BRAND_COLOR);
        company.verify();
        company.feature();
        em.persist(company);

        attachOwner(company, ownerUserId);
        attachChildren(company);
        attachLinks(company);
        return company.getId();
    }

    // 소프트 삭제된 회사(소유자 연동 유지). 공개 조회는 404, 소유자/관리자 조회는 200 으로 보여야 함을 검증하는 데 쓴다.
    @Transactional
    public Long seedDeletedCompany(Long ownerUserId) {
        Company company = Company.createByUser(
                ownerUserId, "9998887776", "삭제된회사", "박대표",
                ESTABLISHMENT_DATE, Address.of("06236", "서울 강남구 봉은사로 2", null, "5층"),
                null, "https://cdn.plantly.test/logo2.png", null,
                null, null, null, null, null, null, null, null);
        company.delete();
        em.persist(company);
        attachOwner(company, ownerUserId);
        return company.getId();
    }

    // 관리자 대신등록 + 소유자 미연동(unclaimed) 회사. 관리자 조회가 미연동 회사도 전부 보여줌을 검증하는 데 쓴다.
    @Transactional
    public Long seedAdminRegisteredCompany(Long adminId) {
        Company company = Company.createByAdmin(
                adminId, "5554443332", "관리자등록회사", "최대표",
                ESTABLISHMENT_DATE, Address.of("06236", "서울 강남구 도산대로 3", null, "3층"),
                null, "https://cdn.plantly.test/logo3.png", null,
                null, null, null, null, null, null, null, null);
        em.persist(company);
        // 미연동: CompanyMember 를 만들지 않는다 (소유자 없음).
        return company.getId();
    }

    // 사용 가능한(VERIFIED·미소비·미만료) 선행 인증. 임시저장은 이 인증 1건당 1개라, 초안 인수 테스트가
    // 실제 verificationId 를 손에 쥐고 시작하게 한다. 국세청 호출 없이 통과 상태로 바로 영속화한다.
    @Transactional
    public Long seedUsableVerification(Long userId) {
        CompanyVerification verification = CompanyVerification.issue(
                userId, BUSINESS_NUMBER, CEO_NAME, ESTABLISHMENT_DATE, LocalDateTime.now(), Duration.ofMinutes(30));
        em.persist(verification);
        return verification.getId();
    }

    // 주어진 회사의 등록에 소비된(CONSUMED) 인증. 번호는 그 회사의 사업자번호를 따른다 — 초안 저장 거절 판정이
    // "소비한 회사가 살아 있는가" 라서, 실제 회사 행(삭제 여부 포함)에 묶여 있어야 판정이 재현된다.
    @Transactional
    public Long seedConsumedVerification(Long userId, Long companyId) {
        Company company = em.find(Company.class, companyId);
        CompanyVerification verification = CompanyVerification.issue(
                userId, company.getBusinessNumber(), CEO_NAME, ESTABLISHMENT_DATE, LocalDateTime.now(), Duration.ofMinutes(30));
        verification.consume(companyId);
        em.persist(verification);
        return verification.getId();
    }

    private void attachOwner(Company company, Long ownerUserId) {
        em.persist(CompanyMember.owner(company.getId(), ownerUserId));
    }

    private void attachChildren(Company company) {
        // 연락처: 대표 1건 + 비대표 1건
        CompanyContact rep = new CompanyContact(company, REP_CONTACT_NAME, "영업팀장", "01022223333", "rep@plantly.test", 0);
        rep.markAsRepresentative();
        em.persist(rep);
        em.persist(new CompanyContact(company, "정사원", "사원", "01044445555", "staff@plantly.test", 1));

        // 프로젝트 레퍼런스: 대표 1건 + 표지 후보 이미지 2장(displayOrder 0,1 → 표지=0번 = REP_THUMBNAIL_URL)
        CompanyProjectReference ref = new CompanyProjectReference(
                company, REP_REFERENCE_TITLE, "불량률 30% 감소", "A제조", "2022-2023", 0);
        ref.markAsRepresentative();
        em.persist(ref);
        em.persist(CompanyImage.ofProject(ref, REP_THUMBNAIL_URL, 0));
        em.persist(CompanyImage.ofProject(ref, "https://cdn.plantly.test/ref/2.png", 1));

        // 갤러리 이미지(레퍼런스 미연결 = projectReference null): DETAIL 타입 2장
        em.persist(CompanyImage.ofCompany(company, "https://cdn.plantly.test/gallery/1.png", ImageType.DETAIL, 0));
        em.persist(CompanyImage.ofCompany(company, "https://cdn.plantly.test/gallery/2.png", ImageType.DETAIL, 1));

        // 소재 / 장비 / 태그
        em.persist(new CompanyMaterial(company, MATERIAL_NAME, 0));
        em.persist(new CompanyMaterial(company, "알루미늄", 1));
        em.persist(new CompanyEquipment(company, EQUIPMENT_NAME, 0));
        em.persist(new CompanyEquipment(company, "5축 머시닝센터", 1));
        em.persist(new CompanyTag(company, TAG_NAME, 0));
        em.persist(new CompanyTag(company, "자동화", 1));
    }

    private void attachLinks(Company company) {
        Category category = Category.createRoot(CATEGORY_NAME, "CAT-001", "https://cdn.plantly.test/cat.png", "정밀가공 카테고리", 0);
        em.persist(category);
        em.persist(new CompanyCategory(company, category, 0));

        Certification certification = Certification.create(CERTIFICATION_NAME, "iso-9001", CertificationType.MANAGEMENT_SYSTEM, 0);
        em.persist(certification);
        em.persist(new CompanyCertification(company, certification, 0));

        Country country = Country.create("KR", "KOR", "410", COUNTRY_NAME_KO, "South Korea", Continent.ASIA);
        em.persist(country);
        em.persist(new CompanyCountry(company, country, 0));

        DomesticRegion region = DomesticRegion.create("1100000000", REGION_NAME, "서울", REGION_DISPLAY_NAME, RegionLevel.SIDO, null);
        em.persist(region);
        em.persist(new CompanyDomesticRegion(company, region, 0));

        Industry industry = Industry.create(INDUSTRY_NAME, "IND-001", "https://cdn.plantly.test/ind.png", "기계 산업", 0);
        em.persist(industry);
        em.persist(new CompanyIndustry(company, industry, 0));
    }
}
