package project.plantly.global.seed;

import project.plantly.domain.company.domesticRegion.DomesticRegion;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.CertificationRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ContactRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ImageRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ReferenceRequest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.TrlLevel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 등록 요청 조립기. {@link CompanyCreateRequest} 는 필드가 30개라 그대로 쓰면 케이스 정의가 읽히지 않는다.
 *
 * <p>기본값은 "평범하게 잘 채워진 회사"이고, 각 케이스는 자기가 증명하려는 축만 덮어쓴다. 그래야 케이스
 * 목록을 읽을 때 무엇이 그 케이스의 본질인지가 한눈에 보인다.
 *
 * <p>모양은 둘이다. 기본값은 최소한으로 채운 회사(카테고리 1, 상세이미지 3, 동영상 없음)이고,
 * {@link #filled(SeedMasterCatalog)} 는 잘 채운 회사다. 둘을 섞어 써야 목록 카드가 한 가지 모양으로만 보이지 않는다.
 * 한도는 등록 경로와 무관하게 {@code CompanyConstraints} 하나라, 어느 모양이든 그 안에 있으면 등록된다.
 */
public class SeedCompanyRequestBuilder {

    private final int index;

    private String businessNumber;
    private String companyName;
    private String ceoName;
    private LocalDate establishmentDate;
    private String postalCode;
    private String roadAddress;
    private String jibunAddress;
    private String detailAddress;
    private String website;
    private String logoUrl;
    private String coverImageUrl;
    private String introTitle;
    private String content;
    private TrlLevel trlLevel;
    private String videoUrl;
    private String leadTime;
    private String asInfo;
    private PricingType pricingType;
    private String brandColor;
    private CompanyVisibility visibility = CompanyVisibility.PUBLIC;

    private List<ContactRequest> contacts;
    private List<ImageRequest> images;
    private List<ReferenceRequest> references;
    private List<String> materialNames;
    private List<String> equipmentNames;
    private List<String> tagNames;

    private List<Long> categoryIds;
    private List<CertificationRequest> certifications;
    private List<Long> countryIds;
    private List<Long> domesticRegionIds;
    private List<Long> industryIds;

    private SeedCompanyRequestBuilder(int index, SeedMasterCatalog masters) {
        this.index = index;
        DomesticRegion region = masters.region(index);
        // 주소 문자열은 "시도 시군구" 로 시작해야 카드 지역 라벨이 온전히 잘린다. 링크는 region 그대로.
        String addressRegionName = masters.addressRegionName(region, index);

        this.companyName = SeedVocabulary.companyName(index);
        this.ceoName = SeedVocabulary.ceoName(index);
        this.establishmentDate = SeedVocabulary.establishmentDate(index);
        this.postalCode = SeedVocabulary.postalCode(index);
        this.roadAddress = SeedVocabulary.roadAddress(addressRegionName, index);
        this.jibunAddress = SeedVocabulary.jibunAddress(addressRegionName, index);
        this.detailAddress = SeedVocabulary.detailAddress(index);
        this.website = SeedVocabulary.website(index);
        this.logoUrl = SeedVocabulary.logoUrl(index);
        // 커버는 선택 필드지만 기본 모양에도 채운다 — 없는 경우는 withoutCoverStyling 이 의도적으로 만든다.
        this.coverImageUrl = SeedVocabulary.coverImageUrl(index);
        this.introTitle = SeedVocabulary.introTitle(index);
        this.content = SeedVocabulary.content(index);
        this.trlLevel = TrlLevel.values()[Math.floorMod(index, TrlLevel.values().length)];
        this.videoUrl = null; // 최소 모양에는 동영상이 없다. filled() 가 채운다
        this.leadTime = SeedVocabulary.leadTime(index);
        this.asInfo = SeedVocabulary.asInfo(index);
        this.pricingType = PricingType.values()[Math.floorMod(index, PricingType.values().length)];
        // brandColor 도 커버와 같이 기본으로 채운다.
        // 색이 없는 케이스는 withoutCoverStyling / withoutOptionalFields 가 의도적으로 만든다(프론트 폴백 확인용).
        this.brandColor = SeedVocabulary.brandColor(index);

        this.contacts = List.of(new ContactRequest(
                SeedVocabulary.contactName(index), "영업팀장",
                SeedVocabulary.phone(index), SeedVocabulary.email(index)));
        this.images = detailImages(3);
        this.references = List.of(reference(0));
        this.materialNames = SeedVocabulary.materials(index, 3);
        this.equipmentNames = SeedVocabulary.equipment(index, 3);
        this.tagNames = SeedVocabulary.tags(index, 3);

        this.categoryIds = masters.categoryIds(index, 1);
        this.certifications = masterCertifications(masters.certificationIds(index, 2));
        this.countryIds = masters.countryIds(index, 2);
        this.domesticRegionIds = List.of(region.getId());
        this.industryIds = masters.industryIds(index, 1);
    }

    public static SeedCompanyRequestBuilder of(int index, SeedMasterCatalog masters) {
        return new SeedCompanyRequestBuilder(index, masters);
    }

    /**
     * 잘 채운 회사 모양: 카테고리 5, 상세이미지 6, 동영상, 레퍼런스 이미지 4. 케이스 회사의 출발점이고,
     * 케이스는 여기서 자기가 증명하려는 축만 덮어쓴다. 입력 상한까지 채운 모양은 C21 이 따로 만든다.
     */
    public SeedCompanyRequestBuilder filled(SeedMasterCatalog masters) {
        this.categoryIds = masters.categoryIds(index, 5);
        this.images = detailImages(6);
        this.videoUrl = SeedVocabulary.videoUrl(index);
        this.references = List.of(reference(4));
        return this;
    }

    public SeedCompanyRequestBuilder businessNumber(String value) {
        this.businessNumber = value;
        return this;
    }

    public SeedCompanyRequestBuilder companyName(String value) {
        this.companyName = value;
        return this;
    }

    public SeedCompanyRequestBuilder ceoName(String value) {
        this.ceoName = value;
        return this;
    }

    public SeedCompanyRequestBuilder visibility(CompanyVisibility value) {
        this.visibility = value;
        return this;
    }

    public SeedCompanyRequestBuilder content(String value) {
        this.content = value;
        return this;
    }

    public SeedCompanyRequestBuilder introTitle(String value) {
        this.introTitle = value;
        return this;
    }

    public SeedCompanyRequestBuilder categoryIds(List<Long> value) {
        this.categoryIds = value;
        return this;
    }

    public SeedCompanyRequestBuilder certificationIds(List<Long> value) {
        this.certifications = masterCertifications(value);
        return this;
    }

    /**
     * 마스터 목록에 없는 인증을 직접 입력한 케이스. 기존 인증 뒤에 '기타' 링크로 덧붙는다.
     * 같은 마스터에 이름만 다른 링크가 여러 건 붙는 형태라, 프론트가 배지를 이름 기준으로 그리는지 확인하는 자리다.
     */
    public SeedCompanyRequestBuilder customCertificationNames(Long etcCertificationId, List<String> names) {
        if (etcCertificationId == null || names == null || names.isEmpty()) {
            return this;
        }
        List<CertificationRequest> merged = new ArrayList<>(certifications == null ? List.of() : certifications);
        names.forEach(name -> merged.add(new CertificationRequest(etcCertificationId, name)));
        this.certifications = List.copyOf(merged);
        return this;
    }

    private static List<CertificationRequest> masterCertifications(List<Long> ids) {
        return ids == null ? null : ids.stream().map(id -> new CertificationRequest(id, null)).toList();
    }

    public SeedCompanyRequestBuilder countryIds(List<Long> value) {
        this.countryIds = value;
        return this;
    }

    public SeedCompanyRequestBuilder industryIds(List<Long> value) {
        this.industryIds = value;
        return this;
    }

    public SeedCompanyRequestBuilder domesticRegionIds(List<Long> value) {
        this.domesticRegionIds = value;
        return this;
    }

    public SeedCompanyRequestBuilder equipmentNames(List<String> value) {
        this.equipmentNames = value;
        return this;
    }

    public SeedCompanyRequestBuilder detailImageCount(int count) {
        this.images = detailImages(count);
        return this;
    }

    public SeedCompanyRequestBuilder referenceImageCount(int count) {
        this.references = List.of(reference(count));
        return this;
    }

    /**
     * 커버 이미지와 브랜드 컬러만 비운다. 카드가 그릴 게 로고밖에 없는 상태를 만든다.
     * withoutOptionalFields 와 달리 나머지 선택 필드는 그대로 둔다 — 스팟라이트처럼 "정상 회사인데
     * 커버만 없는" 경우의 폴백(자리표시자 + 기본 배너 색)을 다른 결핍과 섞이지 않게 확인하기 위해서다.
     */
    public SeedCompanyRequestBuilder withoutCoverStyling() {
        this.coverImageUrl = null;
        this.brandColor = null;
        return this;
    }

    /** 모든 부속 컬렉션을 비운다. 빈 섹션이 어떻게 그려지는지 확인하는 케이스용. */
    public SeedCompanyRequestBuilder withoutCollections() {
        this.contacts = List.of();
        this.images = List.of();
        this.references = List.of();
        this.materialNames = List.of();
        this.equipmentNames = List.of();
        this.tagNames = List.of();
        return this;
    }

    /** 선택 필드를 전부 비운다. null 자리에 프론트가 무엇을 그리는지 확인하는 케이스용. */
    public SeedCompanyRequestBuilder withoutOptionalFields() {
        // 지번은 선택 필드다. 지번이 없는 주소에서 우편번호 서비스가 내려주는 빈 문자열을 그대로 재현한다
        // — Address 가 이를 null 로 접는지(= 상세 화면에 빈 줄이 남지 않는지)까지 시드로 드러난다.
        this.jibunAddress = "";
        this.coverImageUrl = null;
        this.website = null;
        this.introTitle = null;
        this.content = null;
        this.trlLevel = null;
        this.videoUrl = null;
        this.leadTime = null;
        this.asInfo = null;
        this.pricingType = null;
        this.brandColor = null;
        this.establishmentDate = null;
        return this;
    }

    public CompanyCreateRequest build() {
        return new CompanyCreateRequest(
                businessNumber, companyName, ceoName, establishmentDate,
                postalCode, roadAddress, jibunAddress, detailAddress,
                website, logoUrl, coverImageUrl, introTitle, content, trlLevel, videoUrl, leadTime, asInfo,
                pricingType, brandColor, visibility,
                contacts, images, references, materialNames, equipmentNames, tagNames,
                categoryIds, certifications, countryIds, domesticRegionIds, industryIds);
    }

    /**
     * 자가등록 요청. 신원 3종(사업자번호·대표자명·개업일자)은 자리 자체가 없다 — 선행 인증 레코드에서만 온다.
     */
    public MyCompanyCreateRequest buildMy(Long verificationId) {
        return new MyCompanyCreateRequest(
                verificationId, companyName,
                postalCode, roadAddress, jibunAddress, detailAddress,
                website, logoUrl, coverImageUrl, introTitle, content, trlLevel, videoUrl, leadTime, asInfo,
                pricingType, brandColor, visibility,
                contacts, images, references, materialNames, equipmentNames, tagNames,
                categoryIds, certifications, countryIds, domesticRegionIds, industryIds);
    }

    // 회사 직속 갤러리는 DETAIL 타입만 허용된다(GalleryImageTypePolicy). PROJECT 이미지는 레퍼런스로만 들어간다.
    private List<ImageRequest> detailImages(int count) {
        return IntStream.range(0, Math.max(count, 0))
                .mapToObj(slot -> new ImageRequest(SeedVocabulary.imageUrl(index, slot), ImageType.DETAIL))
                .toList();
    }

    private ReferenceRequest reference(int imageCount) {
        List<String> imageUrls = IntStream.range(0, Math.max(imageCount, 0))
                .mapToObj(slot -> SeedVocabulary.imageUrl(index, 100 + slot))
                .toList();
        return new ReferenceRequest(
                SeedVocabulary.projectTitle(index),
                SeedVocabulary.projectAchievements(index),
                SeedVocabulary.projectPartners(index),
                SeedVocabulary.projectPeriod(index),
                imageUrls);
    }
}
