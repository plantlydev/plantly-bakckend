package project.plantly.globalTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.exception.CommonErrorCode;
import project.plantly.global.response.ApiResponse;

import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GlobalExceptionHandler 의 @ExceptionHandler(Exception.class) fallback 은 스프링의 기본 처리
// (DefaultHandlerExceptionResolver)보다 먼저 잡는다 - ExceptionHandlerExceptionResolver 의 우선순위가
// 더 높아서, 스프링이 알아서 4xx 로 매핑해 줄 표준 MVC 예외까지 이 advice 가 가로채 500 으로 내보냈다.
// 415(HttpMediaTypeNotSupportedException)를 UploadControllerTest 에서 그렇게 발견한 뒤, 이 테스트로 나머지를
// 한자리에 드러냈다 - 실제로 6종이 500 으로 새고 있었다. 그래서 advice 를 ResponseEntityExceptionHandler
// 상속으로 바꿨고, 이 테스트는 그 상태 코드들이 다시 500 으로 돌아가지 않도록 잠가 둔다.
//
// 검증 대상이 특정 도메인 엔드포인트가 아니라 advice 자체이므로, 예외를 하나씩 정확히 유발하는
// probe 컨트롤러를 두고 그것만 띄운다. 도메인 컨트롤러에 얹으면 서비스 목·인증 주체 같은 잡음이 끼고,
// 나중에 그 엔드포인트의 계약이 바뀌면 advice 와 무관한 이유로 이 테스트가 깨진다.
//
// MockMvc 에 시큐리티 필터를 걸지 않는 것은 다른 컨트롤러 테스트와 같은 이유다(UploadControllerTest 주석 참고).
@ActiveProfiles("test")
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import(GlobalExceptionHandlerTest.ProbeController.class)
@DisplayName("전역 예외 처리 - 표준 MVC 예외가 제 상태 코드로 나가는가")
class GlobalExceptionHandlerTest {

    // fallback 이 가로챘을 때 내려가는 문구. 이 문장이 보이면 "클라이언트 요청 오류"가 "서버가 죽었다"로
    // 둔갑한 것이다 - 상태 코드뿐 아니라 이 메시지가 아님도 함께 확인한다.
    private static final String FALLBACK_MESSAGE = "서버 오류가 발생했습니다.";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Nested
    @DisplayName("업로드 API 작업에서 먼저 잡았던 것")
    class AlreadyHandled {

        @Test
        @DisplayName("받지 않는 Content-Type 은 415 다")
        void unsupportedMediaType() throws Exception {
            mockMvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_XML)
                            .content("<probe/>"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("지원하지 않는 요청 형식입니다."));
        }
    }

    @Nested
    @DisplayName("상속 전환으로 되돌린 것 - 전환 전에는 모두 500 이었다")
    class RestoredByInheritance {

        @Test
        @DisplayName("깨진 JSON 본문은 400 이다 - HttpMessageNotReadableException")
        void malformedJsonBody() throws Exception {
            mockMvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value(not(FALLBACK_MESSAGE)));
        }

        @Test
        @DisplayName("경로 변수 타입이 안 맞으면 400 이다 - MethodArgumentTypeMismatchException")
        void pathVariableTypeMismatch() throws Exception {
            mockMvc.perform(get("/probe/items/{id}", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value(not(FALLBACK_MESSAGE)));
        }

        @Test
        @DisplayName("필수 쿼리 파라미터가 없으면 400 이다 - MissingServletRequestParameterException")
        void missingRequestParameter() throws Exception {
            mockMvc.perform(get("/probe/search"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value(not(FALLBACK_MESSAGE)));
        }

        // 업로드 API 는 file 파트를 required=false 로 받아 서비스에서 BusinessException 을 던지는 방식으로
        // 이 예외를 피해 갔다. 그 우회가 없는 엔드포인트를 새로 열면 그대로 500 이 나가므로 여기서 함께 잠근다.
        @Test
        @DisplayName("필수 multipart 파트가 없으면 400 이다 - MissingServletRequestPartException")
        void missingRequestPart() throws Exception {
            mockMvc.perform(multipart("/probe/part")
                            .file(new MockMultipartFile("wrongName", new byte[]{1, 2, 3})))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value(not(FALLBACK_MESSAGE)));
        }

        @Test
        @DisplayName("허용하지 않는 HTTP 메서드는 405 다 - HttpRequestMethodNotSupportedException")
        void methodNotSupported() throws Exception {
            mockMvc.perform(delete("/probe/search"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value(not(FALLBACK_MESSAGE)));
        }

        // 오타 난 URL 은 사용자가 만들 수 있는 상태다(북마크, 프론트 라우팅 실수). 이게 500 이면
        // 프론트는 "서버 장애"로 읽고 엉뚱한 데서 원인을 찾는다.
        //
        // 상태 코드뿐 아니라 봉투까지 확인한다 - 상태만 보면 이 경로가 나중에 빈 본문이나 ProblemDetail 로
        // 바뀌어도 테스트가 통과해 버린다. success 플래그는 ProblemDetail 에 없으므로 형식이 갈리면 여기서 걸린다.
        @Test
        @DisplayName("매핑되지 않은 경로는 404 이고 본문도 같은 봉투다 - NoResourceFoundException")
        void unmappedPath() throws Exception {
            mockMvc.perform(get("/probe/does-not-exist"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("요청한 경로를 찾을 수 없습니다."));
        }
    }

    @Nested
    @DisplayName("부모가 다루지 않는 것 - fallback 의 안전망")
    class DeclaredStatusOnException {

        // 예외 클래스에 붙은 @ResponseStatus 는 ResponseStatusExceptionResolver 가 읽지만, 이 advice 의
        // fallback 이 먼저 잡아 선점한다. 부모(ResponseEntityExceptionHandler)가 다루는 것은 자기가 선언한
        // 표준 MVC 예외 목록과 ErrorResponseException 계열뿐이라 여기까지 오지 않는다.
        //
        // 이 프로젝트는 상태를 BusinessException + ErrorCode 로 선언하므로 지금 이런 예외는 없다.
        // 라이브러리 예외나 나중에 들어올 코드가 선언한 상태를 조용히 500 으로 바꾸지 않도록 잠가 둔다.
        @Test
        @DisplayName("@ResponseStatus 가 붙은 예외는 그 상태로 나간다 - 500 으로 덮이지 않는다")
        void declaredStatusIsHonoured() throws Exception {
            mockMvc.perform(get("/probe/declared-status"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value(not(FALLBACK_MESSAGE)));
        }

        @Test
        @DisplayName("reason 을 적어 두었으면 그 문구를 쓴다")
        void declaredReasonIsUsed() throws Exception {
            mockMvc.perform(get("/probe/declared-reason"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("만료된 초대입니다."));
        }
    }

    @Nested
    @DisplayName("부모의 기본 매핑을 일부러 벗어나는 것")
    class DeliberateDeviation {

        // ResponseEntityExceptionHandler 는 용량 초과를 413(PAYLOAD_TOO_LARGE)으로 준다. 이 API 는
        // "입력 오류는 400" 규칙이라 400 으로 되돌려 놓았고(CommonErrorCode.FILE_TOO_LARGE),
        // 상속으로 갈아탈 때 조용히 413 으로 바뀌지 않도록 여기서 잠근다.
        //
        // 실제 서블릿 상한은 MockMvc 가 강제하지 않으므로 예외를 직접 던져 advice 경로만 확인한다 -
        // 상한값 자체는 UploadServiceTest 가 애플리케이션 층에서 따로 본다.
        @Test
        @DisplayName("업로드 용량 초과는 413 이 아니라 400 이다 - MaxUploadSizeExceededException")
        void maxUploadSizeExceeded() throws Exception {
            mockMvc.perform(post("/probe/too-large"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("업로드 가능한 파일 용량을 초과했습니다."));
        }
    }

    @Nested
    @DisplayName("전환 전에도 상태 코드만은 맞았던 것")
    class AlreadyCorrectByAccident {

        // 406 은 전환 전에도 406 으로 나갔지만 fallback 을 안 거쳐서가 아니었다. 로그로 확인한 당시 순서는
        //   1) fallback 이 HttpMediaTypeNotAcceptableException 을 잡아 500 + ApiResponse 를 만든다
        //   2) 그 JSON 본문을 쓰려는데 Accept 가 application/xml 이라 협상에 또 실패한다
        //      ("Failure in @ExceptionHandler ... HttpMediaTypeNotAcceptableException")
        //   3) DefaultHandlerExceptionResolver 가 그 실패를 받아 406 으로 되돌려 놓는다
        // 상태 코드만 우연히 맞고 서버 로그에는 "예상치 못한 서버 오류" ERROR 가 남아 모니터링을 오염시켰다.
        // 지금은 부모가 곧장 406 으로 매핑하고 로그도 warn 한 줄로 조용하다.
        //
        // 본문이 비는 것은 고친 뒤에도 마찬가지다 - 클라이언트가 받지 않겠다고 선언한 형식으로는
        // ApiResponse 봉투를 실어 보낼 수 없다. 그래서 이 케이스만 봉투를 기대하지 않는다.
        @Test
        @DisplayName("Accept 헤더가 응답 형식과 안 맞으면 406 이고 본문은 없다 - HttpMediaTypeNotAcceptableException")
        void mediaTypeNotAcceptable() throws Exception {
            mockMvc.perform(get("/probe/search")
                            .param("keyword", "커피")
                            .accept(MediaType.APPLICATION_XML))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(content().string(""));
        }
    }

    // 예외를 하나씩 유발하기 위한 최소 엔드포인트. 응답 본문은 검증 대상이 아니다 -
    // 여기서 보는 것은 "정상 경로"가 아니라 각 요청 오류가 advice 를 지나 어떤 상태 코드로 나가는가다.
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        record ProbeBody(String name) {
        }

        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        ApiResponse<Map<String, String>> body(@RequestBody ProbeBody request) {
            return ApiResponse.success(Map.of("name", request.name()));
        }

        @GetMapping("/items/{id}")
        ApiResponse<Map<String, Long>> item(@PathVariable Long id) {
            return ApiResponse.success(Map.of("id", id));
        }

        @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
        ApiResponse<Map<String, String>> search(@RequestParam String keyword) {
            return ApiResponse.success(Map.of("keyword", keyword));
        }

        // 우리 규약(BusinessException + ErrorCode)을 타는 경로. 응답 code 가 도메인 상수명이어야 한다.
        @GetMapping("/business")
        void business() {
            throw new BusinessException(CompanyErrorCode.VERIFICATION_EXPIRED);
        }

        // 본문 검증 실패 경로. code 는 항목이 아니라 폼 전체의 판정(INVALID_INPUT)이어야 한다.
        record ValidatedBody(@NotBlank(message = "이름은 필수입니다.") String name) {
        }

        @PostMapping(value = "/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        void validated(@Valid @RequestBody ValidatedBody body) {
        }

        @GetMapping("/declared-status")
        ApiResponse<Void> declaredStatus() {
            throw new DeclaredStatusException();
        }

        @GetMapping("/declared-reason")
        ApiResponse<Void> declaredReason() {
            throw new DeclaredReasonException();
        }

        @PostMapping("/too-large")
        ApiResponse<Void> tooLarge() {
            throw new MaxUploadSizeExceededException(10L);
        }

        // 락 대기 상한(Postgres lock_timeout)을 넘겨 락을 못 잡은 경로. 스프링이 SQLState 55P03 을 이 예외로 옮긴다.
        @GetMapping("/lock-busy")
        ApiResponse<Void> lockBusy() {
            throw new CannotAcquireLockException("could not obtain lock on row in relation \"company\"");
        }

        @PostMapping("/part")
        ApiResponse<Map<String, String>> part(@RequestPart MultipartFile file) {
            return ApiResponse.success(Map.of("name", file.getName()));
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class DeclaredStatusException extends RuntimeException {
    }

    @ResponseStatus(code = HttpStatus.GONE, reason = "만료된 초대입니다.")
    static class DeclaredReasonException extends RuntimeException {
    }

    // 응답의 code 는 클라이언트가 분기에 쓰는 유일한 값이다(error 는 사람이 읽는 문구다). 그래서
    // "코드가 붙는가" 가 아니라 "경로마다 옳은 코드가 붙는가" 를 본다 — 여기 실린 넷은 출처가 전부 다르다:
    // 도메인 예외(ErrorCode 직접) / 검증(폼 전체 판정) / 상태만 선언된 예외(상태→코드) / 표준 MVC 예외(부모 경유).
    // 한 곳이라도 코드를 빠뜨리면 그 경로만 클라이언트가 분기할 수 없게 되고, 그건 응답을 봐야만 드러난다.
    @Nested
    @DisplayName("락 경합 - 서버 오류가 아니라 다시 시도할 수 있는 충돌이다")
    class LockContention {

        @Test
        @DisplayName("락 대기 상한을 넘겨 락을 못 잡으면 500 이 아니라 409(RESOURCE_BUSY) 다")
        void lockNotAcquired_isConflict() throws Exception {
            mockMvc.perform(get("/probe/lock-busy"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("RESOURCE_BUSY"))
                    .andExpect(jsonPath("$.error").value(not(FALLBACK_MESSAGE)));
        }
    }

    @Nested
    @DisplayName("응답 code - 경로마다 옳은 식별자가 실리는가")
    class ErrorCodes {

        @Test
        @DisplayName("도메인 예외는 ErrorCode 상수명을 그대로 싣는다")
        void businessException_carriesDomainCode() throws Exception {
            mockMvc.perform(get("/probe/business"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CompanyErrorCode.VERIFICATION_EXPIRED.name()))
                    // 코드와 문구가 같은 출처에서 나온다 — 둘이 어긋날 자리가 없다.
                    .andExpect(jsonPath("$.error").value(CompanyErrorCode.VERIFICATION_EXPIRED.getMessage()));
        }

        @Test
        @DisplayName("검증 실패의 code 는 폼 전체 판정(INVALID_INPUT)이고, 어느 칸인지는 errors 가 말한다")
        void validationFailure_carriesInvalidInput() throws Exception {
            mockMvc.perform(post("/probe/validated")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CommonErrorCode.INVALID_INPUT.name()))
                    // error 에는 첫 위반 문구가, errors 에는 칸 이름이 실린다.
                    .andExpect(jsonPath("$.error").value("이름은 필수입니다."))
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
        }

        // 규약(BusinessException + ErrorCode) 밖 예외라 실어 보낼 도메인 코드가 없다. 상태에서 고른 코드라도
        // 붙어야 "클라이언트가 분기할 수 없는 응답" 이 하나도 남지 않는다.
        @Test
        @DisplayName("상태만 선언된 규약 밖 예외도 상태에서 고른 code 를 받는다 (409 가 INVALID_INPUT 으로 뭉개지지 않는다)")
        void declaredStatusException_carriesStatusCode() throws Exception {
            mockMvc.perform(get("/probe/declared-status"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(CommonErrorCode.CONFLICT.name()));
        }

        @Test
        @DisplayName("표준 MVC 예외(부모 경유)도 같은 봉투에 code 를 싣는다")
        void standardMvcException_carriesStatusCode() throws Exception {
            mockMvc.perform(delete("/probe/search"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(CommonErrorCode.METHOD_NOT_ALLOWED.name()));
        }

        @Test
        @DisplayName("성공 응답에는 code 가 없다 (성공은 갈래가 하나라 분기할 것이 없다)")
        void successResponse_hasNoCode() throws Exception {
            mockMvc.perform(get("/probe/items/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").doesNotExist());
        }
    }
}
