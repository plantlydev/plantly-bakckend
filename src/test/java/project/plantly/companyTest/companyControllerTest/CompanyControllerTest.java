package project.plantly.companyTest.companyControllerTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import project.plantly.companyTest.support.CompanyApiDocs;
import project.plantly.companyTest.support.CompanyCreateRequestSamples;
import project.plantly.companyTest.support.CompanyResponseSamples;
import project.plantly.domain.company.controller.CompanyController;
import project.plantly.domain.company.dto.CompanyDraftResponse;
import project.plantly.domain.company.dto.CompanyDraftSummary;
import project.plantly.domain.company.dto.CompanyShowcaseResponse;
import project.plantly.domain.company.dto.CompanyStatsResponse;
import project.plantly.domain.company.dto.CompanyReverificationRequest;
import project.plantly.domain.company.dto.CompanyReverificationResponse;
import project.plantly.domain.company.dto.CompanyVerificationRequest;
import project.plantly.domain.company.dto.CompanyVerificationResponse;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.search.CompanySearchCriteria;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.search.dto.CompanySummary;
import project.plantly.domain.company.service.CompanyDraftService;
import project.plantly.domain.company.service.CompanyStatsService;
import project.plantly.domain.company.service.CompanyQueryService;
import project.plantly.domain.company.service.CompanyRegistrationService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.global.PageInfo;
import project.plantly.global.PageResponse;
import project.plantly.global.exception.BusinessException;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.global.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.relaxedResponseFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 유저 자가등록 컨트롤러 슬라이스 테스트 (REST Docs 문서화 목적, 성공 케이스 중심).
@ActiveProfiles("test")
@WebMvcTest(controllers = CompanyController.class)
@ExtendWith(RestDocumentationExtension.class)
@Import(CompanyControllerTest.MethodSecurityTestConfig.class)
public class CompanyControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    // 자가등록 진입점. 컨트롤러는 CompanyService 가 아니라 이 빈을 부른다 — 만료 인증 자동 재질의(국세청 HTTP)를
    // 등록 트랜잭션 밖에서 끝내기 위한 경계라서다(CompanyRegistrationService 주석).
    @MockitoBean
    private CompanyRegistrationService companyRegistrationService;

    // 컨트롤러가 상세 조회용으로 주입받는 협력 객체. 등록 슬라이스 테스트에서는 사용하지 않지만 컨텍스트 로딩을 위해 모킹한다.
    @MockitoBean
    private CompanyQueryService companyQueryService;

    // 컨트롤러가 수정용으로 주입받는 협력 객체. 이 테스트에서는 사용하지 않지만 컨텍스트 로딩을 위해 모킹한다.
    @MockitoBean
    private CompanyUpdateService companyUpdateService;

    @MockitoBean
    private CompanyVerificationService companyVerificationService;

    // 컨트롤러가 임시저장용으로 주입받는 협력 객체. 초안 슬라이스 테스트에서 사용하며, 나머지 테스트는 컨텍스트 로딩용으로만 둔다.
    @MockitoBean
    private CompanyDraftService companyDraftService;

    // 현황 지표용. 이 테스트에서는 stats 슬라이스에서만 쓰고, 나머지는 컨텍스트 로딩용으로 둔다.
    @MockitoBean
    private CompanyStatsService companyStatsService;

    private MockMvc mockMvc;

    // 초안 경로 키. 인증 식별자가 아니라 사업자등록번호이며, 정규화(하이픈 제거)는 서비스가 한다.
    private static final String BUSINESS_NUMBER = "1234567890";

    @BeforeEach
    void setUpMockMvc(RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .build();
    }

    @Test
    @DisplayName("사업자 인증에 성공하면 verificationId 와 검증된 값들을 반환한다")
    void verifyBusiness_success() throws Exception {
        CompanyVerificationRequest request = CompanyCreateRequestSamples.verificationRequest();
        given(companyVerificationService.verify(eq(7L), any(CompanyVerificationRequest.class)))
                .willReturn(new CompanyVerificationResponse(99L, "1234567890", "김대표",
                        LocalDate.of(2020, 1, 15), LocalDateTime.of(2026, 7, 19, 12, 30)));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.verificationId").value(99L))
                // 하이픈을 넣어 보내도 정규화된 값으로 돌아온다.
                .andExpect(jsonPath("$.data.businessNumber").value("1234567890"))
                .andDo(document("company-verification",
                        requestFields(CompanyApiDocs.verificationRequestFields()),
                        responseFields(CompanyApiDocs.verificationResponseFields())));
    }

    @Test
    @DisplayName("국세청 정보와 일치하지 않으면 400(VERIFICATION_MISMATCH) 을 반환한다")
    void verifyBusiness_mismatch() throws Exception {
        given(companyVerificationService.verify(eq(7L), any(CompanyVerificationRequest.class)))
                .willThrow(new BusinessException(CompanyErrorCode.VERIFICATION_MISMATCH));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompanyCreateRequestSamples.verificationRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andDo(document("company-verification-mismatch",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("국세청 장애 시 503 으로 안내한다 — 입력 오류와 구분되어야 한다")
    void verifyBusiness_ntsUnavailable() throws Exception {
        given(companyVerificationService.verify(eq(7L), any(CompanyVerificationRequest.class)))
                .willThrow(new BusinessException(CompanyErrorCode.VERIFICATION_UNAVAILABLE));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompanyCreateRequestSamples.verificationRequest())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("일일 인증 시도 한도를 넘기면 429 를 반환한다")
    void verifyBusiness_limitExceeded() throws Exception {
        given(companyVerificationService.verify(eq(7L), any(CompanyVerificationRequest.class)))
                .willThrow(new BusinessException(CompanyErrorCode.VERIFICATION_LIMIT_EXCEEDED));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompanyCreateRequestSamples.verificationRequest())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("사업자 재인증에 성공하면 갱신된 검증값과 재인증 시각을 반환한다")
    void reverifyBusiness_success() throws Exception {
        CompanyReverificationRequest request = CompanyCreateRequestSamples.reverificationRequest();
        given(companyVerificationService.reverify(eq(7L), eq(42L), any(CompanyReverificationRequest.class)))
                .willReturn(new CompanyReverificationResponse("1234567890", "김신임",
                        LocalDate.of(2021, 3, 4), LocalDateTime.of(2026, 7, 21, 10, 0)));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies/{id}/verification", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 사업자번호는 요청 본문에 없지만, 저장값으로 재인증되어 응답에 그대로 실린다.
                .andExpect(jsonPath("$.data.businessNumber").value("1234567890"))
                .andExpect(jsonPath("$.data.ceoName").value("김신임"))
                .andDo(document("company-reverification",
                        pathParameters(parameterWithName("id").description("재인증할 회사 id")),
                        requestFields(CompanyApiDocs.reverificationRequestFields()),
                        responseFields(CompanyApiDocs.reverificationResponseFields())));
    }

    @Test
    @DisplayName("국세청 인증을 받지 않은 회사를 재인증하면 400(COMPANY_NOT_BUSINESS_VERIFIED) 을 반환한다")
    void reverifyBusiness_notVerified() throws Exception {
        given(companyVerificationService.reverify(eq(7L), eq(42L), any(CompanyReverificationRequest.class)))
                .willThrow(new BusinessException(CompanyErrorCode.COMPANY_NOT_BUSINESS_VERIFIED));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies/{id}/verification", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompanyCreateRequestSamples.reverificationRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("인증된 유저가 회사를 등록하면 201 Created 와 생성된 회사 id 를 반환한다")
    void createMyCompany_success() throws Exception {
        MyCompanyCreateRequest request = CompanyCreateRequestSamples.myFull();
        given(companyRegistrationService.register(eq(7L), any(MyCompanyCreateRequest.class))).willReturn(100L);
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회사 등록이 완료되었습니다."))
                .andExpect(jsonPath("$.data.id").value(100L))
                .andDo(document("company-create",
                        requestFields(CompanyApiDocs.myCompanyCreateRequestFields()),
                        responseFields(CompanyApiDocs.idResponseFields())));
    }

    @Test
    @DisplayName("필수 항목(companyName)이 비어 있으면 400 검증 오류를 반환한다")
    void createMyCompany_validationError() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        // companyName 만 공란(@NotBlank 위반), 나머지는 모두 선택이라 단일 검증 오류만 발생한다.
        String invalidJson = """
                {"companyName":"","ceoName":"김대표"}
                """;

        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists())
                .andDo(document("company-create-validation-error",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("등록 정책(구조 검증/마스터 유효성 등)에 걸리면 400(정책 위반 메시지) 를 반환한다")
    void createMyCompany_policyViolation() throws Exception {
        // 정책 위반은 서비스가 BusinessException(400)으로 던진다. 대표로 갤러리 이미지 타입 위반을 사용한다.
        given(companyRegistrationService.register(eq(7L), any(MyCompanyCreateRequest.class)))
                .willThrow(new BusinessException(CompanyErrorCode.GALLERY_IMAGE_TYPE_NOT_ALLOWED));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompanyCreateRequestSamples.myFull())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("갤러리에는 상세 이미지(DETAIL)만 등록할 수 있습니다."))
                .andDo(document("company-create-policy-violation",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    // ===== 임시저장(초안) PUT/GET/DELETE /api/v1/companies/drafts/{businessNumber} =====
    // 경로 키는 인증 식별자가 아니라 사업자등록번호다 — 인증은 만료·재발급되며 id 가 바뀌지만 작성분은
    // 그 수명에 묶이면 안 되기 때문이다(CompanyDraft 주석). 하이픈 유무는 서버가 정규화한다.

    @Test
    @DisplayName("임시저장은 자가등록 폼을 사업자번호 1건당 1개 보관하고 성공 플래그만 반환한다 (개수 상한 외 검증 없이 부분 입력 허용)")
    void saveDraft_success() throws Exception {
        // save 는 void — 기본 mock 이 삼킨다. 경로의 사업자번호와 인증 principal(7)이 서비스로 전달되는지 verify 로 확인한다.
        MyCompanyCreateRequest request = CompanyCreateRequestSamples.myFull();
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/drafts/{businessNumber}", BUSINESS_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("company-draft-save",
                        pathParameters(parameterWithName("businessNumber").description(
                                "사업자등록번호(하이픈 유무 무관). 인증 식별자가 아니라 이 값이 초안의 키다")),
                        requestFields(CompanyApiDocs.myCompanyCreateRequestFields()),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyDraftService).save(eq(7L), eq(BUSINESS_NUMBER), any(MyCompanyCreateRequest.class));
    }

    @Test
    @DisplayName("내 초안 목록은 이어쓸 초안을 사업자번호·회사명·저장 시각으로 최근순 반환한다 (폼 전체는 싣지 않는다)")
    void listDrafts_success() throws Exception {
        given(companyDraftService.list(7L)).willReturn(List.of(
                new CompanyDraftSummary(BUSINESS_NUMBER, "플랜틀리", LocalDateTime.of(2026, 7, 23, 10, 0)),
                new CompanyDraftSummary("2208162517", null, LocalDateTime.of(2026, 7, 21, 18, 5))));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/companies/drafts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].businessNumber").value(BUSINESS_NUMBER))
                .andExpect(jsonPath("$.data[0].companyName").value("플랜틀리"))
                .andExpect(jsonPath("$.data[0].updatedAt").value("2026-07-23T10:00:00"))
                // 회사명을 아직 안 쓴 초안 — 화면은 사업자번호로 부른다.
                .andExpect(jsonPath("$.data[1].companyName").isEmpty())
                .andDo(document("company-draft-list",
                        responseFields(CompanyApiDocs.companyDraftListResponseFields())));
    }

    @Test
    @DisplayName("재진입 시 초안 조회는 저장했던 폼 상태(payload)·마지막 저장 시각·이어쓸 인증을 반환한다")
    void getDraft_success() throws Exception {
        CompanyVerificationResponse verification = new CompanyVerificationResponse(
                123L, BUSINESS_NUMBER, "홍길동", LocalDate.of(2018, 3, 2), LocalDateTime.of(2026, 7, 27, 9, 0));
        CompanyDraftResponse response = new CompanyDraftResponse(
                CompanyCreateRequestSamples.myFull(), LocalDateTime.of(2026, 7, 23, 10, 0), verification);
        given(companyDraftService.get(7L, BUSINESS_NUMBER)).willReturn(response);
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/companies/drafts/{businessNumber}", BUSINESS_NUMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.payload.verificationId").value(99L))
                .andExpect(jsonPath("$.data.payload.companyName").value("플랜틀리"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-07-23T10:00:00"))
                .andExpect(jsonPath("$.data.verification.verificationId").value(123L))
                // payload 내부 필드는 회사 등록 요청과 동일하므로 relaxed 로 봉투·저장시각만 문서화한다(중복 방지).
                .andDo(document("company-draft-get",
                        pathParameters(parameterWithName("businessNumber").description(
                                "사업자등록번호(하이픈 유무 무관). 인증 식별자가 아니라 이 값이 초안의 키다")),
                        relaxedResponseFields(CompanyApiDocs.companyDraftResponseFields())));
    }

    @Test
    @DisplayName("저장된 초안이 없으면 404(DRAFT_NOT_FOUND) 를 반환한다")
    void getDraft_notFound() throws Exception {
        given(companyDraftService.get(7L, BUSINESS_NUMBER))
                .willThrow(new BusinessException(CompanyErrorCode.DRAFT_NOT_FOUND));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/companies/drafts/{businessNumber}", BUSINESS_NUMBER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("저장된 임시저장 내역이 없습니다."))
                .andDo(document("company-draft-not-found",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("이미 등록에 소비된 인증에 임시저장하면 409(VERIFICATION_ALREADY_USED) 를 반환한다")
    void saveDraft_alreadyUsed() throws Exception {
        willThrow(new BusinessException(CompanyErrorCode.VERIFICATION_ALREADY_USED))
                .given(companyDraftService).save(eq(7L), eq(BUSINESS_NUMBER), any(MyCompanyCreateRequest.class));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/drafts/{businessNumber}", BUSINESS_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompanyCreateRequestSamples.myFull())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("이미 회사 등록에 사용된 사업자 인증입니다."))
                .andDo(document("company-draft-already-used",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("임시저장 폐기는 성공 플래그만 반환한다 (초안이 없어도 멱등하게 성공)")
    void deleteDraft_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(delete("/api/v1/companies/drafts/{businessNumber}", BUSINESS_NUMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("company-draft-delete",
                        pathParameters(parameterWithName("businessNumber").description(
                                "사업자등록번호(하이픈 유무 무관). 인증 식별자가 아니라 이 값이 초안의 키다")),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyDraftService).delete(eq(7L), eq(BUSINESS_NUMBER));
    }

    @Test
    @DisplayName("공개 상세 조회는 인증 없이도 공개 프로필(meta 제외)을 반환한다")
    void getCompany_public_success() throws Exception {
        given(companyQueryService.getPublic(1L, null)).willReturn(CompanyResponseSamples.fullPublic());

        mockMvc.perform(get("/api/v1/companies/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.companyName").value("플랜틀리테크"))
                .andExpect(jsonPath("$.data.representativeContact.contactName").value("이담당"))
                .andDo(document("company-public-detail",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        responseFields(CompanyApiDocs.companyPublicResponseFields())));
    }

    @Test
    @DisplayName("소유자 전용 조회는 멤버 본인에게 profile + 내부·운영 meta 를 반환한다")
    void getMyCompany_owner_success() throws Exception {
        given(companyQueryService.getOwnerView(eq(9L), eq(7L)))
                .willReturn(CompanyResponseSamples.fullDetail());
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/companies/{id}/private", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.profile.companyName").value("플랜틀리테크"))
                .andExpect(jsonPath("$.data.meta.businessNumber").value("1234567890"))
                .andExpect(jsonPath("$.data.meta.ownerUserId").value(7L))
                .andDo(document("company-owner-detail",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        responseFields(CompanyApiDocs.companyDetailResponseFields())));
    }

    @Test
    @DisplayName("공개 조회 대상 회사가 없거나 삭제됐으면 404(COMPANY_NOT_FOUND) 를 반환한다")
    void getCompany_notFound() throws Exception {
        given(companyQueryService.getPublic(404L, null))
                .willThrow(new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/companies/{id}", 404L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("존재하지 않는 회사입니다."))
                .andDo(document("company-public-detail-not-found",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("소유자 전용 조회를 멤버가 아닌 유저가 호출하면 403(COMPANY_ACCESS_DENIED) 를 반환한다")
    void getMyCompany_accessDenied() throws Exception {
        given(companyQueryService.getOwnerView(eq(9L), eq(7L)))
                .willThrow(new BusinessException(CompanyErrorCode.COMPANY_ACCESS_DENIED));
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/companies/{id}/private", 9L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("해당 회사에 대한 접근 권한이 없습니다."))
                .andDo(document("company-owner-detail-forbidden",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("공개 회사 목록/검색은 인증 없이 페이징된 요약 카드를 반환한다")
    void searchCompanies_public_success() throws Exception {
        CompanySummary item = new CompanySummary(1L, "플랜틀리", "스마트팜 솔루션",
                "https://cdn/logo.png", "https://cdn/cover.png", "#2E7D32", "서울 강남구", true, false, true,
                List.of("제조", "정밀가공"), List.of("스마트팜", "IoT"), List.of("농업기술"),
                true, false,    // likedByMe, favoritedByMe
                null, null, null, null);   // 공개 목록은 소유자 전용 값을 싣지 않는다 (CompanySummary.fromPublic)
        PageResponse<CompanySummary> page = new PageResponse<>(List.of(item), new PageInfo(1, 20, 1, 1));
        given(companyQueryService.search(any(CompanySearchCriteria.class), any(Pageable.class), isNull())).willReturn(page);

        mockMvc.perform(get("/api/v1/companies?keyword=스마트팜&categoryIds=1&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1L))
                .andExpect(jsonPath("$.data.content[0].companyName").value("플랜틀리"))
                .andExpect(jsonPath("$.data.content[0].spotlight").value(true))
                .andExpect(jsonPath("$.data.content[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.content[0].favoritedByMe").value(false))
                .andExpect(jsonPath("$.data.content[0].categoryNames[0]").value("제조"))
                .andExpect(jsonPath("$.data.content[0].tagNames[1]").value("IoT"))
                .andExpect(jsonPath("$.data.content[0].industryNames[0]").value("농업기술"))
                // 소유자 전용 값은 null 이면 키째 빠진다 — 공개 계약에 상수·빈 필드를 늘리지 않는다.
                .andExpect(jsonPath("$.data.content[0].visibility").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].businessVerified").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].updatedAt").doesNotExist())
                .andExpect(jsonPath("$.data.pageInfo.totalElement").value(1))
                .andDo(document("company-search",
                        queryParameters(CompanyApiDocs.companySearchQueryParameters()),
                        responseFields(CompanyApiDocs.companySearchResponseFields())));
    }

    @Test
    @DisplayName("메인 화면 노출 영역은 인증 없이 스팟라이트·추천·최근 등록 세 레일을 반환한다 (페이지가 아니라 고정 리스트)")
    void getShowcase_public_success() throws Exception {
        // 스팟라이트 레일은 관리자가 고정(pin)한 회사만 담는다.
        CompanySummary pinned = new CompanySummary(1L, "고정노출사", "정밀 부품",
                "https://cdn/logo1.png", "https://cdn/cover1.png", "#2E7D32", "서울 강남구", true, false, true,
                List.of("제조"), List.of("정밀가공"), List.of("기계"),
                false, false, null, null, null, null);
        // 커버·브랜드 컬러는 선택 필드다. 둘 다 비운 카드를 섞어 레일이 null 을 그대로 통과시키는지 함께 확인한다
        // (프론트가 자리표시자·기본 배너 색으로 폴백하는 근거).
        CompanySummary recommended = new CompanySummary(2L, "추천사", "스마트팜 솔루션",
                "https://cdn/logo2.png", null, null, "경기 화성시", true, true, false,
                List.of("농업"), List.of("IoT"), List.of("농업기술"),
                true, false, null, null, null, null);
        // 최근 등록 레일은 큐레이션을 보지 않는다 — verified/featured/spotlight 가 전부 꺼진 평범한
        // 회사가 올라오는 것이 정상이고, 그게 이 레일이 위 둘과 다른 지면이라는 표시다.
        CompanySummary newcomer = new CompanySummary(3L, "신규등록사", "금형 설계",
                "https://cdn/logo3.png", null, null, "인천 남동구", false, false, false,
                List.of("제조"), List.of("금형"), List.of("기계"),
                false, false, null, null, null, null);
        given(companyQueryService.getShowcase(isNull()))
                .willReturn(new CompanyShowcaseResponse(List.of(pinned), List.of(recommended), List.of(newcomer)));

        mockMvc.perform(get("/api/v1/companies/showcase"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.spotlight[0].id").value(1L))
                .andExpect(jsonPath("$.data.spotlight[0].companyName").value("고정노출사"))
                .andExpect(jsonPath("$.data.spotlight[0].spotlight").value(true))
                // 스팟라이트 카드는 로고 외에 커버(배경)와 브랜드 컬러(배너 색)를 함께 받는다.
                .andExpect(jsonPath("$.data.spotlight[0].coverImageUrl").value("https://cdn/cover1.png"))
                .andExpect(jsonPath("$.data.spotlight[0].brandColor").value("#2E7D32"))
                .andExpect(jsonPath("$.data.featured[0].id").value(2L))
                .andExpect(jsonPath("$.data.featured[0].featured").value(true))
                .andExpect(jsonPath("$.data.featured[0].coverImageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.featured[0].brandColor").doesNotExist())
                .andExpect(jsonPath("$.data.latest[0].id").value(3L))
                // 최근 등록은 플래그가 전부 꺼진 회사도 오른다 — 여기엔 큐레이션 개념이 없다.
                .andExpect(jsonPath("$.data.latest[0].verified").value(false))
                .andExpect(jsonPath("$.data.latest[0].featured").value(false))
                .andExpect(jsonPath("$.data.latest[0].spotlight").value(false))
                .andDo(document("company-showcase",
                        responseFields(CompanyApiDocs.companyShowcaseResponseFields())));
    }

    @Test
    @DisplayName("현황 지표는 인증 없이 네 개의 집계 숫자만 반환한다 (목록 없음)")
    void getStats_public_success() throws Exception {
        given(companyStatsService.getStats()).willReturn(new CompanyStatsResponse(45, 143, 24, 8));

        mockMvc.perform(get("/api/v1/companies/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.companyCount").value(45))
                // 대분류 개수가 아니라 공개 트리 전체 노드 수다(대+중+소).
                .andExpect(jsonPath("$.data.categoryCount").value(143))
                .andExpect(jsonPath("$.data.industryCount").value(24))
                .andExpect(jsonPath("$.data.certificationCount").value(8))
                // 개수만 필요한 화면이 목록을 받아 세지 않게 하려고 만든 엔드포인트다 — 카드가 실리면 목적이 사라진다.
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.pageInfo").doesNotExist())
                .andDo(document("company-stats",
                        responseFields(CompanyApiDocs.companyStatsResponseFields())));
    }

    @Test
    @DisplayName("내 회사 목록은 인증된 본인 소유 회사를 페이징된 요약 카드로 반환한다")
    void getMyCompanies_success() throws Exception {
        CompanySummary item = new CompanySummary(1L, "플랜틀리", "스마트팜 솔루션",
                "https://cdn/logo.png", "https://cdn/cover.png", "#2E7D32", "서울 강남구", true, false, true,
                List.of("제조", "정밀가공"), List.of("스마트팜", "IoT"), List.of("농업기술"),
                false, false,                  // 내 회사 목록은 개인화 미적용
                CompanyVisibility.PRIVATE,     // 소유자 목록만 공개 범위 등 관리 값을 싣는다 (fromOwner)
                true,                          // 국세청 확인 — 위의 verified(에디터 선정)와 다른 축
                LocalDateTime.of(2026, 7, 1, 9, 0), LocalDateTime.of(2026, 7, 20, 15, 30));
        PageResponse<CompanySummary> page = new PageResponse<>(List.of(item), new PageInfo(1, 20, 1, 1));
        given(companyQueryService.listMyCompanies(eq(7L), any(Pageable.class))).willReturn(page);
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/companies/my?page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1L))
                .andExpect(jsonPath("$.data.content[0].companyName").value("플랜틀리"))
                .andExpect(jsonPath("$.data.content[0].categoryNames[0]").value("제조"))
                .andExpect(jsonPath("$.data.content[0].tagNames[1]").value("IoT"))
                .andExpect(jsonPath("$.data.content[0].industryNames[0]").value("농업기술"))
                // 공개 목록과 갈리는 지점: 소유자 목록만 공개 범위를 받는다. 비공개 회사도 여기엔 보이므로
                // 목록에서 바로 공개 여부를 구분할 수 있어야 한다.
                .andExpect(jsonPath("$.data.content[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.content[0].businessVerified").value(true))
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-07-01T09:00:00"))
                .andExpect(jsonPath("$.data.content[0].updatedAt").value("2026-07-20T15:30:00"))
                .andExpect(jsonPath("$.data.pageInfo.totalElement").value(1))
                .andDo(document("company-my",
                        queryParameters(CompanyApiDocs.companyMyQueryParameters()),
                        responseFields(CompanyApiDocs.companySearchResponseFields())));
    }

    @Test
    @DisplayName("내 즐겨찾기 목록은 인증된 본인이 담은 회사를 즐겨찾기순 요약 카드로 반환한다")
    void getMyFavorites_success() throws Exception {
        // 즐겨찾기 목록이므로 favoritedByMe 는 정의상 true. likedByMe 는 카드마다 실제 값이 채워진다.
        CompanySummary item = new CompanySummary(1L, "플랜틀리", "스마트팜 솔루션",
                "https://cdn/logo.png", "https://cdn/cover.png", "#2E7D32", "서울 강남구", true, false, true,
                List.of("제조", "정밀가공"), List.of("스마트팜", "IoT"), List.of("농업기술"),
                true, true, null, null, null, null);
        PageResponse<CompanySummary> page = new PageResponse<>(List.of(item), new PageInfo(1, 20, 1, 1));
        given(companyQueryService.listMyFavorites(eq(7L), any(Pageable.class))).willReturn(page);
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/companies/favorites?page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1L))
                .andExpect(jsonPath("$.data.content[0].companyName").value("플랜틀리"))
                .andExpect(jsonPath("$.data.content[0].favoritedByMe").value(true))
                .andExpect(jsonPath("$.data.content[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.pageInfo.totalElement").value(1))
                .andDo(document("company-favorites",
                        queryParameters(CompanyApiDocs.companyMyQueryParameters()),
                        responseFields(CompanyApiDocs.companySearchResponseFields())));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long userId, UserRole role) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authOf(userId, role));
        SecurityContextHolder.setContext(securityContext);
    }

    private Authentication authOf(Long userId, UserRole role) {
        User user = BeanUtils.instantiateClass(User.class);
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "userStatus", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "userRole", role);
        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
