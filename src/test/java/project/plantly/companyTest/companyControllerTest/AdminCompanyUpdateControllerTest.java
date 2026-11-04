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
import project.plantly.domain.company.controller.AdminCompanyController;
import project.plantly.domain.company.dto.AdminCompanyFlagsRequest;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.global.exception.BusinessException;
import project.plantly.domain.company.service.CompanyQueryService;
import project.plantly.domain.company.service.CompanyService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.global.security.UserPrincipal;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 수정 API 컨트롤러 슬라이스 테스트 — 라우팅/응답 형태 + @PreAuthorize 권한 게이트(ADMIN)를 검증하고,
// 성공/권한 예외 응답을 REST Docs 스니펫으로 문서화한다. 요청/응답 형태는 유저 수정과 동일해 같은 디스크립터를 공유한다.
@ActiveProfiles("test")
@WebMvcTest(controllers = AdminCompanyController.class)
@ExtendWith(RestDocumentationExtension.class)
@Import(AdminCompanyUpdateControllerTest.MethodSecurityTestConfig.class)
public class AdminCompanyUpdateControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private CompanyUpdateService companyUpdateService;

    @MockitoBean
    private CompanyService companyService;

    @MockitoBean
    private CompanyQueryService companyQueryService;

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
    @DisplayName("관리자가 기본 정보를 수정하면 200 ok 를 반환하고 관리자 경로 서비스에 위임한다")
    void updateBasicInfoByAdmin_success() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"introTitle\":\"관리자 수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("admin-company-update",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.companyUpdateRequestFields()),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).updateBasicInfoByAdmin(eq(5L), any(CompanyUpdateRequest.class));
    }

    @Test
    @DisplayName("관리자가 아닌 유저가 기본 정보 수정을 호출하면 @PreAuthorize 가 막아 403 을 반환한다")
    void updateBasicInfoByAdmin_forbidden_forNonAdmin() throws Exception {
        authenticate(2L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"introTitle\":\"관리자 수정\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("접근 권한이 없습니다."))
                .andDo(document("admin-company-update-forbidden",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("관리자가 공개/비공개를 전환하면 200 ok 를 반환하고 관리자 경로 서비스에 위임한다")
    void changeVisibilityByAdmin_success() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}/visibility", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("admin-company-update-visibility",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.companyVisibilityUpdateRequestFields()),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).changeVisibilityByAdmin(eq(5L), eq(CompanyVisibility.PUBLIC));
    }

    @Test
    @DisplayName("관리자가 아닌 유저가 공개/비공개 전환을 호출하면 @PreAuthorize 가 막아 403 을 반환한다")
    void changeVisibilityByAdmin_forbidden_forNonAdmin() throws Exception {
        authenticate(2L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}/visibility", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("관리자가 운영 플래그(인증/추천/스팟라이트)를 조정하면 200 ok 를 반환하고 서비스에 위임한다")
    void changeFlagsByAdmin_success() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}/flags", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verified\":true,\"featured\":true,\"spotlight\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("admin-company-flags",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.adminCompanyFlagsRequestFields()),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).changeFlagsByAdmin(eq(5L), any(AdminCompanyFlagsRequest.class));
    }

    @Test
    @DisplayName("관리자가 아닌 유저가 운영 플래그 조정을 호출하면 @PreAuthorize 가 막아 403 을 반환한다")
    void changeFlagsByAdmin_forbidden_forNonAdmin() throws Exception {
        authenticate(2L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}/flags", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verified\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("관리자가 사업자 인증을 회수하면 200 ok 를 반환하고 사유와 함께 서비스에 위임한다")
    void revokeBusinessVerificationByAdmin_success() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}/verification", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"사칭 신고 접수 — 실제 사업자와 무관함이 확인됨\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("admin-company-verification-revoke",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.adminVerificationRevokeRequestFields()),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).revokeBusinessVerificationByAdmin(5L, "사칭 신고 접수 — 실제 사업자와 무관함이 확인됨");
    }

    @Test
    @DisplayName("회수 사유가 비어 있으면 400 을 반환한다 — 분쟁 대응 근거 없이 회수할 수 없다")
    void revokeBusinessVerificationByAdmin_requiresReason() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}/verification", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("관리자가 아닌 유저가 인증 회수를 호출하면 @PreAuthorize 가 막아 403 을 반환한다")
    void revokeBusinessVerificationByAdmin_forbidden_forNonAdmin() throws Exception {
        authenticate(2L, UserRole.MEMBER);

        mockMvc.perform(patch("/api/v1/admin/companies/{id}/verification", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"사유\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("관리자가 회사를 삭제하면 200 ok 를 반환하고 관리자 삭제 서비스에 위임한다")
    void deleteCompanyByAdmin_success() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/admin/companies/{id}", 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("admin-company-delete",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).deleteByAdmin(eq(5L));
    }

    @Test
    @DisplayName("관리자가 삭제된 회사를 복구하면 200 ok 를 반환하고 복구 서비스에 위임한다")
    void restoreCompanyByAdmin_success() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/admin/companies/{id}/restore", 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andDo(document("admin-company-restore",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).restoreByAdmin(eq(5L));
    }

    @Test
    @DisplayName("복구 대상 번호가 이미 활성으로 재사용 중이면 409(BUSINESS_NUMBER_TAKEN) 를 반환한다")
    void restoreCompanyByAdmin_businessNumberTaken_conflict() throws Exception {
        authenticate(1L, UserRole.ADMIN);
        willThrow(new BusinessException(CompanyErrorCode.BUSINESS_NUMBER_TAKEN))
                .given(companyUpdateService).restoreByAdmin(eq(5L));

        mockMvc.perform(post("/api/v1/admin/companies/{id}/restore", 5L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("이미 사용 중인 사업자번호입니다."))
                .andDo(document("admin-company-restore-conflict",
                        responseFields(CompanyApiDocs.errorResponseFields())));
    }

    @Test
    @DisplayName("관리자가 아닌 유저가 복구를 호출하면 @PreAuthorize 가 막아 403 을 반환한다")
    void restoreCompanyByAdmin_forbidden_forNonAdmin() throws Exception {
        authenticate(2L, UserRole.MEMBER);

        mockMvc.perform(post("/api/v1/admin/companies/{id}/restore", 5L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("관리자가 태그를 전체 교체하면 200 ok 를 반환한다")
    void replaceTagsByAdmin_success() throws Exception {
        authenticate(1L, UserRole.ADMIN);

        mockMvc.perform(put("/api/v1/admin/companies/{id}/tags", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"정밀\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("admin-company-update-tags",
                        pathParameters(parameterWithName("id").description("회사 ID")),
                        requestFields(CompanyApiDocs.replaceListRequestFields("교체할 태그명 목록 (빈 배열이면 전부 비움)")),
                        responseFields(CompanyApiDocs.okResponseFields())));

        verify(companyUpdateService).replaceTagsByAdmin(eq(5L), any());
    }

    @Test
    @DisplayName("관리자가 아닌 유저가 태그 교체를 호출하면 @PreAuthorize 가 막아 403 을 반환한다")
    void replaceTagsByAdmin_forbidden_forNonAdmin() throws Exception {
        authenticate(2L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/admin/companies/{id}/tags", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"정밀\"]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("접근 권한이 없습니다."));
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
