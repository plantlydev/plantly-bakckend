package project.plantly.domain.company.dto;

import project.plantly.domain.company.category.Category;
import project.plantly.domain.company.certification.CertificationType;
import project.plantly.domain.company.country.Continent;
import project.plantly.domain.company.country.Country;
import project.plantly.domain.company.domesticRegion.DomesticRegion;
import project.plantly.domain.company.domesticRegion.RegionLevel;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyContact;
import project.plantly.domain.company.entity.CompanyImage;
import project.plantly.domain.company.entity.CompanyProjectReference;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.entity.link.CompanyCertification;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.TrlLevel;
import project.plantly.domain.company.industry.Industry;

import java.time.LocalDate;
import java.util.List;

// 일반(비소유자) 회사 상세 조회 응답. 누구에게 보여도 안전한 공개 필드만 담는다.
// 의도적으로 제외: businessNumber(사업자번호), 소유/등록 provenance(userId/registrationSource/registeredBy),
//                spotlightOrder, deleted, 타임스탬프 등 내부·운영 정보.
//                → 이들은 소유자/관리자 전용인 CompanyDetailResponse 의 meta 로만 노출한다.
//                postalCode(우편번호)도 제외한다 — 화면에 쓰이지 않고, 위치는 도로명/지번 주소로 이미 충분하다.
//
// 부속(연락처/이미지/레퍼런스/소재·장비·태그/링크 마스터)은 민감 정보가 아니므로 공개 뷰에도 그대로 포함하며,
// 소유자/관리자 뷰는 이 응답을 'profile' 로 통째로 재사용한다.
public record CompanyPublicResponse(
        Long id,
        String companyName,
        String ceoName,
        LocalDate establishmentDate,
        String roadAddress,
        String jibunAddress,
        String detailAddress,
        String website,
        String logoUrl,
        // 카드 커버(선택). 상세에서는 히어로 배경으로 쓴다 — 갤러리(galleryImages)와 다른 자리다.
        String coverImageUrl,
        String introTitle,
        String content,
        TrlLevel trlLevel,
        // 동영상(선택). 저장값을 그대로 싣는다.
        String videoUrl,
        String leadTime,
        String asInfo,
        PricingType pricingType,
        String brandColor,

        // 공개 노출용 배지성 플래그 (노출 순서값 spotlightOrder 는 내부 정보라 제외)
        // verified 와 businessVerified 는 다른 축이다 — 전자는 에디터 선정 큐레이션, 후자는 국세청 사업자 확인.
        // 프론트에서도 서로 다른 배지로 표시해야 한다. 사업자번호 자체는 여전히 비공개(meta 전용)다.
        boolean verified,
        boolean businessVerified,
        boolean featured,
        boolean spotlight,

        // 개인화(로그인 뷰어 기준): 내가 이 회사를 좋아요/즐겨찾기 했는지. 익명·소유자/관리자 뷰에서는 false.
        // (총 개수가 아니라 viewer 별 on/off 상태 — 총 개수는 CompanyStat 의 likeCount/favoriteCount 로 별도)
        boolean likedByMe,
        boolean favoritedByMe,

        // ===== 부속 =====
        // 연락처/레퍼런스는 초기 버전에서 대표 1건만 노출한다(없으면 null). 전체 목록은 추후 '더보기' 전용 조회로 분리.
        ContactResponse representativeContact,
        List<GalleryImageResponse> galleryImages,
        ProjectReferenceResponse representativeReference,
        List<String> materialNames,
        List<String> equipmentNames,
        List<String> tagNames,
        List<CategoryResponse> categories,
        List<CertificationResponse> certifications,
        List<CountryResponse> countries,
        List<RegionResponse> regions,
        List<IndustryResponse> industries
) {

    // likedByMe/favoritedByMe 는 aggregate(원자료)에 없는 viewer 별 상태라 호출부(조회 서비스)가 계산해 주입한다.
    // 개인화가 무의미한 경로(소유자/관리자 뷰, 익명)는 false 를 넘긴다.
    // includeHidden = 꺼진(active=false) 컬렉션 항목을 실을지. 공개 조회는 false(아예 빠진다),
    // 소유자/관리자 뷰는 true(전부 싣고 active 플래그로 회색 처리한다).
    public static CompanyPublicResponse from(CompanyAggregate aggregate, boolean likedByMe, boolean favoritedByMe,
                                             boolean includeHidden) {
        Company c = aggregate.company();
        return new CompanyPublicResponse(
                c.getId(),
                c.getCompanyName(),
                c.getCeoName(),
                c.getEstablishmentDate(),
                c.getAddress().getRoadAddress(),
                c.getAddress().getJibunAddress(),
                c.getAddress().getDetailAddress(),
                c.getWebsite(),
                c.getLogoUrl(),
                c.getCoverImageUrl(),
                c.getIntroTitle(),
                c.getContent(),
                c.getTrlLevel(),
                c.getVideoUrl(),
                c.getLeadTime(),
                c.getAsInfo(),
                c.getPricingType(),
                c.getBrandColor(),
                c.isVerified(),
                c.isBusinessVerified(),
                c.isFeatured(),
                c.isSpotlight(),
                likedByMe,
                favoritedByMe,
                aggregate.representativeContact() == null ? null
                        : ContactResponse.from(aggregate.representativeContact()),
                aggregate.galleryImages().stream()
                        .filter(image -> includeHidden || image.isActive())
                        .map(GalleryImageResponse::from).toList(),
                aggregate.representativeReference() == null ? null
                        : ProjectReferenceResponse.from(aggregate.representativeReference(),
                                aggregate.representativeReferenceThumbnail()),
                aggregate.materials().stream().map(m -> m.getMaterialName()).toList(),
                aggregate.equipment().stream().map(e -> e.getEquipmentName()).toList(),
                aggregate.tags().stream().map(t -> t.getTagName()).toList(),
                aggregate.categories().stream()
                        .filter(link -> includeHidden || link.isActive())
                        .map(CategoryResponse::from).toList(),
                aggregate.certifications().stream().map(CertificationResponse::from).toList(),
                aggregate.countries().stream().map(CountryResponse::from).toList(),
                aggregate.regions().stream().map(RegionResponse::from).toList(),
                aggregate.industries().stream().map(IndustryResponse::from).toList()
        );
    }

    public record ContactResponse(String contactName, String position, String phone, String email) {
        public static ContactResponse from(CompanyContact c) {
            return new ContactResponse(c.getContactName(), c.getPosition(), c.getPhone(), c.getEmail());
        }
    }

    // active 는 공개 뷰에선 언제나 true 다(꺼진 건 애초에 빠진다). 값이 갈리는 건 소유자/관리자 뷰뿐이며,
    // 그쪽 화면이 "저장돼 있지만 지금은 공개되지 않는 항목"을 회색으로 구분하는 근거가 된다.
    public record GalleryImageResponse(String imageUrl, ImageType imageType, int displayOrder, boolean active) {
        public static GalleryImageResponse from(CompanyImage image) {
            return new GalleryImageResponse(image.getImageUrl(), image.getImageType(), image.getDisplayOrder(),
                    image.isActive());
        }
    }

    // 프로젝트 레퍼런스 1건 + 표지 이미지 1장(displayOrder 최소, 없으면 null).
    // 전체 이미지(최대 10장)는 상세에 싣지 않는다 — 추후 '레퍼런스 더보기' 전용 조회로 분리.
    public record ProjectReferenceResponse(
            String projectTitle,
            String achievements,
            String partners,
            String period,
            String thumbnailUrl
    ) {
        public static ProjectReferenceResponse from(CompanyProjectReference ref, CompanyImage thumbnail) {
            return new ProjectReferenceResponse(
                    ref.getProjectTitle(),
                    ref.getAchievements(),
                    ref.getPartners(),
                    ref.getPeriod(),
                    thumbnail == null ? null : thumbnail.getImageUrl());
        }
    }

    // GalleryImageResponse.active 와 같은 규약. 노출 여부는 마스터가 아니라 링크의 상태라 링크에서 읽는다.
    public record CategoryResponse(Long id, String categoryName, String slug, int depth, String iconUrl,
                                   boolean active) {
        public static CategoryResponse from(CompanyCategory link) {
            Category category = link.getCategory();
            return new CategoryResponse(category.getId(), category.getCategoryName(),
                    category.getSlug(), category.getDepth(), category.getIconUrl(), link.isActive());
        }
    }

    // type 은 상세 페이지에서 인증 배지를 그룹별로 구분(색 등)하는 데 쓴다.
    //
    // certificationName 은 '화면에 그대로 찍는 이름'이다 — 마스터 목록에 없어 회사가 직접 적은 인증(type=ETC)이면
    // 마스터의 "기타"가 아니라 그 입력값이 온다. 폴백을 프론트에 넘기지 않고 여기서 끝낸다(안 그러면 화면마다
    // "기타"라고만 뜨는 배지가 생긴다). 직접 입력인지 구분해야 하면 type 으로 판별한다.
    public record CertificationResponse(Long id, String certificationName, CertificationType type) {
        public static CertificationResponse from(CompanyCertification link) {
            return new CertificationResponse(link.getCertification().getId(), link.displayName(),
                    link.getCertification().getType());
        }
    }

    public record CountryResponse(Long id, String code, String nameKo, String nameEn, Continent continent) {
        public static CountryResponse from(Country country) {
            return new CountryResponse(country.getId(), country.getCode(),
                    country.getNameKo(), country.getNameEn(), country.getContinent());
        }
    }

    // displayName 이 화면에 그대로 쓰는 완성형 표기다("경기 전역" / "경기 오산시").
    // 회사에 연결된 지역은 부모 문맥 없이 평평하게 내려가므로 프론트가 조합할 수 없어,
    // 시드에 저장해 둔 조합 결과를 그대로 전달한다. name(원본 명칭)은 하위호환으로 남긴다.
    public record RegionResponse(Long id, String code, String name, String displayName, RegionLevel level) {
        public static RegionResponse from(DomesticRegion region) {
            return new RegionResponse(region.getId(), region.getCode(), region.getName(),
                    region.getDisplayName(), region.getLevel());
        }
    }

    public record IndustryResponse(Long id, String industryName, String slug, String iconUrl) {
        public static IndustryResponse from(Industry industry) {
            return new IndustryResponse(industry.getId(), industry.getIndustryName(),
                    industry.getSlug(), industry.getIconUrl());
        }
    }
}
