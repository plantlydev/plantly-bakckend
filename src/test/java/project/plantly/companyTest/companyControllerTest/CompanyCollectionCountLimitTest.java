package project.plantly.companyTest.companyControllerTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import project.plantly.domain.company.controller.AdminCompanyController;
import project.plantly.domain.company.controller.CompanyController;
import project.plantly.domain.company.dto.CompanyConstraints;
import project.plantly.domain.company.service.CompanyDraftService;
import project.plantly.domain.company.service.CompanyQueryService;
import project.plantly.domain.company.service.CompanyRegistrationService;
import project.plantly.domain.company.service.CompanyService;
import project.plantly.domain.company.service.CompanyStatsService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.global.security.UserPrincipal;

import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컬렉션 개수 상한이 모든 쓰기 경로에서 서비스에 닿기 전에 걸리는지 고정한다.
 *
 * <p>등급 제도 폐지(748d5e2)로 등급별 한도 정책이 사라진 뒤, 개수 상한은 요청 DTO·PUT 파라미터의
 * {@code @Size(max = CompanyConstraints.*)} 한 겹뿐이다. 서비스 계층엔 한도 검사가 없으므로(연락처·레퍼런스 1건 가드 제외)
 * 애너테이션 하나가 빠지면 그 컬렉션은 무제한이 된다. 그래서 컬렉션 × 경로 전부를 표로 돌린다 —
 * 상한까지는 통과하고, 상한 + 1 은 400 이며 서비스 위임이 없다.
 *
 * <p>경로: 자가등록·관리자 등록(POST), 소유자·관리자 컬렉션 교체(PUT), 임시저장(PUT /drafts).
 * 임시저장은 미완성 폼을 받아야 해서 필수값·원소·형식 검증은 하지 않지만, 개수·글자수 상한은 발행과 같은 값으로 건다
 * (검증 그룹 {@code DraftChecks}).
 */
@ActiveProfiles("test")
@WebMvcTest(controllers = {CompanyController.class, AdminCompanyController.class})
@Import(CompanyCollectionCountLimitTest.MethodSecurityTestConfig.class)
@DisplayName("컬렉션 개수 상한: 모든 쓰기 경로에서 상한까지 통과, 상한+1 은 400")
class CompanyCollectionCountLimitTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired WebApplicationContext context;

    @MockitoBean CompanyRegistrationService companyRegistrationService;
    @MockitoBean CompanyService companyService;
    @MockitoBean CompanyQueryService companyQueryService;
    @MockitoBean CompanyUpdateService companyUpdateService;
    @MockitoBean CompanyVerificationService companyVerificationService;
    @MockitoBean CompanyDraftService companyDraftService;
    @MockitoBean CompanyStatsService companyStatsService;

    // 필수 항목만 채운 등록 본문(닫는 괄호 없음). 개수 외의 이유로 400 이 나면 무엇에 걸렸는지 구분되지 않는다.
    private static final String USER_CREATE_BODY = "{\"verificationId\":1,\"companyName\":\"회사\""
            + ",\"postalCode\":\"06236\",\"roadAddress\":\"서울시 강남구 테헤란로 1\",\"detailAddress\":\"10층\"";
    private static final String ADMIN_CREATE_BODY = "{\"companyName\":\"회사\",\"ceoName\":\"대표\""
            + ",\"postalCode\":\"06236\",\"roadAddress\":\"서울시 강남구 테헤란로 1\",\"detailAddress\":\"10층\"";

    private MockMvc mockMvc;

    /**
     * 컬렉션 하나. {@code listJson(n)} 은 그 컬렉션의 원소 n 개짜리 JSON 배열(= PUT 본문 = 등록 본문의 필드 값)이다.
     * 레퍼런스 이미지는 레퍼런스 1건 안의 중첩 리스트라, 레퍼런스 1건짜리 배열을 만들되 이미지 수를 n 으로 바꾼다.
     */
    record Limit(String name, String field, String putPath, int max, IntFunction<String> listJson) {
        @Override
        public String toString() {
            return name + "(" + max + ")";
        }
    }

    static Stream<Limit> limits() {
        return Stream.of(
                new Limit("태그", "tagNames", "tags", CompanyConstraints.TAGS_MAX, n -> array(n, i -> "\"t" + i + "\"")),
                new Limit("소재", "materialNames", "materials", CompanyConstraints.MATERIALS_MAX, n -> array(n, i -> "\"m" + i + "\"")),
                new Limit("장비", "equipmentNames", "equipment", CompanyConstraints.EQUIPMENTS_MAX, n -> array(n, i -> "\"e" + i + "\"")),
                new Limit("카테고리", "categoryIds", "categories", CompanyConstraints.CATEGORIES_MAX, n -> array(n, i -> "" + i)),
                new Limit("산업군", "industryIds", "industries", CompanyConstraints.INDUSTRIES_MAX, n -> array(n, i -> "" + i)),
                new Limit("인증", "certifications", "certifications", CompanyConstraints.CERTIFICATIONS_MAX,
                        n -> array(n, i -> "{\"certificationId\":" + i + "}")),
                new Limit("국가", "countryIds", "countries", CompanyConstraints.COUNTRIES_MAX, n -> array(n, i -> "" + i)),
                new Limit("국내지역", "domesticRegionIds", "regions", CompanyConstraints.DOMESTIC_REGIONS_MAX, n -> array(n, i -> "" + i)),
                new Limit("상세 이미지", "images", "images", CompanyConstraints.DETAIL_IMAGES_MAX,
                        n -> array(n, i -> "{\"imageUrl\":\"https://cdn/" + i + ".png\",\"imageType\":\"DETAIL\"}")),
                new Limit("연락처", "contacts", "contacts", CompanyConstraints.CONTACTS_MAX,
                        n -> array(n, i -> "{\"contactName\":\"c" + i + "\"}")),
                new Limit("레퍼런스", "references", "references", CompanyConstraints.REFERENCES_MAX,
                        n -> array(n, i -> "{\"projectTitle\":\"p" + i + "\"}")),
                new Limit("레퍼런스 이미지", "references", "references", CompanyConstraints.REFERENCE_IMAGES_MAX,
                        n -> "[{\"projectTitle\":\"p\",\"imageUrls\":" + array(n, i -> "\"https://cdn/r" + i + ".png\"") + "}]"));
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    @DisplayName("자가등록(POST /companies)")
    void userCreate(Limit limit) throws Exception {
        authenticate(7L, UserRole.MEMBER);
        String path = "/api/v1/companies";

        expect(post(path), USER_CREATE_BODY + field(limit, limit.max() + 1) + "}", status().isBadRequest());
        verifyNoInteractions(companyRegistrationService);
        expect(post(path), USER_CREATE_BODY + field(limit, limit.max()) + "}", status().isCreated());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    @DisplayName("관리자 등록(POST /admin/companies)")
    void adminCreate(Limit limit) throws Exception {
        authenticate(1L, UserRole.ADMIN);
        String path = "/api/v1/admin/companies";

        expect(post(path), ADMIN_CREATE_BODY + field(limit, limit.max() + 1) + "}", status().isBadRequest());
        verifyNoInteractions(companyService);
        expect(post(path), ADMIN_CREATE_BODY + field(limit, limit.max()) + "}", status().isCreated());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    @DisplayName("소유자 컬렉션 교체(PUT /companies/{id}/...)")
    void userReplace(Limit limit) throws Exception {
        authenticate(7L, UserRole.MEMBER);
        String path = "/api/v1/companies/9/" + limit.putPath();

        expect(put(path), limit.listJson().apply(limit.max() + 1), status().isBadRequest());
        verifyNoInteractions(companyUpdateService);
        expect(put(path), limit.listJson().apply(limit.max()), status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    @DisplayName("관리자 컬렉션 교체(PUT /admin/companies/{id}/...)")
    void adminReplace(Limit limit) throws Exception {
        authenticate(1L, UserRole.ADMIN);
        String path = "/api/v1/admin/companies/9/" + limit.putPath();

        expect(put(path), limit.listJson().apply(limit.max() + 1), status().isBadRequest());
        verifyNoInteractions(companyUpdateService);
        expect(put(path), limit.listJson().apply(limit.max()), status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    @DisplayName("임시저장(PUT /companies/drafts/{번호}) — 미완성 폼이어도 개수 상한은 건다")
    void draftSave(Limit limit) throws Exception {
        authenticate(7L, UserRole.MEMBER);
        String path = "/api/v1/companies/drafts/1234567890";

        // 필수값(회사명·주소)이 하나도 없는 초안 — 개수만 본다.
        expect(put(path), "{" + field(limit, limit.max() + 1).substring(1) + "}", status().isBadRequest());
        verifyNoInteractions(companyDraftService);
        expect(put(path), "{" + field(limit, limit.max()).substring(1) + "}", status().isOk());
    }

    /**
     * 초안의 글자수 한 칸. {@code body(n)} 은 그 필드에 n 자짜리 값을 담은 초안 본문 전체다.
     * 발행(등록)과 같은 상한이어야 한다 — 초안에서 통과한 값이 발행 때 길이로 튕기면 사용자는 저장된 줄 알고 떠난 글을 다시 고쳐야 한다.
     */
    record Length(String name, int max, IntFunction<String> body) {
        @Override
        public String toString() {
            return name + "(" + max + ")";
        }
    }

    static Stream<Length> draftLengths() {
        return Stream.of(
                top("companyName", CompanyConstraints.COMPANY_NAME_MAX),
                top("roadAddress", CompanyConstraints.ROAD_ADDRESS_MAX),
                top("jibunAddress", CompanyConstraints.JIBUN_ADDRESS_MAX),
                top("detailAddress", CompanyConstraints.DETAIL_ADDRESS_MAX),
                top("website", CompanyConstraints.URL_MAX),
                top("logoUrl", CompanyConstraints.URL_MAX),
                top("coverImageUrl", CompanyConstraints.URL_MAX),
                top("introTitle", CompanyConstraints.INTRO_TITLE_MAX),
                top("content", CompanyConstraints.CONTENT_MAX),
                top("videoUrl", CompanyConstraints.URL_MAX),
                top("leadTime", CompanyConstraints.LEAD_TIME_MAX),
                top("asInfo", CompanyConstraints.AS_INFO_MAX),
                new Length("tagNames[]", CompanyConstraints.TAG_NAME_MAX, n -> "{\"tagNames\":[\"" + text(n) + "\"]}"),
                new Length("materialNames[]", CompanyConstraints.MATERIAL_NAME_MAX, n -> "{\"materialNames\":[\"" + text(n) + "\"]}"),
                new Length("equipmentNames[]", CompanyConstraints.EQUIPMENT_NAME_MAX, n -> "{\"equipmentNames\":[\"" + text(n) + "\"]}"),
                nested("contacts[].contactName", CompanyConstraints.CONTACT_NAME_MAX, "contacts", "contactName", ""),
                nested("contacts[].position", CompanyConstraints.CONTACT_POSITION_MAX, "contacts", "position", ""),
                nested("contacts[].phone", CompanyConstraints.PHONE_MAX, "contacts", "phone", ""),
                nested("contacts[].email", CompanyConstraints.EMAIL_MAX, "contacts", "email", ""),
                nested("certifications[].customName", CompanyConstraints.CUSTOM_CERTIFICATION_NAME_MAX,
                        "certifications", "customName", ",\"certificationId\":1"),
                nested("images[].imageUrl", CompanyConstraints.URL_MAX, "images", "imageUrl", ",\"imageType\":\"DETAIL\""),
                nested("references[].projectTitle", CompanyConstraints.PROJECT_TITLE_MAX, "references", "projectTitle", ""),
                nested("references[].achievements", CompanyConstraints.ACHIEVEMENTS_MAX, "references", "achievements", ""),
                nested("references[].partners", CompanyConstraints.PARTNERS_MAX, "references", "partners", ""),
                nested("references[].period", CompanyConstraints.PERIOD_MAX, "references", "period", ""),
                new Length("references[].imageUrls[]", CompanyConstraints.URL_MAX,
                        n -> "{\"references\":[{\"imageUrls\":[\"" + text(n) + "\"]}]}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("draftLengths")
    @DisplayName("임시저장 글자수 — 발행과 같은 상한까지 통과, 넘으면 400")
    void draftLength(Length length) throws Exception {
        authenticate(7L, UserRole.MEMBER);
        String path = "/api/v1/companies/drafts/1234567890";

        expect(put(path), length.body().apply(length.max() + 1), status().isBadRequest());
        verifyNoInteractions(companyDraftService);
        expect(put(path), length.body().apply(length.max()), status().isOk());
    }

    private static Length top(String field, int max) {
        return new Length(field, max, n -> "{\"" + field + "\":\"" + text(n) + "\"}");
    }

    // 컬렉션 원소 1건 안의 문자열 필드. rest 는 같은 원소에 함께 실을 나머지 필드(",\"k\":v" 형태).
    private static Length nested(String name, int max, String collection, String field, String rest) {
        return new Length(name, max,
                n -> "{\"" + collection + "\":[{\"" + field + "\":\"" + text(n) + "\"" + rest + "}]}");
    }

    private static String text(int n) {
        return "1".repeat(n);   // 숫자만 — 전화번호 패턴 등 다른 규칙이 섞여도 글자수만 본다
    }

    @Test
    @DisplayName("임시저장은 개수·글자수 외의 검증은 하지 않는다 — 필수값 누락·빈 원소·형식 오류가 있어도 저장된다")
    void draftSkipsNonSizeValidation() throws Exception {
        authenticate(7L, UserRole.MEMBER);

        expect(put("/api/v1/companies/drafts/1234567890"),
                "{\"companyName\":\"\",\"tagNames\":[\"\",null],\"brandColor\":\"#12\","
                        + "\"contacts\":[{\"contactName\":\"\",\"email\":\"not-an-email\"}]}",
                status().isOk());
    }

    private void expect(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        String body, ResultMatcher status) throws Exception {
        // 기록은 요청 "전"에 지운다 — 뒤에서 지우면 이어지는 verifyNoInteractions 가 늘 통과해 버린다.
        clearInvocations(companyRegistrationService, companyService, companyUpdateService, companyDraftService);
        mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status);
    }

    // 등록 본문에 이어 붙일 ",\"field\":[...]" 조각.
    private static String field(Limit limit, int n) {
        return ",\"" + limit.field() + "\":" + limit.listJson().apply(n);
    }

    private static String array(int n, IntFunction<String> element) {
        return IntStream.rangeClosed(1, n).mapToObj(element).collect(Collectors.joining(",", "[", "]"));
    }

    private void authenticate(Long userId, UserRole role) {
        User user = BeanUtils.instantiateClass(User.class);
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "userStatus", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "userRole", role);
        UserPrincipal principal = new UserPrincipal(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
    }
}
