package project.plantly.companyTest.companyStatTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.stat.CompanyStatController;
import project.plantly.domain.company.stat.CompanyStatService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.security.UserPrincipal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회사 좋아요/즐겨찾기 컨트롤러 슬라이스 테스트 — 서비스 목킹으로 라우팅/HTTP 메서드 매핑(PUT·DELETE)/응답 상태(204)/
// 서비스 위임/예외 매핑(404)을 검증하고, 성공·예외 응답을 REST Docs 스니펫으로 문서화한다.
// 실제 멱등 동작(INSERT ... ON CONFLICT DO NOTHING, 재요청에도 예외 없음)은 서비스/실DB 통합 테스트가 담당한다.
@ActiveProfiles("test")
@WebMvcTest(controllers = CompanyStatController.class)
@ExtendWith(RestDocumentationExtension.class)
public class CompanyStatControllerTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private CompanyStatService companyStatService;

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
    @DisplayName("좋아요 등록(PUT)은 204 No Content 를 반환하고 서비스에 위임한다")
    void like_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/{id}/like", 9L))
                .andExpect(status().isNoContent())
                .andDo(document("company-like",
                        pathParameters(parameterWithName("id").description("회사 ID"))));

        verify(companyStatService).like(eq(7L), eq(9L));
    }

    @Test
    @DisplayName("좋아요 해제(DELETE)는 204 No Content 를 반환하고 서비스에 위임한다")
    void unlike_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(delete("/api/v1/companies/{id}/like", 9L))
                .andExpect(status().isNoContent())
                .andDo(document("company-unlike",
                        pathParameters(parameterWithName("id").description("회사 ID"))));

        verify(companyStatService).unlike(eq(7L), eq(9L));
    }

    @Test
    @DisplayName("즐겨찾기 등록(PUT)은 204 No Content 를 반환하고 서비스에 위임한다")
    void favorite_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(put("/api/v1/companies/{id}/favorite", 9L))
                .andExpect(status().isNoContent())
                .andDo(document("company-favorite",
                        pathParameters(parameterWithName("id").description("회사 ID"))));

        verify(companyStatService).favorite(eq(7L), eq(9L));
    }

    @Test
    @DisplayName("즐겨찾기 해제(DELETE)는 204 No Content 를 반환하고 서비스에 위임한다")
    void unfavorite_success() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        mockMvc.perform(delete("/api/v1/companies/{id}/favorite", 9L))
                .andExpect(status().isNoContent())
                .andDo(document("company-unfavorite",
                        pathParameters(parameterWithName("id").description("회사 ID"))));

        verify(companyStatService).unfavorite(eq(7L), eq(9L));
    }

    @Test
    @DisplayName("좋아요 등록(PUT)을 이미 좋아요한 상태에서 다시 보내도 409 가 아니라 204 로 멱등하게 응답한다")
    void like_isIdempotent_returnsNoContentOnRepeat() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        // 등록은 동사가 고정(PUT=원하는 상태로 수렴)이라 재요청도 동일하게 204. 서버 측 무해 처리(ON CONFLICT)는 서비스가 담당.
        mockMvc.perform(put("/api/v1/companies/{id}/like", 9L)).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/companies/{id}/like", 9L)).andExpect(status().isNoContent());

        verify(companyStatService, times(2)).like(eq(7L), eq(9L));
    }

    @Test
    @DisplayName("삭제·비공개·미존재 회사에 좋아요를 등록하면 404(COMPANY_NOT_FOUND) 를 반환한다")
    void like_nonexistentCompany_notFound() throws Exception {
        authenticate(7L, UserRole.MEMBER);
        willThrow(new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND))
                .given(companyStatService).like(eq(7L), eq(404L));

        mockMvc.perform(put("/api/v1/companies/{id}/like", 404L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("존재하지 않는 회사입니다."))
                .andDo(document("company-like-not-found",
                        responseFields(CompanyApiDocs.errorResponseFields())));
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
