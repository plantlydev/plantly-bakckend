package project.plantly.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // 업로드 용량 초과. 도메인 코드가 아니라 여기 있는 이유는 이 한계가 두 층에서 걸리기 때문이다 —
    // 서블릿 상한(StorageConfig 가 app.upload.max-file-size 에서 만든다)은 컨트롤러에 닿기도 전에 터지고(전역),
    // 애플리케이션 상한(같은 값)은 UploadService 가 확인한다. 같은 사실을 두 문장으로
    // 말하지 않으려고 한 곳에 둔다. 413 이 아니라 400 인 것은 "입력 오류는 400" 이라는 이 API 의 규칙에 맞춘 것.
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "업로드 가능한 파일 용량을 초과했습니다."),

    // 요청 Content-Type 이 엔드포인트가 받는 형식과 다르거나 아예 없는 경우.
    // 사용자가 화면에서 만들 수 있는 상태가 아니라 클라이언트 구현 오류라, 메시지는 일반화하고
    // 실제 형식/허용 형식은 로그로 남긴다(GlobalExceptionHandler).
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),

    // 아래 넷은 도메인이 던지는 코드가 아니라, 스프링이 요청 자체를 거절할 때 GlobalExceptionHandler 가
    // 상태 코드로부터 골라 쓰는 기본값이다(문구와 응답 code 둘 다). 도메인은 여전히 자기 enum 의 코드를 던진다 -
    // 예를 들어 "존재하지 않는 회사"는 CompanyErrorCode 의 404 지 여기 NOT_FOUND 가 아니다.
    // 여기 NOT_FOUND 는 매핑 자체가 없는 URL(오타 주소 등)용이다.
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "요청한 형식으로는 응답할 수 없습니다."),

    // 상태만 선언된 규약 밖 예외(@ResponseStatus)가 409 로 나갈 때의 코드. 이게 없으면 4xx 기본값인
    // INVALID_INPUT 으로 떨어져 "충돌"이 "잘못된 입력값"으로 나가고, 클라이언트가 검증 오류로 분기한다.
    // 도메인 409(사업자번호 중복 등)는 여전히 자기 코드를 던진다.
    CONFLICT(HttpStatus.CONFLICT, "요청이 현재 상태와 충돌합니다."),

    // 락 대기 상한(app.db.lock-timeout)을 넘겨 행 락을 못 잡았거나 데드락으로 희생된 경우. 같은 대상에 대한 다른
    // 요청이 처리 중이라는 뜻이라 서버 오류(500)가 아니라 다시 시도하면 되는 충돌이다. 도메인 코드가 아니라 여기
    // 있는 이유는 락이 특정 도메인이 아니라 DB 계층에서 걸리기 때문이다.
    RESOURCE_BUSY(HttpStatus.CONFLICT, "다른 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
