package project.plantly.companyTest.support;

import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.CertificationRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ImageRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ReferenceRequest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.enums.ImageType;

import java.util.List;

// CompanyCreateRequest 는 필드가 28개인 record 라, 테스트마다 전체 생성자를 호출하면 가독성이 떨어진다.
// 각 테스트가 "관심 있는 필드만" 세팅하도록 돕는 테스트 전용 빌더.
// 기본값은 정책을 건드리지 않는 무해한 값(대부분 null/빈 값)으로 둔다.
public class CompanyCreateRequestBuilder {

    // @NotBlank 대상은 의미 있는 기본값을 둔다. (정책 단위 테스트는 Bean Validation 을 거치지 않지만 현실성 유지)
    private String companyName = "테스트회사";
    private String ceoName = "홍길동";
    // 주소 3축도 @NotBlank 대상이다 — 엔티티(Address)가 NOT NULL 이라 등록 DTO 가 필수로 막는다.
    private String postalCode = "06236";
    private String roadAddress = "서울시 강남구 테헤란로 1";
    private String detailAddress = "10층";

    private String videoUrl;
    private String brandColor;
    private List<ImageRequest> images;
    private List<ReferenceRequest> references;
    private List<Long> categoryIds;
    private List<CertificationRequest> certifications;
    private List<String> materialNames;
    private List<String> equipmentNames;
    private List<String> tagNames;

    public static CompanyCreateRequestBuilder aRequest() {
        return new CompanyCreateRequestBuilder();
    }

    public CompanyCreateRequestBuilder videoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
        return this;
    }

    public CompanyCreateRequestBuilder brandColor(String brandColor) {
        this.brandColor = brandColor;
        return this;
    }

    public CompanyCreateRequestBuilder categoryIds(List<Long> categoryIds) {
        this.categoryIds = categoryIds;
        return this;
    }

    public CompanyCreateRequestBuilder certifications(List<CertificationRequest> certifications) {
        this.certifications = certifications;
        return this;
    }

    public CompanyCreateRequestBuilder nameLists(List<String> materialNames, List<String> equipmentNames, List<String> tagNames) {
        this.materialNames = materialNames;
        this.equipmentNames = equipmentNames;
        this.tagNames = tagNames;
        return this;
    }

    public CompanyCreateRequestBuilder images(List<ImageRequest> images) {
        this.images = images;
        return this;
    }

    public CompanyCreateRequestBuilder references(List<ReferenceRequest> references) {
        this.references = references;
        return this;
    }

    // n 장의 DETAIL 갤러리 이미지를 가진 요청을 만든다. (DetailImageLimitPolicy 용)
    public CompanyCreateRequestBuilder detailImages(int count) {
        this.images = imageUrls(count).stream()
                .map(url -> new ImageRequest(url, ImageType.DETAIL))
                .toList();
        return this;
    }

    // 단일 레퍼런스에 n 장의 이미지를 가진 요청을 만든다. (ReferenceImagePolicy 용)
    public CompanyCreateRequestBuilder referenceWithImages(int count) {
        this.references = List.of(new ReferenceRequest("프로젝트", null, null, null, imageUrls(count)));
        return this;
    }

    public CompanyCreateRequest build() {
        return new CompanyCreateRequest(
                null,            // businessNumber
                companyName,
                ceoName,
                null,            // establishmentDate
                postalCode,
                roadAddress,
                null,            // jibunAddress
                detailAddress,
                null,            // website
                null,            // logoUrl
                null,            // coverImageUrl
                null,            // introTitle
                null,            // content
                null,            // trlLevel
                videoUrl,
                null,            // leadTime
                null,            // asInfo
                null,            // pricingType
                brandColor,
                null,            // visibility (null = 공개 기본)
                null,            // contacts
                images,
                references,
                materialNames,
                equipmentNames,
                tagNames,
                categoryIds,
                certifications,
                null,            // countryIds
                null,            // domesticRegionIds
                null             // industryIds
        );
    }

    // 자가등록 요청(MyCompanyCreateRequest). 신원 3종(사업자번호/대표자명/개업일자)은 이 DTO 에 없고,
    // 선행 인증 레코드에서 서버가 채운다 — 그래서 verificationId 만 받는다.
    // 같은 기본값을 공유하려고 별도 빌더를 만들지 않고 이 빌더의 다른 출구로 둔다.
    public MyCompanyCreateRequest buildMy(Long verificationId) {
        return new MyCompanyCreateRequest(
                verificationId,
                companyName,
                postalCode,
                roadAddress,
                null,            // jibunAddress
                detailAddress,
                null,            // website
                null,            // logoUrl
                null,            // coverImageUrl
                null,            // introTitle
                null,            // content
                null,            // trlLevel
                videoUrl,
                null,            // leadTime
                null,            // asInfo
                null,            // pricingType
                brandColor,
                null,            // visibility (null = 공개 기본)
                null,            // contacts
                images,
                references,
                materialNames,
                equipmentNames,
                tagNames,
                categoryIds,
                certifications,
                null,            // countryIds
                null,            // domesticRegionIds
                null             // industryIds
        );
    }

    private static List<String> imageUrls(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "https://img/" + i + ".png")
                .toList();
    }
}
