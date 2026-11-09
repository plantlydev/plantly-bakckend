package project.plantly.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import project.plantly.global.response.ApiResponse;
import project.plantly.global.response.ValidationError;

import java.util.List;


// ResponseEntityExceptionHandler 를 상속한다.
//
// 이 advice 의 @ExceptionHandler(Exception.class) fallback 은 스프링의 기본 처리
// (DefaultHandlerExceptionResolver)보다 먼저 잡는다 - ExceptionHandlerExceptionResolver 의 우선순위가
// 더 높기 때문이다. 그래서 스프링이 알아서 4xx 로 매핑해 줄 표준 MVC 예외까지 전부 500 으로 나갔다.
// 실제로 깨진 JSON·타입 불일치·필수 파라미터 누락·필수 파트 누락·잘못된 메서드가 400/405 대신 500 이었고,
// 매핑되지 않은 URL(NoResourceFoundException)까지 500 이라 오타 난 주소가 "서버 장애"로 읽혔다.
// 하나씩 핸들러를 다는 방식은 또 새는 곳을 남기므로, 표준 예외 20여 종의 상태 코드 매핑을 부모에게 넘긴다.
//
// 다만 응답 본문은 기존 ApiResponse 봉투를 유지한다. 부모의 기본 본문은 ProblemDetail 이지만,
// 상태 코드 교정(상속의 목적)과 본문 형식은 분리할 수 있다 - createResponseEntity 한 곳에서 갈아끼운다.
// ProblemDetail 로 전면 전환하지 않는 이유는 비용이 이 클래스 밖에 있기 때문이다: 시큐리티 필터단의
// 401/403(SecurityResponseWriter)은 MVC 밖이라 이 상속이 닿지 않아 형식이 갈리고, 프론트는 별도 repo 라
// 락스텝 배포가 필요하다.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 409, 500 중 의도된 비즈니스 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e){
        ErrorCode code = e.getErrorCode();

        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.failure(code));
    }

    // 접근 권한이 없습니다.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure(CommonErrorCode.FORBIDDEN));
    }

    // 409 락 경합 - 락 대기 상한(Postgres lock_timeout, SQLState 55P03)을 넘겼거나 데드락(40P01)으로 희생됐다.
    // 스프링이 둘 다 PessimisticLockingFailureException 하위(CannotAcquireLockException 등)로 옮겨 준다.
    // 같은 대상을 다른 요청이 붙잡고 있다는 뜻이라 다시 시도하면 되므로 500 이 아니다. 다만 자주 보이면 락을
    // 오래 쥐는 트랜잭션이나 방치된 DB 세션이 있다는 신호라 경고로 남긴다.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockContention(PessimisticLockingFailureException e) {
        log.warn("락 경합으로 요청을 거절했다: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(CommonErrorCode.RESOURCE_BUSY.getStatus())
                .body(ApiResponse.failure(CommonErrorCode.RESOURCE_BUSY));
    }

    // 500 서버 오류 - 부모가 다루지 않는, 예상치 못한 모든 예외의 fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected (Exception e){
        // 예외 클래스에 @ResponseStatus 로 상태가 선언돼 있으면 그것을 따른다.
        // 그 선언을 읽는 것은 ResponseStatusExceptionResolver 인데 이 advice 가 먼저 잡아 선점하므로,
        // 여기서 직접 보지 않으면 "404 로 나가라"고 써 붙인 예외가 조용히 500 으로 둔갑한다.
        // (프로그래밍 방식으로 던지는 ResponseStatusException 은 ErrorResponseException 의 하위라
        //  부모가 이미 처리한다 - 여기 걸리는 것은 애너테이션만 붙은 예외다.)
        //
        // 이 프로젝트의 규약은 여전히 BusinessException + ErrorCode 다. 이 분기는 규약 밖의 예외
        // (주로 라이브러리가 던지는 것)가 선언해 둔 상태를 삼키지 않기 위한 안전망이지 두 번째 규약이 아니다.
        ResponseStatus declared = AnnotatedElementUtils.findMergedAnnotation(e.getClass(), ResponseStatus.class);

        if (declared != null) {
            HttpStatus status = declared.code();
            // reason 은 개발자가 "이 문구로 내보내라"고 적어 둔 것이므로 있으면 그대로 쓰고,
            // 없으면 상태 코드로 고른 기본 문구를 쓴다.
            // 코드는 상태에서 고른다 — 이 예외는 우리 규약(BusinessException + ErrorCode) 밖이라
            // 실어 보낼 도메인 코드가 없다. 상태 단위 코드라도 있으면 클라이언트가 최소한 갈래는 나눌 수 있다.
            CommonErrorCode declaredCode = defaultErrorCode(status);
            String message = StringUtils.hasText(declared.reason()) ? declared.reason() : declaredCode.getMessage();

            if (status.is5xxServerError()) {
                log.error("상태가 선언된 예외 (status={})", status.value(), e);
            } else {
                log.warn("상태가 선언된 예외 (status={}): {}: {}", status.value(), e.getClass().getSimpleName(), e.getMessage());
            }

            return ResponseEntity.status(status).body(ApiResponse.failure(declaredCode, message));
        }

        log.error("예상치 못한 서버 오류", e);  //실제 원인은 로그로 기록

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR));  //클라이언트에는 일괄적으로 서버 오류로 내려줌
    }

    // --- 아래는 부모가 이미 @ExceptionHandler 로 잡는 예외들의 처리 지점(protected 훅) ---
    // 같은 예외 타입에 @ExceptionHandler 를 새로 달면 부모 것과 겹쳐 기동 시 ambiguous mapping 으로 터진다.
    // 훅은 어노테이션이 없으므로 오버라이드로만 손댄다.

    // 400 검증 실패 - 요청 본문 객체 바인딩·검증
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return handleExceptionInternal(ex, validationBody(ValidationErrorOrdering.sorted(ex.getBindingResult())),
                headers, status, request);
    }

    // 400 검증 실패 - 메서드 파라미터 검증. 위 MethodArgumentNotValidException 은 "요청 본문 객체"를 바인딩·검증할 때
    // 나오고, 이쪽은 본문이 객체가 아니라 리스트라 파라미터 레벨 검증이 도는 경우(@Valid 의 원소 중첩 검증,
    // List<@NotNull X> 같은 컨테이너 원소 제약)에 나온다.
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return handleExceptionInternal(ex, validationBody(ValidationErrorOrdering.sorted(ex)),
                headers, status, request);
    }

    // 검증 실패 응답 본문. 위반을 전부 담고, error 에는 그 첫 메시지를 넣는다 -
    // 항목별 표시를 하지 않는 화면이 error 하나만 읽어도 동작하도록 봉투 모양을 맞춘다.
    //
    // 목록이 비는 것은 이론상의 경우다(메시지 없는 위반만 담겨 온 경우). 그때도 봉투는 나가야 하므로
    // 기본 문구로 떨어진다.
    private ApiResponse<Void> validationBody(List<ValidationError> errors) {
        if (errors.isEmpty()) {
            return ApiResponse.failure(CommonErrorCode.INVALID_INPUT);
        }

        return ApiResponse.failure(CommonErrorCode.INVALID_INPUT, errors.get(0).message(), errors);
    }

    // 400 업로드 용량 초과 - 서블릿 컨테이너가 요청을 다 받기 전에 끊고 던진다. 컨트롤러에 닿지 않으므로
    // UploadService 의 상한 검사로는 잡히지 않는다. 부모는 이걸 413 으로 주지만, "입력 오류는 400" 이라는
    // 이 API 의 규칙에 맞춰 400 으로 되돌린다(CommonErrorCode.FILE_TOO_LARGE 주석 참고).
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return handleExceptionInternal(ex, ApiResponse.failure(CommonErrorCode.FILE_TOO_LARGE),
                headers, CommonErrorCode.FILE_TOO_LARGE.getStatus(), request);
    }

    // 표준 예외가 지나가는 단일 통로. 진단값(받은 Content-Type, 변환 실패한 값 등)은 예외 메시지에 들어 있으므로
    // 여기서 로그로만 남기고 클라이언트에게는 일반화한 문구를 내려보낸다 - 사용자가 화면에서 만들 수 있는
    // 상태가 아니라 클라이언트 구현 오류다.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        if (statusCode.is5xxServerError()) {
            log.error("요청 처리 중 서버 오류 (status={})", statusCode.value(), ex);
        } else {
            log.warn("거절된 요청 (status={}): {}: {}", statusCode.value(), ex.getClass().getSimpleName(), ex.getMessage());
        }

        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // 부모가 만든 ProblemDetail 본문을 ApiResponse 봉투로 갈아끼우는 지점. 위 훅들이 이미 봉투를 만들어
    // 넘긴 경우에는 그대로 둔다.
    //
    // 참고: Accept 헤더가 JSON 이 아니어서 406 이 되는 경우에는 이 봉투도 쓰이지 못하고 본문 없이 406 만 나간다 -
    // 클라이언트가 받지 않겠다고 선언한 형식으로 봉투를 실어 보낼 수는 없다.
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        if (body instanceof ApiResponse) {
            return new ResponseEntity<>(body, headers, statusCode);
        }

        return new ResponseEntity<>(ApiResponse.failure(defaultErrorCode(statusCode)), headers, statusCode);
    }

    // 스프링이 요청 자체를 거절한 경우의 코드. 예외 종류가 아니라 상태 코드로 고른다 -
    // 부모가 다루는 예외가 20여 종이라 종류마다 두면 다시 "빠뜨린 예외" 문제가 생기고,
    // 클라이언트에게 유의미한 구분은 어차피 상태 코드 단위다.
    //
    // 문구가 아니라 ErrorCode 를 돌려주는 이유: 코드가 자기 문구를 들고 있어 둘을 따로 고를 이유가 없고,
    // 문구만 돌려주면 이 경로의 응답에 code 가 빠진다(클라이언트가 분기할 수 없는 응답이 하나 생긴다).
    private CommonErrorCode defaultErrorCode(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());

        if (status == null) {
            return CommonErrorCode.INTERNAL_ERROR;
        }

        return switch (status) {
            case NOT_FOUND -> CommonErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> CommonErrorCode.NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case PAYLOAD_TOO_LARGE -> CommonErrorCode.FILE_TOO_LARGE;
            case UNAUTHORIZED -> CommonErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> CommonErrorCode.FORBIDDEN;
            case CONFLICT -> CommonErrorCode.CONFLICT;
            // 여기 없는 상태는 갈래만 나눈다. 표준 MVC 예외의 4xx 는 대부분 실제로 입력 문제(깨진 JSON·
            // 타입 불일치·필수 파라미터 누락)라 INVALID_INPUT 이 맞지만, 규약 밖 예외가 낯선 4xx 를 선언해
            // 오면 그 코드가 정확하지 않을 수 있다. 정확한 코드가 필요해지면 위 switch 에 상태를 추가한다.
            default -> statusCode.is4xxClientError()
                    ? CommonErrorCode.INVALID_INPUT
                    : CommonErrorCode.INTERNAL_ERROR;
        };
    }
}
