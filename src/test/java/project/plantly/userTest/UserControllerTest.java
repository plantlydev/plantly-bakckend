package project.plantly.userTest;


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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import project.plantly.domain.user.User;
import project.plantly.domain.user.UserController;
import project.plantly.domain.user.UserService;
import project.plantly.domain.user.dto.request.SignUpRequest;
import project.plantly.domain.user.dto.request.UpdateProfileRequest;
import project.plantly.domain.user.dto.response.AdminUserListResponse;
import project.plantly.domain.user.dto.response.ProfileResponse;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.exception.UserErrorCode;
import project.plantly.global.PageInfo;
import project.plantly.global.PageResponse;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import project.plantly.domain.user.dto.response.UserDetailResponse;


import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// REST Docs 용 요청 빌더: URL 템플릿을 기록해 path/query 파라미터 문서화를 지원한다 (MockMvcRequestBuilders 대체)
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.payload.JsonFieldType.ARRAY;
import static org.springframework.restdocs.payload.JsonFieldType.BOOLEAN;
import static org.springframework.restdocs.payload.JsonFieldType.NUMBER;
import static org.springframework.restdocs.payload.JsonFieldType.STRING;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;


@ActiveProfiles("test")
@WebMvcTest(controllers = UserController.class)
@ExtendWith(RestDocumentationExtension.class)   // REST Docs: 스니펫 생성 컨텍스트 제공
@Import(UserControllerTest.MethodSecurityTestConfig.class)
public class UserControllerTest {

    // @PreAuthorize 를 슬라이스 테스트에서 강제하기 위한 메서드 시큐리티 활성화
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig { }


    // REST Docs 적용을 위해 WebApplicationContext 로 MockMvc 를 직접 구성한다.
    // webAppContextSetup 은 시큐리티 필터를 붙이지 않으므로 기존 addFilters=false 와 동일하게 동작하고,
    // @PreAuthorize(메서드 시큐리티)는 그대로 적용된다.
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpMockMvc(RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())   // 요청 본문을 보기 좋게 정렬
                        .withResponseDefaults(prettyPrint())) // 응답 본문을 보기 좋게 정렬
                .build();
    }

    @MockitoBean
    private UserService userService;

    @Test
    @DisplayName("정상 요청이면 201과 성공 메세지 반환")
    public void signUp_success () throws Exception {
        SignUpRequest request = new SignUpRequest("test@example.com", "rawPassword!",  "rawPassword!", "홍길동", "01012345678");

        mockMvc.perform(post("/api/v1/users/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andDo(document("users-sign-up",
                        requestFields(
                                fieldWithPath("email").description("이메일 (필수, 이메일 형식)"),
                                fieldWithPath("password").description("비밀번호 (필수, 10~60자, 특수문자 1개 이상)"),
                                fieldWithPath("reWritePassword").description("비밀번호 재입력 (필수, password 와 동일해야 함)"),
                                fieldWithPath("name").description("이름 (필수, 2~30자, 한글 또는 영문)"),
                                fieldWithPath("phone").description("휴대폰 번호 (필수, 10~11자리 숫자)")
                        ),
                        responseFields(
                                fieldWithPath("success").description("요청 성공 여부 (true)"),
                                fieldWithPath("message").description("성공 메시지")
                        )
                ));
    }

    @Test
    @DisplayName("중복 이메일이면 409와 에러 메세지 반환")
    public void signup_duplicateEmail () throws Exception {
        SignUpRequest request = new SignUpRequest("test@example.com", "rawPassword!", "rawPassword!", "홍길동", "01012345678");

        willThrow(new BusinessException(UserErrorCode.DUPLICATE_EMAIL))
                .given(userService).createUser(any(SignUpRequest.class));

        mockMvc.perform(post("/api/v1/users/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("이미 사용 중인 이메일입니다."))
                .andDo(document("users-sign-up-duplicate-email",
                        responseFields(
                                fieldWithPath("success").description("요청 성공 여부 (false)"),
                                fieldWithPath("code")
                                        .description("에러 코드(ErrorCode 상수명). 클라이언트는 문구가 아니라 이 값으로 분기한다"),
                                fieldWithPath("error").description("에러 메시지 (이미 사용 중인 이메일)")
                        )
                ));

    }

    @Test
    @DisplayName("이메일 형식이 잘못되면 400과 검증 에러 반환")
    public void signup_validationFail () throws Exception {
        SignUpRequest request = new SignUpRequest("testexample.com", "rawPassword!", "rawPassword!", "홍길동", "01012345678");

        mockMvc.perform(post("/api/v1/users/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists())
                .andDo(document("users-sign-up-validation-error",
                        responseFields(
                                fieldWithPath("success").description("요청 성공 여부 (false)"),
                                fieldWithPath("code")
                                        .description("에러 코드(ErrorCode 상수명). 클라이언트는 문구가 아니라 이 값으로 분기한다"),
                                fieldWithPath("error").description("검증 실패 메시지 (첫 번째 위반 항목)"),
                                fieldWithPath("errors").type(ARRAY).optional()
                                        .description("입력 검증 실패일 때만 존재. 위반 전체가 화면의 폼 순서로 담긴다"),
                                fieldWithPath("errors[].field").type(STRING).optional()
                                        .description("위반한 입력칸의 경로. 폼 전체 오류면 생략된다"),
                                fieldWithPath("errors[].message").type(STRING).optional()
                                        .description("그 입력칸 아래에 표시할 문구")
                        )
                ));

    }

    @Test
    @DisplayName("인증된 유저 프로프리 200 반환")
    public void getProfile_success () throws Exception {
        Long userId = 1L;

        ProfileResponse profile = new ProfileResponse(
                "email@example.com",
                "홍길동",
                null,
                "01012345678",
                UserStatus.ACTIVE,
                UserRole.MEMBER,
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );

        given(userService.getMyProfile(userId)).willReturn(profile);
        authenticate(userId);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("email@example.com"))   // ApiResponse 래퍼 키 확인
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.userStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.userRole").value("MEMBER"))
                .andDo(document("users-me",
                        responseFields(
                                fieldWithPath("success").type(BOOLEAN).description("요청 성공 여부 (true)"),
                                fieldWithPath("data.email").type(STRING).description("이메일"),
                                fieldWithPath("data.name").type(STRING).description("이름"),
                                fieldWithPath("data.nickname").type(STRING).optional().description("닉네임 (미설정 시 null)"),
                                fieldWithPath("data.phone").type(STRING).description("휴대폰 번호"),
                                fieldWithPath("data.userStatus").type(STRING).description("회원 상태 (ACTIVE, SUSPENDED 등)"),
                                fieldWithPath("data.userRole").type(STRING).description("권한 (MEMBER, ADMIN). 관리자 메뉴 노출 판단용"),
                                fieldWithPath("data.createdAt").type(STRING).description("가입 일시 (ISO-8601)")
                        )
                ));
    }

    @Test
    @DisplayName("유저 없음 404 반환")
    public void getProfile_userNotFount () throws Exception {
        Long userId = 1L;
        given(userService.getMyProfile(userId))
                .willThrow(new BusinessException(UserErrorCode.USER_NOT_FOUND));
        authenticate(userId);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isNotFound())
                .andDo(document("users-me-not-found",
                        responseFields(
                                fieldWithPath("success").type(BOOLEAN).description("요청 성공 여부 (false)"),
                                fieldWithPath("code").type(STRING)
                                        .description("에러 코드(ErrorCode 상수명). 클라이언트는 문구가 아니라 이 값으로 분기한다"),
                                fieldWithPath("error").type(STRING).description("에러 메시지 (회원을 찾을 수 없음)")
                        )
                ));

    }

    @Test
    @DisplayName("회원 프로필 수정시 200과 성공 메세지 반환")
    public void profileUpdate_success () throws Exception {
        Long userId = 1L;

        ProfileResponse profile = new ProfileResponse(
                "email@example.com",
                "홍길동",
                null,
                "01012345678",
                UserStatus.ACTIVE,
                UserRole.MEMBER,
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );

        authenticate(userId);
        // 수정된 프로필을 응답 data 로 반환하도록 stub (문서에 응답 본문을 담기 위함)
        given(userService.updateUserProfile(anyLong(), any(UpdateProfileRequest.class))).willReturn(profile);
        UpdateProfileRequest request = new UpdateProfileRequest(null, "닉네임", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("프로필 수정이 완료되었습니다."))
                .andDo(document("users-me-update",
                        requestFields(
                                fieldWithPath("name").type(STRING).optional().description("이름 (선택, 2~30자, 한글/영문)"),
                                fieldWithPath("nickname").type(STRING).optional().description("닉네임 (선택, 2~15자)"),
                                fieldWithPath("phone").type(STRING).optional().description("휴대폰 번호 (선택, 10~11자리 숫자)")
                        ),
                        responseFields(
                                fieldWithPath("success").type(BOOLEAN).description("요청 성공 여부 (true)"),
                                fieldWithPath("message").type(STRING).description("성공 메시지"),
                                fieldWithPath("data.email").type(STRING).description("이메일"),
                                fieldWithPath("data.name").type(STRING).description("이름"),
                                fieldWithPath("data.nickname").type(STRING).optional().description("닉네임 (미설정 시 null)"),
                                fieldWithPath("data.phone").type(STRING).description("휴대폰 번호"),
                                fieldWithPath("data.userStatus").type(STRING).description("회원 상태"),
                                fieldWithPath("data.userRole").type(STRING).description("권한 (MEMBER, ADMIN)"),
                                fieldWithPath("data.createdAt").type(STRING).description("가입 일시")
                        )
                ));
    }

    @Test
    @DisplayName("관리자가 회원 상세 조회시 200과 데이터 반환")
    public void getUserDetailForAdmin_admin_success () throws Exception {
        Long targetUserId = 2L;

        UserDetailResponse response = UserDetailResponse.builder()
                .id(targetUserId)
                .email("target@example.com")
                .name("대상회원")
                .nickname("닉네임")
                .phone("01099998888")
                .userStatus(UserStatus.ACTIVE)
                .userRole(UserRole.MEMBER)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .deletedAt(null)
                .build();

        given(userService.getUserDetailForAdmin(targetUserId)).willReturn(response);
        authenticate(1L, UserRole.ADMIN);  // 관리자 권한으로 인증

        mockMvc.perform(get("/api/v1/admin/users/{userId}", targetUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.email").value("target@example.com"))
                .andExpect(jsonPath("$.data.userRole").value("MEMBER"))
                .andDo(document("admin-user-detail",
                        pathParameters(
                                parameterWithName("userId").description("조회할 회원 ID")
                        ),
                        responseFields(
                                fieldWithPath("success").type(BOOLEAN).description("요청 성공 여부 (true)"),
                                fieldWithPath("data.id").type(NUMBER).description("회원 ID"),
                                fieldWithPath("data.email").type(STRING).description("이메일"),
                                fieldWithPath("data.name").type(STRING).description("이름"),
                                fieldWithPath("data.nickname").type(STRING).optional().description("닉네임 (미설정 시 null)"),
                                fieldWithPath("data.phone").type(STRING).description("휴대폰 번호"),
                                fieldWithPath("data.userStatus").type(STRING).description("회원 상태 (ACTIVE, SUSPENDED 등)"),
                                fieldWithPath("data.userRole").type(STRING).description("권한 (MEMBER, ADMIN)"),
                                fieldWithPath("data.createdAt").type(STRING).description("가입 일시"),
                                fieldWithPath("data.updatedAt").type(STRING).optional().description("수정 일시 (없으면 null)"),
                                fieldWithPath("data.deletedAt").type(STRING).optional().description("탈퇴 일시 (없으면 null)")
                        )
                ));
    }

    @Test
    @DisplayName("권한이 없는 회원이 회원 상세 조회시 403 반환")
    public void getUserDetailForAdmin_member_forbidden() throws Exception {
        Long targetId = 2L;
        authenticate(1L, UserRole.MEMBER);  //일반 권한으로 인증

        mockMvc.perform(get("/api/v1/admin/users/{userId}", targetId))
                .andDo(print())
                .andExpect(status().isForbidden())
                .andDo(document("admin-user-detail-forbidden",
                        responseFields(
                                fieldWithPath("success").type(BOOLEAN).description("요청 성공 여부 (false)"),
                                fieldWithPath("code").type(STRING)
                                        .description("에러 코드(ErrorCode 상수명). 클라이언트는 문구가 아니라 이 값으로 분기한다"),
                                fieldWithPath("error").type(STRING).description("에러 메시지 (접근 권한 없음)")
                        )
                ));

        // 인가 단계에서 막혀 서비스는 호출되지 않아야 함
        verify(userService, never()).getUserDetailForAdmin(anyLong());
    }

    @Test
    @DisplayName("관리자가 회원 목록 조회 시 200과 페이지 데이터 반환")
    public void getUserListForAdmin_admin_success() throws Exception {
        //given
        AdminUserListResponse content = new AdminUserListResponse(
                "target@example.com", "대상회원", "01099998888",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                UserRole.MEMBER, UserStatus.ACTIVE
        );

        PageInfo pageInfo = new PageInfo(1, 30, 1, 1);

        PageResponse<AdminUserListResponse> response = new PageResponse<>(List.of(content), pageInfo);

        given(userService.getUserListForAdmin(any(Pageable.class))).willReturn(response);
        authenticate(1L, UserRole.ADMIN);

        //when & then
        mockMvc.perform(get("/api/v1/admin/users?page=0&size=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].email").value("target@example.com"))
                .andExpect(jsonPath("$.data.content[0].userRole").value("MEMBER"))
                .andExpect(jsonPath("$.data.pageInfo.pageNumber").value(1))
                .andExpect(jsonPath("$.data.pageInfo.totalElement").value(1))
                .andDo(document("admin-user-list",
                        queryParameters(
                                parameterWithName("page").description("페이지 번호 (0-base 입력)"),
                                parameterWithName("size").description("페이지 크기 (기본 30)")
                        ),
                        responseFields(
                                fieldWithPath("success").type(BOOLEAN).description("요청 성공 여부 (true)"),
                                fieldWithPath("data.content[].email").type(STRING).description("이메일"),
                                fieldWithPath("data.content[].name").type(STRING).description("이름"),
                                fieldWithPath("data.content[].phone").type(STRING).description("휴대폰 번호"),
                                fieldWithPath("data.content[].createdAt").type(STRING).description("가입 일시"),
                                fieldWithPath("data.content[].userRole").type(STRING).description("권한 (MEMBER, ADMIN)"),
                                fieldWithPath("data.content[].userStatus").type(STRING).description("회원 상태"),
                                fieldWithPath("data.pageInfo.pageNumber").type(NUMBER).description("현재 페이지 (1-base)"),
                                fieldWithPath("data.pageInfo.size").type(NUMBER).description("페이지 크기"),
                                fieldWithPath("data.pageInfo.totalElement").type(NUMBER).description("전체 건수"),
                                fieldWithPath("data.pageInfo.totalPage").type(NUMBER).description("전체 페이지 수")
                        )
                ));
    }

    @Test
    @DisplayName("권한 없는 회원이 회원 목록 조회 시 403 반환")
    public void getUserListForAdmin_member_forbidden () throws Exception {
        //given
        authenticate(1L, UserRole.MEMBER);

        mockMvc.perform(get("/api/v1/admin/users"))
                .andDo(print())
                .andExpect(status().isForbidden())
                .andDo(document("admin-user-list-forbidden",
                        responseFields(
                                fieldWithPath("success").type(BOOLEAN).description("요청 성공 여부 (false)"),
                                fieldWithPath("code").type(STRING)
                                        .description("에러 코드(ErrorCode 상수명). 클라이언트는 문구가 아니라 이 값으로 분기한다"),
                                fieldWithPath("error").type(STRING).description("에러 메시지 (접근 권한 없음)")
                        )
                ));

        // 인가 단계에서 Block -> 서비스 호출 되지 않음 확인
        verify(userService, never()).getUserListForAdmin(any(Pageable.class));

    }


    // 테스트 격리: 매 테스트 후 SecurityContext 비우기
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long userId) {
        authenticate(userId, UserRole.MEMBER);
    }


    // addFilters = false 라 보안 필터가 SecurityContext 를 채워주지 않으므로 직접 주입
    // (@AuthenticationPrincipal 리졸버는 SecurityContextHolder 에서 직접 읽음)
    private void authenticate(Long userId, UserRole role) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authOf(userId, role));
        SecurityContextHolder.setContext(context);
    }

    // 주어진 userId 를 가진 UserPrincipal 로 인증 객체 생성
    private Authentication authOf(Long userId, UserRole role) {
        User user = BeanUtils.instantiateClass(User.class);
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "userStatus", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "userRole", role);

        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
