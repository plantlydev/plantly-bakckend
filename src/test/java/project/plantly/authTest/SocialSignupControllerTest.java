package project.plantly.authTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import project.plantly.domain.auth.social.SocialProfile;
import project.plantly.domain.auth.social.SocialProvider;
import project.plantly.domain.auth.social.SocialSignupController;
import project.plantly.domain.auth.social.SocialSignupService;
import project.plantly.domain.auth.social.SocialSignupService.ConsentTerm;
import project.plantly.domain.auth.social.exception.SocialSignupErrorCode;
import project.plantly.domain.user.consent.ConsentType;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.security.oauth2.PendingSocialSignupStore;
import project.plantly.global.security.oauth2.PendingSocialSignupStore.PendingSocialSignup;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.JsonFieldType.ARRAY;
import static org.springframework.restdocs.payload.JsonFieldType.STRING;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.BDDMockito.willThrow;

// 소셜 가입 API 문서. 실제 보안 필터·세션 흐름은 NaverSocialSignupFlowTest 가 검증한다.
@ActiveProfiles("test")
@WebMvcTest(controllers = SocialSignupController.class)
@ExtendWith(RestDocumentationExtension.class)
@Import(PendingSocialSignupStore.class)
class SocialSignupControllerTest {

    private static final SocialProfile PROFILE =
            new SocialProfile(SocialProvider.NAVER, "naver-1", "member@naver.com", "홍길동");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private SocialSignupService socialSignupService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .build();
        given(socialSignupService.consentTerms()).willReturn(List.of(
                new ConsentTerm(ConsentType.AGE_OVER_14, true, "v1"),
                new ConsentTerm(ConsentType.TERMS_OF_SERVICE, true, "v1"),
                new ConsentTerm(ConsentType.PRIVACY_COLLECTION, true, "v1"),
                new ConsentTerm(ConsentType.MARKETING, false, "v1")));
    }

    @Test
    @DisplayName("가입 대기 조회 200")
    void pending() throws Exception {
        mockMvc.perform(get("/api/v1/auth/social/pending").session(pendingSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("member@naver.com"))
                .andDo(document("auth-social-pending",
                        responseFields(
                                fieldWithPath("success").description("요청 성공 여부 (true)"),
                                fieldWithPath("data.provider").description("소셜 제공자 (NAVER, GOOGLE)"),
                                fieldWithPath("data.email").description("제공자가 준 이메일. 가입하면 회원 이메일이 된다"),
                                fieldWithPath("data.name").type(STRING).optional()
                                        .description("제공자가 준 이름. null 이면 가입 화면에서 이름을 입력받아 가입 요청의 name 으로 보낸다"),
                                fieldWithPath("data.consents").type(ARRAY).description("동의 항목. 배열 순서가 화면 표시 순서다"),
                                fieldWithPath("data.consents[].type")
                                        .description("항목 (AGE_OVER_14: 만 14세 이상, TERMS_OF_SERVICE: 이용약관, "
                                                + "PRIVACY_COLLECTION: 개인정보 수집·이용, MARKETING: 광고성 정보 수신)"),
                                fieldWithPath("data.consents[].required").description("필수 여부. 필수 항목에 모두 동의해야 가입된다"),
                                fieldWithPath("data.consents[].version").description("현재 약관 버전")
                        )));
    }

    @Test
    @DisplayName("가입 대기 정보가 없거나 만료되면 401")
    void pending_expired() throws Exception {
        MockHttpSession expired = new MockHttpSession();
        expired.setAttribute(PendingSocialSignupStore.SESSION_ATTRIBUTE,
                new PendingSocialSignup(PROFILE, Instant.now().minusSeconds(1)));

        mockMvc.perform(get("/api/v1/auth/social/pending").session(expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNUP_SESSION_EXPIRED"))
                .andDo(document("auth-social-pending-expired", responseFields(errorFields())));
    }

    @Test
    @DisplayName("가입 201")
    void signup() throws Exception {
        mockMvc.perform(post("/api/v1/auth/social/signup").session(pendingSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agreements", List.of("AGE_OVER_14", "TERMS_OF_SERVICE", "PRIVACY_COLLECTION", "MARKETING")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loginUrl").value("/oauth2/authorization/naver"))
                .andDo(document("auth-social-signup",
                        requestFields(
                                fieldWithPath("agreements").type(ARRAY)
                                        .description("동의한 항목의 type 목록. 필수 항목은 모두 있어야 하고, 빠진 선택 항목은 미동의로 기록된다"),
                                fieldWithPath("name").type(STRING).optional()
                                        .description("이름 (2~30자, 한글 또는 영문). 가입 대기 조회의 name 이 null 일 때만 쓰인다")
                        ),
                        responseFields(
                                fieldWithPath("success").description("요청 성공 여부 (true)"),
                                fieldWithPath("message").description("성공 메시지"),
                                fieldWithPath("data.loginUrl")
                                        .description("이동할 재인가 주소. 가입 API 는 로그인시키지 않는다 — 브라우저를 이 주소로 "
                                                + "이동시키면 제공자를 바로 통과해 로그인된 채 프론트 / 로 돌아온다")
                        )));
    }

    @Test
    @DisplayName("필수 동의가 빠지면 400")
    void signup_requiredConsentMissing() throws Exception {
        willThrow(new BusinessException(SocialSignupErrorCode.REQUIRED_CONSENT_MISSING))
                .given(socialSignupService).signup(any(), isNull(), any());

        mockMvc.perform(post("/api/v1/auth/social/signup").session(pendingSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("agreements", List.of("TERMS_OF_SERVICE")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUIRED_CONSENT_MISSING"))
                .andDo(document("auth-social-signup-required-consent-missing", responseFields(errorFields())));
    }

    @Test
    @DisplayName("대기 중 같은 이메일 회원이 생겼으면 409")
    void signup_emailInUse() throws Exception {
        willThrow(new BusinessException(SocialSignupErrorCode.EMAIL_IN_USE))
                .given(socialSignupService).signup(any(), any(), any());

        mockMvc.perform(post("/api/v1/auth/social/signup").session(pendingSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agreements", List.of("AGE_OVER_14", "TERMS_OF_SERVICE", "PRIVACY_COLLECTION")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_USE"))
                .andDo(document("auth-social-signup-email-in-use", responseFields(errorFields())));
    }

    private static MockHttpSession pendingSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingSocialSignupStore.SESSION_ATTRIBUTE,
                new PendingSocialSignup(PROFILE, Instant.now().plus(PendingSocialSignupStore.TTL)));
        return session;
    }

    private static FieldDescriptor[] errorFields() {
        return new FieldDescriptor[]{
                fieldWithPath("success").description("요청 성공 여부 (false)"),
                fieldWithPath("code").description("에러 코드(ErrorCode 상수명). 클라이언트는 문구가 아니라 이 값으로 분기한다"),
                fieldWithPath("error").description("에러 메시지")
        };
    }
}
