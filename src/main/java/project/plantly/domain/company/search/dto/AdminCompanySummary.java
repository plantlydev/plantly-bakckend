package project.plantly.domain.company.search.dto;

import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.RegistrationSource;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 회사 목록 카드 1건. 공개 카드({@link CompanySummary})와 같은 회사 스칼라 + 이름 목록에
 * 운영 필드(삭제 여부 / 소유자 id / 등록 출처 / 등록 시각)를 더한다. 관리자만 보는 목록이므로
 * 어느 회사가 삭제됐는지·누구 소유인지·어떻게 등록됐는지를 카드에서 바로 구분할 수 있게 한다.
 */
public record AdminCompanySummary(
        Long id,
        String companyName,
        String introTitle,
        String logoUrl,
        // 카드 커버(와이드 사진). 관리자 목록 카드가 소유자 목록 카드와 같은 몸통(커버+로고)으로 그려져서 싣는다. 없으면 null.
        String coverImageUrl,
        String address,        // 공개 카드와 동일한 지역 라벨(시도+시군구). 전체 주소는 관리자 상세에서 본다.
        boolean verified,       // 에디터 선정 배지(관리자 큐레이션). 국세청 확인은 아래 businessVerified 다
        boolean businessVerified,
        boolean featured,       // 추천 레일 노출. 순수 관리자 큐레이션이라 끄면 바로 레일에서 빠진다
        // 스팟라이트 '수동 고정(pin)'. 켜져 있으면 메인 스팟라이트 레일 후보다(ShowcaseCardRepository).
        boolean spotlight,
        // ----- 운영 필드 (관리자 전용) -----
        boolean deleted,
        CompanyVisibility visibility,      // 공개 범위(PUBLIC/PRIVATE). 비공개도 관리자 목록엔 노출된다.
        Long ownerUserId,                  // 소유자 미연동(관리자 등록 등)이면 null
        RegistrationSource registrationSource,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,           // 회사 본체 행 기준(컬렉션만 바꾼 수정은 반영되지 않는다)
        // ----- 회사가 연결한 이름 목록 -----
        List<String> categoryNames,
        List<String> tagNames,
        List<String> industryNames
) {}
