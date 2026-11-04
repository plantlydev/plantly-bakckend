package project.plantly.companyTest.companyControllerTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import project.plantly.companyTest.support.CompanyApiDocs;
import project.plantly.domain.company.controller.CompanyController;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.service.CompanyQueryService;
import project.plantly.domain.company.service.CompanyDraftService;
import project.plantly.domain.company.service.CompanyStatsService;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.domain.company.service.CompanyRegistrationService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.security.UserPrincipal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 유저(소유자) 수정 API 컨트롤러 슬라이스 테스트 — 라우팅/검증/인가 매핑/응답 형태(ok())를 서비스 목킹으로 검증하고,
// 성공/예외 응답을 REST Docs 스니펫으로 문서화한다.
// 실제 수정 동작(전체 교체·삭제 스코프·검색 재동기화)은 실DB 통합 테스트(후속)가 담당한다.
@ActiveProfiles("test")
@WebMvcTest(controllers = CompanyController.class)
@ExtendWith(RestDocumentationExtension.class)
@Import(CompanyUpdateControllerTest.MethodSecurityTestConfig.class)
public class CompanyUpdateControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private CompanyUpdateService companyUpdateService;

    // 컨트롤러가 함께 주입받는 협력 객체. 이 테스트에서 직접 쓰진 않지만 컨텍스트 로딩을 위해 모킹한다.
    @MockitoBean
    private CompanyRegistrationService companyRegistrationService;

    @MockitoBean
    private CompanyQueryService companyQueryService;

    @MockitoBean
    private CompanyVerificationService companyVerificationService;

    @MockitoBean
    private CompanyDraftService companyDraftService;

    // 이 테스트는 수정 경로만 보지만 슬라이스가 CompanyController 전체를 띄우므로,
    // 컨트롤러가 주입받는 협력 객체는 쓰지 않아도 전부 모킹해야 컨텍스트가 뜬다.
    @MockitoBean
    private CompanyStatsService companyStatsService;

    private MockMvc mockMvc;

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
    @DisplayName("소유자가 기본 정보를 부분 수정하면 200 과 본문 없는 성공 응답(ok)만 반환한다")
    void updateBasicInfo_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/companies/{id}", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"introTitle\":\"새 한줄 요약\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andDo(document("company-update",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.companyUpdateRequestFields()),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).updateBasicInfoByUser(eq(9L), eq(7L), any(CompanyUpdateRequest.class));
    }

    @Test
    @DisplayName("필수 필드를 빈 문자열로 비우려 하면 400(@Size(min=1) 위반) 을 반환한다")
    void updateBasicInfo_blankRequiredField_validationError() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/companies/{id}", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists())
                .andDo(document("company-update-validation-error",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    // 브랜드 컬러는 화면이 CSS 색상으로 그대로 쓰는 값이라(메인 스팟라이트 배너 배경) 형식을 DTO 에서 강제한다.
    // 색 이름·축약형·rgb() 를 허용하면 저장 표기가 갈라져 프론트의 명도 계산이 분기해야 한다.
    @Test
    @DisplayName("브랜드 컬러가 #RRGGBB 형식이 아니면 400 을 반환한다")
    void updateBasicInfo_malformedBrandColor_validationError() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/companies/{id}", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandColor\":\"red\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(companyUpdateService, never()).updateBasicInfoByUser(any(), any(), any());
    }

    // 로고(@Size(min=1))와 달리 커버·브랜드 컬러는 선택 필드라 ""(비우기)가 정상 입력이다.
    @Test
    @DisplayName("커버 이미지·브랜드 컬러는 빈 문자열로 비울 수 있다")
    void updateBasicInfo_clearsOptionalImageAndColor() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/companies/{id}", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coverImageUrl\":\"\",\"brandColor\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(companyUpdateService).updateBasicInfoByUser(eq(9L), eq(7L), any(CompanyUpdateRequest.class));
    }

    @Test
    @DisplayName("소유자가 아닌 유저가 수정하면 403(COMPANY_ACCESS_DENIED) 를 반환한다")
    void updateBasicInfo_notOwner_forbidden() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        willThrow(new BusinessException(CompanyErrorCode.COMPANY_ACCESS_DENIED))
                .given(companyUpdateService).updateBasicInfoByUser(eq(9L), eq(7L), any(CompanyUpdateRequest.class));

        mockMvc.perform(patch("/api/v1/companies/{id}", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"introTitle\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("해당 회사에 대한 접근 권한이 없습니다."))
                .andDo(document("company-update-forbidden",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("존재하지 않는 회사를 수정하면 404(COMPANY_NOT_FOUND) 를 반환한다")
    void updateBasicInfo_notFound() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        willThrow(new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND))
                .given(companyUpdateService).updateBasicInfoByUser(eq(404L), eq(7L), any(CompanyUpdateRequest.class));

        mockMvc.perform(patch("/api/v1/companies/{id}", 404L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"introTitle\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("존재하지 않는 회사입니다."))
                .andDo(document("company-update-not-found",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("소유자가 공개/비공개를 전환하면 200 ok 를 반환하고 서비스에 위임한다")
    void changeVisibility_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/companies/{id}/visibility", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("company-update-visibility",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.companyVisibilityUpdateRequestFields()),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).changeVisibilityByUser(eq(9L), eq(7L), eq(CompanyVisibility.PRIVATE));
    }

    @Test
    @DisplayName("visibility 가 없으면 400(@NotNull 위반) 을 반환한다")
    void changeVisibility_missingValue_validationError() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/companies/{id}/visibility", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("소유자가 본인 회사를 삭제하면 200 ok 를 반환하고 자가삭제 서비스에 위임한다")
    void deleteMyCompany_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(delete("/api/v1/companies/{id}", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("company-delete",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).deleteByUser(eq(9L), eq(7L));
    }

    @Test
    @DisplayName("소유자가 아닌 유저가 삭제하면 403(COMPANY_ACCESS_DENIED) 를 반환한다")
    void deleteMyCompany_notOwner_forbidden() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        willThrow(new BusinessException(CompanyErrorCode.COMPANY_ACCESS_DENIED))
                .given(companyUpdateService).deleteByUser(eq(9L), eq(7L));

        mockMvc.perform(delete("/api/v1/companies/{id}", 9L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("해당 회사에 대한 접근 권한이 없습니다."))
                .andDo(document("company-delete-forbidden",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("소유자가 태그를 전체 교체하면 200 ok 를 반환하고 서비스에 위임한다")
    void replaceTags_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/{id}/tags", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"정밀\",\"자동화\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("company-update-tags",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.replaceListRequestFields("교체할 태그명 목록 (빈 배열이면 전부 비움)")),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).replaceTagsByUser(eq(9L), eq(7L), any());
    }

    @Test
    @DisplayName("소유자가 카테고리 링크를 전체 교체하면 200 ok 를 반환한다")
    void replaceCategories_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/{id}/categories", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("company-update-categories",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.replaceListRequestFields(
                                "교체할 카테고리 ID 목록 (최대 10개, 빈 배열이면 전부 비움)")),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).replaceCategoriesByUser(eq(9L), eq(7L), any());
    }

    @Test
    @DisplayName("갤러리에 DETAIL 외 타입이 섞이면 400(GALLERY_IMAGE_TYPE_NOT_ALLOWED) 를 반환한다")
    void replaceGalleryImages_nonDetailType_badRequest() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        willThrow(new BusinessException(CompanyErrorCode.GALLERY_IMAGE_TYPE_NOT_ALLOWED))
                .given(companyUpdateService).replaceGalleryImagesByUser(eq(9L), eq(7L), any());

        mockMvc.perform(put("/api/v1/companies/{id}/images", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"imageUrl\":\"https://cdn/x.png\",\"imageType\":\"PROJECT\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("갤러리에는 상세 이미지(DETAIL)만 등록할 수 있습니다."))
                .andDo(document("company-update-images-type-error",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    // 1건 상한이 이제 요청 DTO 에서 먼저 걸린다 — 등록 경로와 같은 제약을 수정 경로에도 걸었기 때문이다.
    // 서비스의 writer 가드(CONTACT_LIMIT_EXCEEDED)는 남아 있지만 이 경로로는 도달하지 않는다.
    // 검증에서 끊는 쪽이 낫다: 응답에 field 경로가 실려 화면이 어느 항목을 지적할지 알 수 있다.
    @Test
    @DisplayName("연락처를 1건 초과로 교체하면 400 을 반환한다")
    void replaceContacts_overLimit_badRequest() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/{id}/contacts", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"contactName\":\"a\"},{\"contactName\":\"b\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("연락처는 현재 1건만 등록할 수 있습니다."))
                .andDo(document("company-update-contacts-limit-error",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    // 컬렉션 개수 한도는 서비스 정책이 아니라 요청 DTO 의 @Size(CompanyConstraints 의 개수 상한) 가 건다.
    // 서비스에 닿기 전에 끊기는지(위임이 일어나지 않는지)까지 본다 — 서비스 쪽에는 더 이상 한도 검사가 없다.
    @Test
    @DisplayName("카테고리를 10개 넘게 교체하면 400 을 반환하고 서비스에 위임하지 않는다")
    void replaceCategories_overMax_badRequest() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/{id}/categories", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3,4,5,6,7,8,9,10,11]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(companyUpdateService, never()).replaceCategoriesByUser(any(), any(), any());
    }

    @Test
    @DisplayName("상세 이미지를 30장 넘게 교체하면 400 을 반환하고 서비스에 위임하지 않는다")
    void replaceGalleryImages_overMax_badRequest() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        String images = java.util.stream.IntStream.rangeClosed(1, 31)
                .mapToObj(i -> "{\"imageUrl\":\"https://cdn/" + i + ".png\",\"imageType\":\"DETAIL\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        mockMvc.perform(put("/api/v1/companies/{id}/images", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(images))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(companyUpdateService, never()).replaceGalleryImagesByUser(any(), any(), any());
    }

    @Test
    @DisplayName("레퍼런스 1건의 이미지를 10장 넘게 교체하면 400 을 반환하고 서비스에 위임하지 않는다")
    void replaceReferences_imagesOverMax_badRequest() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        String urls = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "\"https://cdn/r" + i + ".png\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        mockMvc.perform(put("/api/v1/companies/{id}/references", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"projectTitle\":\"p\",\"imageUrls\":" + urls + "}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(companyUpdateService, never()).replaceReferencesByUser(any(), any(), any());
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
