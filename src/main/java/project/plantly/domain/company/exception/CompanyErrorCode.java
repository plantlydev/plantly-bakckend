package project.plantly.domain.company.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import project.plantly.global.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum CompanyErrorCode implements ErrorCode {

    // 회사 직속 갤러리(images)에 DETAIL 이 아닌 이미지 타입이 포함된 경우
    GALLERY_IMAGE_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "갤러리에는 상세 이미지(DETAIL)만 등록할 수 있습니다."),

    // 연락처/레퍼런스는 초기 버전상 각각 1건만 허용한다.
    // 등록·수정 모두 요청 DTO 의 @Size(max=1) 가 먼저 거른다 — 그래야 응답에 어느 항목인지가 실린다.
    // 아래 두 코드는 그 뒤에 서는 writer 가드용이다: DTO 를 거치지 않는 호출부(시드·배치 등)를 막는다.
    CONTACT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "연락처는 1건만 등록할 수 있습니다."),
    REFERENCE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "레퍼런스는 1건만 등록할 수 있습니다."),

    // 링크(M:N) 등록 시 요청에 존재하지 않는 마스터 ID가 포함된 경우
    CATEGORY_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 카테고리가 포함되어 있습니다."),
    CERTIFICATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 인증이 포함되어 있습니다."),
    COUNTRY_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 국가가 포함되어 있습니다."),
    DOMESTIC_REGION_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 국내 지역이 포함되어 있습니다."),
    INDUSTRY_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 산업군이 포함되어 있습니다."),

    // 인증 링크의 "직접 입력한 인증명(customName)" 규약 위반.
    // customName 은 '기타'(ETC) 마스터에만 붙는다 — 마스터 목록에 없는 인증을 회사가 적어 넣는 앵커라서다.
    // 양방향으로 다 막는다: 기타인데 이름이 없으면 화면에 "기타"라는 정체불명 배지가 뜨고,
    // 기타가 아닌데 이름이 있으면 마스터 이름과 다른 이름이 같은 인증 행세를 한다(패싯 필터와 표기가 어긋난다).
    CERTIFICATION_CUSTOM_NAME_REQUIRED(HttpStatus.BAD_REQUEST, "기타 인증은 인증명을 직접 입력해야 합니다."),
    CERTIFICATION_CUSTOM_NAME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "직접 입력한 인증명은 기타 인증에만 붙일 수 있습니다."),
    CERTIFICATION_CUSTOM_NAME_TOO_LONG(HttpStatus.BAD_REQUEST, "직접 입력한 인증명이 너무 깁니다."),

    // 조회 대상 회사가 없거나(삭제 포함) 식별자가 잘못된 경우. 공개 조회에서 소프트 삭제는 미존재로 취급한다.
    COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 회사입니다."),

    // 본인 회사 상세(소유자 전용 뷰)를 소유자가 아닌 사용자가 요청한 경우
    COMPANY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 회사에 대한 접근 권한이 없습니다."),

    // 등록/복구 시, 활성(미삭제) 회사 중 이미 같은 사업자번호가 존재하는 경우.
    // (soft delete 된 번호는 재사용 가능하지만, 활성 회사끼리는 유일해야 한다 — 부분 유니크 인덱스)
    BUSINESS_NUMBER_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 사업자번호입니다."),

    // ===== 국세청 사업자 인증 =====
    // 국세청에 물어보기 전에 형식으로 거른다. 쿼터를 아끼고 오타를 즉시 알려준다.
    BUSINESS_NUMBER_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "사업자등록번호는 숫자 10자리여야 합니다."),

    // 국세청 판정 결과별 안내. 사용자가 무엇을 고쳐야 하는지가 각각 다르므로 하나로 뭉치지 않는다.
    VERIFICATION_MISMATCH(HttpStatus.BAD_REQUEST, "사업자등록번호·대표자명·개업일자가 국세청 등록 정보와 일치하지 않습니다."),
    VERIFICATION_NOT_REGISTERED(HttpStatus.BAD_REQUEST, "국세청에 등록되지 않은 사업자등록번호입니다."),
    VERIFICATION_SUSPENDED(HttpStatus.BAD_REQUEST, "휴업 중인 사업자는 등록할 수 없습니다."),
    VERIFICATION_CLOSED(HttpStatus.BAD_REQUEST, "폐업한 사업자는 등록할 수 없습니다."),

    // 국세청 쪽 문제로 판정을 받지 못한 경우. 사용자 입력 오류와 절대 같은 문구를 쓰지 않는다 —
    // 점검 시간에 "정보가 일치하지 않습니다"가 뜨면 멀쩡한 사업자가 자기 정보를 계속 고쳐보게 된다.
    VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "국세청 시스템 점검 등으로 확인이 지연되고 있습니다. 잠시 후 다시 시도해주세요."),

    // 일일 시도 횟수 초과. 국세청 API 쿼터 보호 + 대표자명 추측 시도 차단.
    VERIFICATION_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "하루 인증 시도 횟수를 초과했습니다. 내일 다시 시도해주세요."),

    // 등록 시 넘긴 인증 식별자가 없거나, 본인이 받은 인증이 아닌 경우.
    VERIFICATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "유효한 사업자 인증 정보가 없습니다. 사업자 인증을 먼저 진행해주세요."),
    VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "사업자 인증이 만료되었습니다. 다시 인증해주세요."),
    VERIFICATION_ALREADY_USED(HttpStatus.CONFLICT, "이미 회사 등록에 사용된 사업자 인증입니다."),

    // 국세청 검증을 거친 값(대표자명/개업일자)을 임의 수정하려는 경우.
    // 수정을 허용하면 인증 통과 후 아무 값으로 바꿔놓고 배지를 유지할 수 있다.
    // (인증 회사는 대신 재인증 경로로만 이 두 값을 바꿀 수 있다 — POST /api/v1/companies/{id}/verification)
    VERIFIED_FIELD_NOT_EDITABLE(HttpStatus.BAD_REQUEST, "국세청 인증을 받은 회사는 대표자명·개업일자를 수정할 수 없습니다. 재인증을 통해 변경해주세요."),

    // 재인증은 국세청 인증을 이미 받은 회사에서만 가능하다. 인증이 회수됐거나 애초에 미인증인(관리자 대신등록 등)
    // 회사는 이 경로로 배지를 (재)획득할 수 없다 — 그건 관리자 모더레이션 영역이다.
    COMPANY_NOT_BUSINESS_VERIFIED(HttpStatus.BAD_REQUEST, "국세청 인증을 받은 회사만 재인증할 수 있습니다."),

    // ===== 임시저장(초안) =====
    // 복원하려는 초안이 없는 경우(저장한 적 없거나 이미 발행/폐기된 경우).
    DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "저장된 임시저장 내역이 없습니다."),

    // 초안 payload 직렬화/역직렬화 실패(서버 내부 오류).
    DRAFT_SERIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "임시저장 처리 중 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
