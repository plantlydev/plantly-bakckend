package project.plantly.domain.company.policy;

import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ImageRequest;
import project.plantly.domain.company.entity.Company;

import java.util.List;

// 등록 정책이 읽는 것만 추린 입력. 슬라이스가 null 이면 해당 정책이 스스로 스킵한다.
//
// 지금 남은 정책은 구조 검증(GalleryImageTypePolicy) 하나라 갤러리 슬라이스만 둔다. 컬렉션 개수 한도는
// 요청 DTO 의 @Size(CompanyConstraints 의 *_CEILING) 가 모든 회사에 똑같이 걸고, 서비스 계층 정책은 없다.
public record CompanyPolicyView(
        Company company,
        List<ImageRequest> galleryImages // null = 스킵 (GalleryImageTypePolicy)
) {

    public static CompanyPolicyView forCreate(Company company, CompanyCreateRequest request) {
        return new CompanyPolicyView(company, request.images());
    }
}
