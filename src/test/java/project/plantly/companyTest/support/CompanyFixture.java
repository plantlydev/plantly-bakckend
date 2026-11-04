package project.plantly.companyTest.support;

import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;

// 서비스 단위 테스트가 다룰 Company 인스턴스.
// 테스트가 관심 갖는 필드(brandColor 등)만 의미 있게 두고 나머지는 null 로 둔 유저 등록 회사를 만든다.
public class CompanyFixture {

    public static Company userCompany() {
        return userCompanyWithBrandColor(null);
    }

    public static Company userCompanyWithBrandColor(String brandColor) {
        return Company.createByUser(
                1L,              // userId
                null,            // businessNumber
                "테스트회사",      // companyName
                "홍길동",          // ceoName
                null,            // establishmentDate
                Address.of(null, null, null, null),  // 주소(정책 테스트라 무해)
                null, null, null, null, null, null,  // website ~ trlLevel (logoUrl 뒤 coverImageUrl 포함)
                null,            // videoUrl
                null, null, null,// leadTime, asInfo, pricingType
                brandColor);
    }
}
