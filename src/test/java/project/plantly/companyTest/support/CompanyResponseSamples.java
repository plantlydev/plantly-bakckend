package project.plantly.companyTest.support;

import project.plantly.domain.company.certification.CertificationType;
import project.plantly.domain.company.country.Continent;
import project.plantly.domain.company.domesticRegion.RegionLevel;
import project.plantly.domain.company.dto.CompanyDetailResponse;
import project.plantly.domain.company.dto.CompanyDetailResponse.ManagementMeta;
import project.plantly.domain.company.dto.CompanyPublicResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.CategoryResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.CertificationResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.ContactResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.CountryResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.GalleryImageResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.IndustryResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.ProjectReferenceResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse.RegionResponse;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.RegistrationSource;
import project.plantly.domain.company.enums.TrlLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 조회 컨트롤러 슬라이스 테스트(REST Docs)용 응답 샘플.
// 모든 필드를 채운(부속 포함) 표본이라 REST Docs 가 응답 트리 전체를 문서화할 수 있다.
public class CompanyResponseSamples {

    // 모든 필드가 채워진 공개 프로필 표본.
    public static CompanyPublicResponse fullPublic() {
        return new CompanyPublicResponse(
                1L, "플랜틀리테크", "김대표", LocalDate.of(2018, 3, 2),
                "서울 강남구 테헤란로 1", "서울 강남구 역삼동 736-1", "10층",   // 우편번호는 공개 응답에서 제외
                "https://plantly.test", "https://cdn.plantly.test/logo.png",
                "https://cdn.plantly.test/cover.png",
                "정밀 부품 전문", "정밀 가공 20년 경력의 부품 제조사입니다.",
                TrlLevel.MASS_PRODUCTION, "https://youtu.be/demo", "2주", "유선 AS 지원",
                PricingType.CONSULTATION, "#2E7D32",
                true, true, true, false,   // verified(에디터 선정), businessVerified(국세청 확인), featured, spotlight
                true, false,   // likedByMe, favoritedByMe
                new ContactResponse("이담당", "영업팀장", "01022223333", "rep@plantly.test"),
                List.of(new GalleryImageResponse("https://cdn.plantly.test/gallery/1.png", ImageType.DETAIL, 0, true)),
                new ProjectReferenceResponse("스마트팩토리 구축", "불량률 30% 감소", "A제조", "2022-2023",
                        "https://cdn.plantly.test/ref/cover.png"),
                List.of("스테인리스"),
                List.of("CNC 선반"),
                List.of("정밀"),
                List.of(new CategoryResponse(1L, "정밀가공", "CAT-001", 1, "https://cdn.plantly.test/cat.png", true)),
                List.of(new CertificationResponse(1L, "ISO 9001", CertificationType.MANAGEMENT_SYSTEM)),
                List.of(new CountryResponse(1L, "KR", "대한민국", "South Korea", Continent.ASIA)),
                List.of(new RegionResponse(1L, "1100000000", "서울특별시", "서울", RegionLevel.SIDO)),
                List.of(new IndustryResponse(1L, "기계", "IND-001", "https://cdn.plantly.test/ind.png"))
        );
    }

    // 공개 프로필 + 내부·운영 메타가 모두 채워진 상세 표본.
    public static CompanyDetailResponse fullDetail() {
        return new CompanyDetailResponse(
                fullPublic(),
                new ManagementMeta(
                        "1234567890", "06236", RegistrationSource.USER, 7L, 7L,
                        true, 0,
                        true,                                          // verified (에디터 선정)
                        true, LocalDateTime.of(2024, 1, 2, 3, 4, 5),   // businessVerified, businessVerifiedAt
                        true, false, false,                            // featured, spotlight, deleted
                        CompanyVisibility.PUBLIC,
                        true,                                          // videoVisibleToPublic (항상 true — 등급 게이트 폐지)
                        List.of(new GalleryImageResponse("https://cdn.plantly.test/ref/1.png", ImageType.PROJECT, 0, true)),
                        LocalDateTime.of(2024, 1, 2, 3, 4, 5),
                        LocalDateTime.of(2024, 1, 2, 3, 4, 5)));
    }
}
