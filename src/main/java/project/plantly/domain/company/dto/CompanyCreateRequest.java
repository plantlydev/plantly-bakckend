package project.plantly.domain.company.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.TrlLevel;

import java.time.LocalDate;
import java.util.List;

// 회사 등록 요청. 본체 비즈니스 필드 + 부속 10종(자식 5 / 링크 5)을 한 번에 받는다.
// 소유자(userId)/등록 경로(source)는 요청 본문이 아니라 진입점(유저 인증 principal / admin 권한)에서 결정하므로
// 이 DTO에는 담지 않는다.
//
// 검증 정책: 엔티티가 NOT NULL 인 항목은 여기서 전부 막고, 나머지는 길이·개수 상한만 건다.
// 등록이 곧 발행이므로("임시저장" 은 별도 CompanyDraft blob 이라 이 DTO 를 거치지 않는다) 필수 검증을
// 뒤로 미룰 단계가 없다. 상한 값은 CompanyConstraints 가 소유한다 — 등록·수정 세 경로가 같은 값을 써야
// 등록에서 막은 값이 수정으로 들어오지 않는다.
//
// 위반이 여러 개면 응답은 전부 담아 내려간다(ApiResponse.errors). 프론트는 각 필드 아래에 메시지를 붙이고
// 첫 항목으로 스크롤한다 — 이 폼은 필드가 30종이라 하나씩 알려주면 저장을 몇 번이나 눌러야 한다.
//
// 부속 리스트의 표시 순서(displayOrder)는 클라이언트가 보내지 않고, 서비스에서 리스트 인덱스로 부여한다.
public record CompanyCreateRequest(
        // ===== 본체 =====
        @Pattern(regexp = CompanyConstraints.BUSINESS_NUMBER_PATTERN,
                message = CompanyConstraints.BUSINESS_NUMBER_MESSAGE)
        String businessNumber,  //사업자번호
        @NotBlank(message = "기업명은 필수입니다.")
        @Size(max = CompanyConstraints.COMPANY_NAME_MAX, message = "기업명은 100자를 넘을 수 없습니다.")
        String companyName,  //기업이름
        @NotBlank(message = "대표자명은 필수입니다.")
        @Size(max = CompanyConstraints.CEO_NAME_MAX, message = "대표자명은 50자를 넘을 수 없습니다.")
        String ceoName,  //대표자
        // 미래 설립일은 오타이지 유효한 입력이 아니다. 국세청 인증 경로로 들어온 값은 이미 검증된 값이라
        // 이 제약에 걸리지 않는다.
        @PastOrPresent(message = "설립일은 오늘보다 미래일 수 없습니다.")
        LocalDate establishmentDate,  //설립일
        // 주소 3축(우편번호/도로명/상세)은 엔티티(Address)가 NOT NULL 이라 여기서 필수로 막는다.
        // 여기서 안 막으면 검증이 아니라 영속화 시점에 터져 400 이 아닌 응답이 나간다.
        @NotBlank(message = "우편번호는 필수입니다.")
        @Pattern(regexp = CompanyConstraints.POSTAL_CODE_PATTERN, message = CompanyConstraints.POSTAL_CODE_MESSAGE)
        String postalCode,  //우편번호
        @NotBlank(message = "도로명 주소는 필수입니다.")
        @Size(max = CompanyConstraints.ROAD_ADDRESS_MAX, message = "도로명 주소는 200자를 넘을 수 없습니다.")
        String roadAddress,  //도로명 주소
        // 지번만 nullable 이다 — 도로명만 있는 신주소는 지번이 없다.
        @Size(max = CompanyConstraints.JIBUN_ADDRESS_MAX, message = "지번 주소는 200자를 넘을 수 없습니다.")
        String jibunAddress,  //지번 주소(도로명만 있는 신주소는 비어있을 수 있음)
        @NotBlank(message = "상세주소는 필수입니다.")
        @Size(max = CompanyConstraints.DETAIL_ADDRESS_MAX, message = "상세주소는 100자를 넘을 수 없습니다.")
        String detailAddress,  //상세주소
        @Size(max = CompanyConstraints.URL_MAX, message = "홈페이지 주소는 255자를 넘을 수 없습니다.")
        String website,  //기업홈페이지
        // 로고는 선택이다. 없으면 프론트가 회사명 앞 두 글자로 대체 배지를 그린다.
        @Size(max = CompanyConstraints.URL_MAX, message = "로고 이미지 주소는 255자를 넘을 수 없습니다.")
        String logoUrl,  //로고 이미지
        @Size(max = CompanyConstraints.URL_MAX, message = "커버 이미지 주소는 255자를 넘을 수 없습니다.")
        String coverImageUrl,  //카드 커버 이미지(선택). 로고와 별개로 목록 카드 배경에 깔린다
        @Size(max = CompanyConstraints.INTRO_TITLE_MAX, message = "한 줄 요약은 50자를 넘을 수 없습니다.")
        String introTitle,  //한줄요약
        @Size(max = CompanyConstraints.CONTENT_MAX, message = "소개글은 5000자를 넘을 수 없습니다.")
        String content,  //소개글
        TrlLevel trlLevel,  //기술성숙도
        @Size(max = CompanyConstraints.URL_MAX, message = "동영상 주소는 255자를 넘을 수 없습니다.")
        String videoUrl,  //동영상링크
        @Size(max = CompanyConstraints.LEAD_TIME_MAX, message = "예상 리드타임은 100자를 넘을 수 없습니다.")
        String leadTime,  //예상 리드타임
        @Size(max = CompanyConstraints.AS_INFO_MAX, message = "유지보수 정보는 1000자를 넘을 수 없습니다.")
        String asInfo,  //유지보수
        PricingType pricingType,  //견적 산출방식
        // 브랜드 컬러. 메인 스팟라이트 배너 배경 등 화면 CSS 에 그대로 꽂히는 값이라 형식을 강제한다.
        // null 은 통과(미지정) — 등록 시 blank 는 비울 대상이 없으므로 허용하지 않는다.
        @Pattern(regexp = CompanyConstraints.BRAND_COLOR_PATTERN, message = CompanyConstraints.BRAND_COLOR_MESSAGE)
        String brandColor,
        CompanyVisibility visibility,  //공개 범위(null = 공개 기본)

        // ===== 자식(소유) 엔티티 =====
        // 연락처/레퍼런스는 하위 필드를 가진 컬렉션이라, 초기 버전은 대표 1건만 받는다.
        // (추후 다건 허용 + '더보기' 별도 조회로 확장 시 이 @Size 제약을 푼다)
        @Valid
        @Size(max = CompanyConstraints.CONTACTS_MAX, message = "연락처는 현재 1건만 등록할 수 있습니다.")
        List<@NotNull(message = "연락처 항목은 비어 있을 수 없습니다.") ContactRequest> contacts,
        @Valid
        @Size(max = CompanyConstraints.DETAIL_IMAGES_MAX, message = "상세 이미지는 30장을 넘을 수 없습니다.")
        List<@NotNull(message = "이미지 항목은 비어 있을 수 없습니다.") ImageRequest> images,
        @Valid
        @Size(max = CompanyConstraints.REFERENCES_MAX, message = "프로젝트 레퍼런스는 현재 1건만 등록할 수 있습니다.")
        List<@NotNull(message = "레퍼런스 항목은 비어 있을 수 없습니다.") ReferenceRequest> references,
        @Size(max = CompanyConstraints.MATERIALS_MAX, message = "취급 소재는 20개를 넘을 수 없습니다.")
        List<@NotBlank(message = "소재명은 비어 있을 수 없습니다.")
                @Size(max = CompanyConstraints.MATERIAL_NAME_MAX, message = "소재명은 50자를 넘을 수 없습니다.")
                String> materialNames,
        @Size(max = CompanyConstraints.EQUIPMENTS_MAX, message = "보유 장비는 20개를 넘을 수 없습니다.")
        List<@NotBlank(message = "장비명은 비어 있을 수 없습니다.")
                @Size(max = CompanyConstraints.EQUIPMENT_NAME_MAX, message = "장비명은 50자를 넘을 수 없습니다.")
                String> equipmentNames,
        @Size(max = CompanyConstraints.TAGS_MAX, message = "태그는 10개를 넘을 수 없습니다.")
        List<@NotBlank(message = "태그는 비어 있을 수 없습니다.")
                @Size(max = CompanyConstraints.TAG_NAME_MAX, message = "태그는 20자를 넘을 수 없습니다.")
                String> tagNames,

        // ===== 링크(M:N) 엔티티 - 기존 마스터 ID 참조 =====
        @Size(max = CompanyConstraints.CATEGORIES_MAX, message = "카테고리는 10개를 넘을 수 없습니다.")
        List<@NotNull(message = "카테고리 항목은 비어 있을 수 없습니다.") Long> categoryIds,
        // 인증만 평면 ID 가 아니다 — '기타' 를 고르면 인증명을 직접 적어 보내야 하고, 그건 마스터 ID 옆에
        // 붙어야 어느 항목의 이름인지가 정해진다. 일반 인증은 customName 없이 ID 만 담으면 된다.
        // @NotNull 은 리스트가 아니라 '원소'에 붙는다 — 인증을 하나도 고르지 않은 "해당 사항 없음"은
        // 정상 상태라 리스트 자체는 null/빈 배열이어도 된다. 막아야 하는 건 [null] 같은 깨진 원소뿐이다.
        @Valid
        @Size(max = CompanyConstraints.CERTIFICATIONS_MAX, message = "인증은 10개를 넘을 수 없습니다.")
        List<@NotNull(message = "인증 항목은 비어 있을 수 없습니다.") CertificationRequest> certifications,
        @Size(max = CompanyConstraints.COUNTRIES_MAX, message = "대응 가능 국가는 20개를 넘을 수 없습니다.")
        List<@NotNull(message = "국가 항목은 비어 있을 수 없습니다.") Long> countryIds,
        @Size(max = CompanyConstraints.DOMESTIC_REGIONS_MAX, message = "대응 가능 지역은 20개를 넘을 수 없습니다.")
        List<@NotNull(message = "지역 항목은 비어 있을 수 없습니다.") Long> domesticRegionIds,
        @Size(max = CompanyConstraints.INDUSTRIES_MAX, message = "산업군은 5개를 넘을 수 없습니다.")
        List<@NotNull(message = "산업군 항목은 비어 있을 수 없습니다.") Long> industryIds
) {

    public record ContactRequest(
            @NotBlank(message = "담당자 이름은 필수입니다.")
            @Size(max = CompanyConstraints.CONTACT_NAME_MAX, message = "담당자 이름은 50자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String contactName,
            @Size(max = CompanyConstraints.CONTACT_POSITION_MAX, message = "직위는 50자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String position,
            @Size(max = CompanyConstraints.PHONE_MAX, message = "연락처는 20자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            @Pattern(regexp = CompanyConstraints.PHONE_PATTERN, message = CompanyConstraints.PHONE_MESSAGE)
            String phone,
            @Email(message = "올바른 이메일 형식이 아닙니다.")
            @Size(max = CompanyConstraints.EMAIL_MAX, message = "이메일은 255자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String email
    ) {
    }

    /**
     * 인증 1건 선택. 마스터 목록에서 고른 인증은 {@code certificationId} 만 담고,
     * 목록에 없어 '기타'를 고른 경우에만 {@code customName} 에 직접 입력한 인증명을 담는다.
     *
     * <p>둘의 짝은 링크 엔티티가 강제한다 — customName 이 있으면 마스터는 ETC 여야 하고, 없으면 아니어야 한다.
     * 같은 '기타' 마스터를 이름만 달리해 여러 건 보낼 수 있다(중복 판정 키가 ID 가 아니라 ID+이름이다).
     */
    public record CertificationRequest(
            @NotNull(message = "인증 ID는 필수입니다.")
            Long certificationId,
            @Size(max = CompanyConstraints.CUSTOM_CERTIFICATION_NAME_MAX,
                    message = "직접 입력한 인증명은 100자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String customName
    ) {
    }

    public record ImageRequest(
            @NotBlank(message = "이미지 주소는 필수입니다.")
            @Size(max = CompanyConstraints.URL_MAX, message = "이미지 주소는 255자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String imageUrl,
            @NotNull(message = "이미지 종류는 필수입니다.")
            ImageType imageType
    ) {
    }

    // 프로젝트 레퍼런스 1건 + 그에 딸린 이미지 URL 목록.
    // displayOrder(레퍼런스 순서/이미지 순서)는 클라이언트가 보내지 않고 서버가 리스트 인덱스로 부여한다.
    public record ReferenceRequest(
            @Size(max = CompanyConstraints.PROJECT_TITLE_MAX, message = "프로젝트명은 200자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String projectTitle,
            @Size(max = CompanyConstraints.ACHIEVEMENTS_MAX, message = "주요 성과는 2000자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String achievements,
            @Size(max = CompanyConstraints.PARTNERS_MAX, message = "참여 기관은 200자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String partners,
            @Size(max = CompanyConstraints.PERIOD_MAX, message = "수행 기간은 50자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            String period,
            // 레퍼런스 1건당 이미지 장수 한도(모든 회사 공통).
            @Size(max = CompanyConstraints.REFERENCE_IMAGES_MAX, message = "레퍼런스 이미지는 10장을 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
            List<@NotBlank(message = "이미지 주소는 비어 있을 수 없습니다.")
                    @Size(max = CompanyConstraints.URL_MAX, message = "이미지 주소는 255자를 넘을 수 없습니다.", groups = {Default.class, DraftChecks.class})
                    String> imageUrls
    ) {
    }
}
