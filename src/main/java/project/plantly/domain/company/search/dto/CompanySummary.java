package project.plantly.domain.company.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.search.CompanyCardProjection;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 회사 검색 결과 1건(리스트 카드). Company 스칼라 + 회사가 연결한 카테고리/태그/산업군 이름 목록.
 * 이름 목록은 검색 시점에 링크 테이블에서 집계한다(카테고리는 회사가 직접 고른 것만 — closure 조상은 제외).
 */
public record CompanySummary(
        Long id,
        String companyName,
        String introTitle,
        String logoUrl,

        // 카드 커버 이미지(없으면 null → 프론트가 자체 자리표시자로 폴백). 로고와 다른 축이다 — 로고는 정사각 배지,
        // 커버는 카드 배경에 깔리는 와이드 사진이라 같은 값을 두 자리에 쓸 수 없다.
        String coverImageUrl,

        // 브랜드 컬러(#RRGGBB, 미지정이면 null). 메인 스팟라이트 배너 배경에 쓴다.
        // 스팟라이트 레일만 필요한 값이지만 전용 프로젝션을 따로 만들지 않는다 — company 행의 스칼라라 추가 비용이
        // 사실상 없는 반면, 레일별로 프로젝션을 쪼개면 5개 읽기 경로가 공유하던 카드 매퍼가 갈라진다.
        // 저장값을 그대로 읽는다(조회 시점 파생 아님).
        String brandColor,

        // 표시용 지역 라벨: 도로명 주소에서 시도+시군구까지만 남긴 값("서울시 강남구" / "경기 화성시" / "세종특별자치시").
        // 전체 주소(도로명·지번·상세)는 상세 조회 응답에서 본다.
        String address,
        boolean verified,
        boolean featured,
        boolean spotlight,
        List<String> categoryNames,
        List<String> tagNames,
        List<String> industryNames,

        // 개인화(로그인 뷰어 기준): 내가 이 회사를 좋아요/즐겨찾기 했는지. 검색 프로젝션은 viewer 독립이라 false 로 만들고,
        // 조회 서비스가 뷰어별 배치 조회로 채운다(withViewerFlags). 익명 뷰어는 false 유지.
        boolean likedByMe,
        boolean favoritedByMe,

        // 공개 범위. 소유자 목록(fromOwner)에만 실리고 공개 경로(fromPublic)에서는 null 이라 응답에서 키가 빠진다.
        //
        // @JsonInclude 를 이 필드에만 붙이는 것이 중요하다. 타입 레벨에 붙이면 coverImageUrl·brandColor·
        // introTitle 처럼 "없으면 null" 을 계약으로 삼은 기존 필드까지 응답에서 사라진다 — 프론트가 null 을
        // 보고 자리표시자로 폴백하도록 만들어 둔 값들이라 그건 계약 파괴다.
        //
        // 공개 목록에서 굳이 빼는 이유: 그쪽에는 비공개 회사가 쿼리 시점에 걸러져 들어오지 않으므로 값이
        // 항상 PUBLIC 이다. 실어 보내면 아무 정보도 없는 상수 필드가 공개 계약에 하나 늘어난다.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        CompanyVisibility visibility,

        // 아래 셋도 visibility 와 같은 규칙이다 — 소유자 목록에만 실리고 공개 경로에서는 키가 빠진다.
        //
        // 국세청 사업자 확인 여부. 위의 verified 는 관리자 큐레이션 배지("에디터 선정")라 이 값과 축이 다르다 —
        // 소유자 목록이 "국세청 인증 완료"를 표시하려면 이 값을 써야 한다. 공개 경로에 싣지 않는 것은 지금 그쪽
        // 화면에 쓰는 곳이 없어서다(공개 계약을 넓히는 것은 그 화면이 생길 때 한다).
        // boolean 이 아니라 Boolean 인 이유: NON_NULL 로 키를 빼려면 null 이 될 수 있어야 한다.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean businessVerified,

        // 등록일·마지막 수정 시각. 소유자 목록의 "n일 전 수정" 표시용. updatedAt 은 회사 본체 행 기준이라
        // 컬렉션만 바꾼 수정(태그·이미지 PUT 등)은 반영되지 않는다.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime createdAt,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime updatedAt
) {

    /**
     * 공개 경로(공개 검색·메인 노출·즐겨찾기)의 카드. 소유자 전용 값(공개 범위 등)을 싣지 않는다.
     *
     * <p>{@code .map(CompanySummary::fromPublic)} 로 쓰라고 팩토리로 둔다 — 호출부의 그 한 줄만 보고
     * 이 목록이 무엇을 내보내는지 알 수 있어야 한다.
     */
    public static CompanySummary fromPublic(CompanyCardProjection row) {
        return of(row, OwnerFields.NONE);
    }

    /**
     * 소유자 목록의 카드. 공개 범위·국세청 확인 여부·등록/수정 시각을 함께 싣는다 — 본인 회사의 관리 상태가
     * 목록에서 보여야 한다.
     */
    public static CompanySummary fromOwner(CompanyCardProjection row) {
        return of(row, new OwnerFields(row.visibility(), row.businessVerified(), row.createdAt(), row.updatedAt()));
    }

    // 소유자 목록에만 실리는 값 묶음. 공개 경로는 NONE(전부 null)을 넘겨 응답에서 키가 빠진다.
    private record OwnerFields(CompanyVisibility visibility, Boolean businessVerified,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
        static final OwnerFields NONE = new OwnerFields(null, null, null, null);
    }

    // 두 팩토리의 유일한 차이가 소유자 전용 값이라는 사실을 한 곳에 모아 둔다 —
    // 카드에 필드가 늘어날 때 양쪽을 따로 고치다 어긋나지 않게.
    private static CompanySummary of(CompanyCardProjection row, OwnerFields owner) {
        return new CompanySummary(
                row.id(), row.companyName(), row.introTitle(), row.logoUrl(), row.coverImageUrl(),
                row.brandColor(), row.address(), row.verified(), row.featured(), row.spotlight(),
                row.categoryNames(), row.tagNames(), row.industryNames(),
                // 개인화는 viewer 독립인 카드 밖에서 채운다(withViewerFlags) → 여기선 false 기본값.
                false, false,
                owner.visibility(), owner.businessVerified(), owner.createdAt(), owner.updatedAt());
    }

    // 검색 결과 카드에 뷰어별 개인화 상태를 덧입힌 사본을 만든다. (record 는 불변이라 교체 생성)
    public CompanySummary withViewerFlags(boolean likedByMe, boolean favoritedByMe) {
        return new CompanySummary(id, companyName, introTitle, logoUrl, coverImageUrl, brandColor, address,
                verified, featured, spotlight, categoryNames, tagNames, industryNames,
                likedByMe, favoritedByMe, visibility, businessVerified, createdAt, updatedAt);
    }
}
