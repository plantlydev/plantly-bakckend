package project.plantly.domain.company.dto;

import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.RegistrationSource;

import java.time.LocalDateTime;
import java.util.List;
import project.plantly.domain.company.dto.CompanyPublicResponse.GalleryImageResponse;

// 소유자/관리자 전용 회사 상세 조회 응답.
// 공개 필드는 CompanyPublicResponse(profile)를 통째로 재사용하고, 내부·운영 정보만 meta 로 덧붙인다.
// (공개 응답과 필드 중복 없이 '공개 + 내부' 를 합성으로 표현 → 공개 경계가 구조로 드러난다)
//
// 확장 지점: 회사 등급/플랜 잔여기간은 아직 회사 레벨로 모델링되지 않아 지금은 담지 않는다.
//           추후 Subscription/Membership 이 생기면 profile/meta 와 나란히 별도 컴포넌트(예: membership)로 추가한다.
public record CompanyDetailResponse(
        CompanyPublicResponse profile,
        ManagementMeta meta
) {

    public static CompanyDetailResponse from(CompanyAggregate aggregate) {
        return new CompanyDetailResponse(
                // 소유자/관리자 뷰는 관리 목적이라 개인화(좋아요/즐겨찾기 상태)를 담지 않는다.
                // 마지막 true = 꺼진 항목도 전부 싣는다. 관리 화면은 "무엇이 저장돼 있고 그중 무엇이 지금
                // 공개되지 않는가"를 보여줘야 하므로, 공개 뷰처럼 지워버리면 소유자가 사라진 줄 안다.
                CompanyPublicResponse.from(aggregate, false, false, true),
                ManagementMeta.from(aggregate));
    }

    // 소유자/관리자에게만 보이는 내부·운영 메타데이터.
    public record ManagementMeta(
            String businessNumber,
            // 우편번호. 공개 응답(profile)은 화면에 안 쓰여 뺐지만, 수정 화면은 현재 값을 보여줘야 한다.
            String postalCode,
            RegistrationSource registrationSource,
            Long registeredBy,
            Long ownerUserId,
            boolean claimed,        // 소유자 연동 여부 (관리자 등록 후 미연동이면 false)
            int spotlightOrder,
            boolean verified,
            boolean businessVerified,
            LocalDateTime businessVerifiedAt,   // 인증 시각. 추후 주기적 재인증(1~2년) 도입 시 만료 판단 기준이 된다.
            boolean featured,
            boolean spotlight,
            boolean deleted,
            CompanyVisibility visibility,   // 공개 범위(PUBLIC/PRIVATE). 소유자/관리자만 보는 운영 메타.

            // 대표 레퍼런스의 이미지 전부(꺼진 것 포함, displayOrder 순). profile.representativeReference 는 공개 상세처럼
            // 표지 한 장(thumbnailUrl)만 준다 — 레퍼런스 수정은 전체 교체(PUT)라 수정 화면이 이 목록으로 채워야
            // 저장할 때 나머지 장이 지워지지 않는다. 레퍼런스가 없으면 빈 배열.
            List<GalleryImageResponse> representativeReferenceImages,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static ManagementMeta from(CompanyAggregate aggregate) {
            Company c = aggregate.company();
            Long ownerUserId = aggregate.ownerUserId();   // OWNER 멤버 없음(관리자 대신등록·미연동) → null
            return new ManagementMeta(
                    c.getBusinessNumber(),
                    c.getAddress().getPostalCode(),
                    c.getRegistrationSource(),
                    c.getRegisteredBy(),
                    ownerUserId,
                    ownerUserId != null,          // claimed = 소유자 연동 여부
                    c.getSpotlightOrder(),
                    c.isVerified(),
                    c.isBusinessVerified(),
                    c.getBusinessVerifiedAt(),
                    c.isFeatured(),
                    c.isSpotlight(),
                    c.isDeleted(),
                    c.getVisibility(),
                    aggregate.representativeReferenceImages().stream().map(GalleryImageResponse::from).toList(),
                    c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }
}
