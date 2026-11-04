package project.plantly.domain.company.dto;

import project.plantly.domain.company.country.Country;
import project.plantly.domain.company.domesticRegion.DomesticRegion;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyContact;
import project.plantly.domain.company.entity.CompanyEquipment;
import project.plantly.domain.company.entity.CompanyImage;
import project.plantly.domain.company.entity.CompanyMaterial;
import project.plantly.domain.company.entity.CompanyProjectReference;
import project.plantly.domain.company.entity.CompanyTag;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.entity.link.CompanyCertification;
import project.plantly.domain.company.industry.Industry;

import java.util.List;

// 상세 응답 조립을 위해 회사 본체 + 부속(자식/링크)을 한 번에 묶은 내부 전용 묶음.
// 조회 서비스가 채워 DTO 의 from(...) 으로 넘긴다. (응답 형태(공개/상세)에 독립적인 '원자료' 계층)
//
// 연락처/레퍼런스는 하위 필드를 가진 컬렉션이라 초기 버전은 대표 1건만 상세에 싣는다(없으면 null).
// representativeReferenceThumbnail 은 그 대표 레퍼런스의 표지 이미지 1장(displayOrder 최소)이다(없으면 null).
//   공개 상세는 이 한 장만 싣는다(전체는 추후 '레퍼런스 더보기' 전용 조회로 분리).
// representativeReferenceImages 는 같은 레퍼런스의 이미지 전부(displayOrder 순)다. 소유자/관리자
//   응답(meta)만 쓴다 — 레퍼런스 수정은 전체 교체(PUT)라, 수정 화면이 한 장만 알면 저장 순간 나머지가 지워진다.
// 갤러리 이미지(projectReference == null)는 galleryImages 로 따로 담는다.
public record CompanyAggregate(
        Company company,
        Long ownerUserId,   // 회사의 OWNER 멤버 user id. 관리자 대신등록·미연동이면 null. (소유는 CompanyMember 가 SSOT)
        CompanyContact representativeContact,
        List<CompanyImage> galleryImages,
        CompanyProjectReference representativeReference,
        CompanyImage representativeReferenceThumbnail,
        List<CompanyImage> representativeReferenceImages,
        List<CompanyMaterial> materials,
        List<CompanyEquipment> equipment,
        List<CompanyTag> tags,
        // 카테고리는 링크째 담는다(회사가 고른 순서 displayOrder 가 링크에 있다). 마스터는 link.getCategory() 로 꺼낸다.
        List<CompanyCategory> categories,
        // 인증도 링크째 담는다 — '기타' 링크의 표시 이름(custom_name)은 마스터가 아니라
        // 링크가 들고 있다. 마스터는 link.getCertification() 으로 꺼낸다.
        List<CompanyCertification> certifications,
        List<Country> countries,
        List<DomesticRegion> regions,
        List<Industry> industries
) {
}
