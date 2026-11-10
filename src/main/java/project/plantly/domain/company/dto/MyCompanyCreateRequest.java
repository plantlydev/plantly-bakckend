package project.plantly.domain.company.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import project.plantly.domain.company.dto.CompanyCreateRequest.CertificationRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ContactRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ImageRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ReferenceRequest;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.TrlLevel;

import java.util.List;

/**
 * 유저 자가등록 요청.
 *
 * <p>관리자 등록({@link CompanyCreateRequest})과 DTO 를 나눈 이유는 <b>신원 3종(사업자번호·대표자명·개업일자)을
 * 아예 받지 않기 위해서</b>다. 이 값들은 선행 인증({@code POST /api/v1/companies/verification})에서 국세청
 * 검증을 통과한 것만 서버가 채운다. 요청 본문에 자리 자체가 없으니 "인증은 A 로 받고 저장은 B 로 하는"
 * 조작이 성립하지 않는다.
 *
 * <p>한 DTO 를 공유하면서 서버가 조용히 무시하는 방식도 가능하지만, 사용자가 보낸 대표자명이 흔적 없이
 * 버려져 디버깅이 어려워지고, ceoName 의 @NotBlank 를 풀어야 해서 관리자 경로의 검증까지 같이 약해진다.
 */
public record MyCompanyCreateRequest(
        // ===== 선행 인증 =====
        // POST /api/v1/companies/verification 이 발급한 식별자. 본인이 받은 것이어야 하고, 미사용·미만료여야 한다.
        // 메시지를 적어두는 이유는 이 폼에서 유일하게 생략돼 있었기 때문이다. 생략하면 Bean Validation 의
        // 기본 문구("널이어서는 안됩니다")가 나가는데, 나머지 30여 개 항목이 전부 우리말 안내 문구를 쓰는
        // 폼에서 이 칸만 말투가 튄다. 제약 조회 API 도 같은 문구를 그대로 내려보낸다.
        @NotNull(message = "사업자 인증 정보는 필수입니다.")
        Long verificationId,

        // ===== 본체 (신원 3종 제외) =====
        @NotBlank(message = "기업명은 필수입니다.")
        @Size(max = CompanyConstraints.COMPANY_NAME_MAX, message = "기업명은 100자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String companyName,
        // 주소 3축(우편번호/도로명/상세)은 엔티티(Address)가 NOT NULL 이라 필수다. 관리자 등록 경로와 같은 규칙이다.
        @NotBlank(message = "우편번호는 필수입니다.")
        @Pattern(regexp = CompanyConstraints.POSTAL_CODE_PATTERN, message = CompanyConstraints.POSTAL_CODE_MESSAGE)
        String postalCode,
        @NotBlank(message = "도로명 주소는 필수입니다.")
        @Size(max = CompanyConstraints.ROAD_ADDRESS_MAX, message = "도로명 주소는 200자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String roadAddress,
        @Size(max = CompanyConstraints.JIBUN_ADDRESS_MAX, message = "지번 주소는 200자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String jibunAddress,
        @NotBlank(message = "상세주소는 필수입니다.")
        @Size(max = CompanyConstraints.DETAIL_ADDRESS_MAX, message = "상세주소는 100자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String detailAddress,
        @Size(max = CompanyConstraints.URL_MAX, message = "홈페이지 주소는 255자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String website,
        // 로고는 선택이다. 없으면 프론트가 회사명 앞 두 글자로 대체 배지를 그린다.
        @Size(max = CompanyConstraints.URL_MAX, message = "로고 이미지 주소는 255자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String logoUrl,
        @Size(max = CompanyConstraints.URL_MAX, message = "커버 이미지 주소는 255자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String coverImageUrl,
        @Size(max = CompanyConstraints.INTRO_TITLE_MAX, message = "한 줄 요약은 50자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String introTitle,
        @Size(max = CompanyConstraints.CONTENT_MAX, message = "소개글은 5000자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String content,
        TrlLevel trlLevel,
        @Size(max = CompanyConstraints.URL_MAX, message = "동영상 주소는 255자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String videoUrl,
        @Size(max = CompanyConstraints.LEAD_TIME_MAX, message = "예상 리드타임은 100자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String leadTime,
        @Size(max = CompanyConstraints.AS_INFO_MAX, message = "유지보수 정보는 1000자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        String asInfo,
        PricingType pricingType,
        // 자동저장(PUT /drafts/{id})은 크기 상한(@Size, DraftChecks 그룹)만 검사하므로, 이 검증은 발행(POST /companies) 시점에만 발화한다.
        // 작성 중 초안에 반쯤 입력된 색이 담겨도 저장은 막히지 않는다.
        @Pattern(regexp = CompanyConstraints.BRAND_COLOR_PATTERN, message = CompanyConstraints.BRAND_COLOR_MESSAGE)
        String brandColor,
        CompanyVisibility visibility,

        // ===== 자식(소유) 엔티티 =====
        @Valid
        @Size(max = CompanyConstraints.CONTACTS_MAX, message = "연락처는 현재 1건만 등록할 수 있습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "연락처 항목은 비어 있을 수 없습니다.") ContactRequest> contacts,
        @Valid
        @Size(max = CompanyConstraints.DETAIL_IMAGES_MAX, message = "상세 이미지는 30장을 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "이미지 항목은 비어 있을 수 없습니다.") ImageRequest> images,
        @Valid
        @Size(max = CompanyConstraints.REFERENCES_MAX, message = "프로젝트 레퍼런스는 현재 1건만 등록할 수 있습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "레퍼런스 항목은 비어 있을 수 없습니다.") ReferenceRequest> references,
        @Size(max = CompanyConstraints.MATERIALS_MAX, message = "취급 소재는 20개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotBlank(message = "소재명은 비어 있을 수 없습니다.")
                @Size(max = CompanyConstraints.MATERIAL_NAME_MAX, message = "소재명은 50자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
                String> materialNames,
        @Size(max = CompanyConstraints.EQUIPMENTS_MAX, message = "보유 장비는 20개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotBlank(message = "장비명은 비어 있을 수 없습니다.")
                @Size(max = CompanyConstraints.EQUIPMENT_NAME_MAX, message = "장비명은 50자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
                String> equipmentNames,
        @Size(max = CompanyConstraints.TAGS_MAX, message = "태그는 10개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotBlank(message = "태그는 비어 있을 수 없습니다.")
                @Size(max = CompanyConstraints.TAG_NAME_MAX, message = "태그는 20자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
                String> tagNames,

        // ===== 링크(M:N) 엔티티 =====
        @Size(max = CompanyConstraints.CATEGORIES_MAX, message = "카테고리는 10개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "카테고리 항목은 비어 있을 수 없습니다.") Long> categoryIds,
        // @NotNull 은 리스트가 아니라 '원소'에 붙는다 — 인증을 하나도 고르지 않은 "해당 사항 없음"은
        // 정상 상태라 리스트 자체는 null/빈 배열이어도 된다. 막아야 하는 건 [null] 같은 깨진 원소뿐이다.
        @Valid
        @Size(max = CompanyConstraints.CERTIFICATIONS_MAX, message = "인증은 10개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "인증 항목은 비어 있을 수 없습니다.") CertificationRequest> certifications,
        @Size(max = CompanyConstraints.COUNTRIES_MAX, message = "대응 가능 국가는 20개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "국가 항목은 비어 있을 수 없습니다.") Long> countryIds,
        @Size(max = CompanyConstraints.DOMESTIC_REGIONS_MAX, message = "대응 가능 지역은 20개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "지역 항목은 비어 있을 수 없습니다.") Long> domesticRegionIds,
        @Size(max = CompanyConstraints.INDUSTRIES_MAX, message = "산업군은 5개를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
        List<@NotNull(message = "산업군 항목은 비어 있을 수 없습니다.") Long> industryIds
) {

    /**
     * 검증된 신원 3종을 채워 공통 등록 요청으로 바꾼다. 등록 파이프라인(정책·자식·링크·검색 동기화)은
     * 두 경로가 공유하므로, 차이를 이 한 곳에서만 흡수한다.
     *
     * <p>개업일자를 establishmentDate 슬롯에 넣는다 — 별도 컬럼을 만들면 카드·검색·응답이 매번
     * "어느 날짜를 보여줄지" 분기해야 한다. 인증 여부(businessVerified)로 값의 출처가 이미 구분된다.
     */
    public CompanyCreateRequest toCreateRequest(CompanyVerification verification) {
        return new CompanyCreateRequest(
                verification.getBusinessNumber(),
                companyName,
                verification.getCeoName(),
                verification.getBusinessStartDate(),
                postalCode, roadAddress, jibunAddress, detailAddress,
                website, logoUrl, coverImageUrl, introTitle, content, trlLevel, videoUrl, leadTime, asInfo,
                pricingType, brandColor, visibility,
                contacts, images, references, materialNames, equipmentNames, tagNames,
                categoryIds, certifications, countryIds, domesticRegionIds, industryIds);
    }
}
