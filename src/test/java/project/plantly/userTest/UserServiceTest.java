package project.plantly.userTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import project.plantly.domain.user.User;
import project.plantly.domain.user.dto.response.AdminUserListResponse;
import project.plantly.domain.user.repository.QUserRepository;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.domain.user.UserService;
import project.plantly.domain.user.dto.request.SignUpRequest;
import project.plantly.domain.user.dto.request.UpdateProfileRequest;
import project.plantly.domain.user.dto.response.ProfileResponse;
import project.plantly.domain.user.dto.response.UserDetailResponse;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.exception.UserErrorCode;
import project.plantly.global.PageResponse;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private QUserRepository qUserRepository;

    @InjectMocks
    private UserService userService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private SignUpRequest signUpRequest (){
        return new SignUpRequest(
                "test@example.com",
                "rawPassword",
                "rawPassword",
                "홍길동",
                "01012345678"
        );
    }

    @Test
    @DisplayName("회원가입 성공")
    public void 회원가입 (){
        //given
        SignUpRequest request = signUpRequest();
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encodedPassword");

        //when
        userService.createUser(request);

        //then
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("test@example.com");
        assertThat(saved.getPassword()).isEqualTo("encodedPassword");
    }

    @Test
    @DisplayName("이메일 중복 Duplicate_email 예외 반환")
    public void 이메일중복 (){
        //given
        SignUpRequest request = signUpRequest();
        given(userRepository.existsByEmail(request.email())).willReturn(true);

        //when & then
        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(UserErrorCode.DUPLICATE_EMAIL);

        verify(userRepository, never()).save(any(User.class));  // 저장 없음
        verify(passwordEncoder, never()).encode(anyString());  // 비밀번호 인코딩 없음
    }

    @Test
    @DisplayName("비밀번호가 재입력과 일치하지 않으면 예외 반환")
    public void signup_invalidPassword (){

        SignUpRequest request = new SignUpRequest("email@example.com", "rawPassword!", "wrongPassword", "홍길동", "01012345678");

        given(userRepository.existsByEmail(request.email())).willReturn(false);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(UserErrorCode.INVALID_PASSWORD);

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());

    }

    @Test
    @DisplayName("사용자가 프로필 조회 성공시 ProfileResponse 반환")
    public void getMyProfile_success (){
        //given
        Long userId = 1L;
        given(userRepository.findById(userId)).willReturn(Optional.of(userFixture(userId)));

        //when
        ProfileResponse result = userService.getMyProfile(userId);

        //then
        assertThat(result.email()).isEqualTo("email@example.com");
        assertThat(result.name()).isEqualTo("홍길동");
        assertThat(result.phone()).isEqualTo("01012345678");
        assertThat(result.nickname()).isNull();
        assertThat(result.userStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("사용자가 프로필 조회 실패시 USER_NOT_FOUNT 예외")
    public void getMyProfile_userNotFound(){
        //given
        Long userId = 999L;
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        //when/then
        assertThatThrownBy(() -> userService.getMyProfile(userId))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("사용자가 프로필 수정 성공시 ProfileResponse 반환")
    public void updateProfile_success (){
        //given
        Long userId = 1L;
        given(userRepository.findById(userId)).willReturn(Optional.of(userFixture(userId)));

        //when
        ProfileResponse profile = userService.updateUserProfile(userId, new UpdateProfileRequest(null, "닉네임", null));

        //then
        assertThat(profile.email()).isEqualTo("email@example.com");
        assertThat(profile.name()).isEqualTo("홍길동");
        assertThat(profile.phone()).isEqualTo("01012345678");
        assertThat(profile.nickname()).isEqualTo("닉네임");
        assertThat(profile.userStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(profile.createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));

    }

    @Test
    @DisplayName("관리자의 회원 상세 조회 성공 200 ok와 Response 반환")
    public void getUserDetailForAdmin_success (){
        //given
        Long userId = 1L;
        given(userRepository.findById(userId)).willReturn(Optional.of(userFixture(userId)));

        //when
        UserDetailResponse result = userService.getUserDetailForAdmin(userId);

        //then
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.email()).isEqualTo("email@example.com");
        assertThat(result.name()).isEqualTo("홍길동");
        assertThat(result.phone()).isEqualTo("01012345678");
        assertThat(result.userStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(result.updatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(result.userRole()).isEqualTo(UserRole.MEMBER);
    }

    @Test
    @DisplayName("관리자 회원 상세 조회시 유저 없으면 USER_NOT_FOUND 예외")
    public void getUserDetailForAdmin_userNotFound() {
        //given
        Long userId = 999L;
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        //when/then
        assertThatThrownBy(() -> userService.getUserDetailForAdmin(userId))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자의 회원 목록 조회 성공시 PageResponse로 변환해서 반환")
    public void getUserListForAdmin_success (){
        //given - 전체 5건 중 페이지당 2건을 조회하는 첫 페이지 상황
        // 주의: PageImpl 은 offset + pageSize > total 이면 total 을 보정하므로(예: size=30,total=5 → 2),
        //       size 를 실제 데이터 양(2)에 맞춰야 total(5)이 그대로 유지된다.
        PageRequest pageable = PageRequest.of(0, 2);

        AdminUserListResponse user1 = new AdminUserListResponse(
                "a@example.com", "회원A", "01011111111",
                LocalDateTime.of(2026, 1, 2, 0, 0),
                UserRole.MEMBER, UserStatus.ACTIVE
        );

        AdminUserListResponse user2 = new AdminUserListResponse(
                "b@example.com", "회원B", "01022222222",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                UserRole.MEMBER, UserStatus.ACTIVE
        );

        PageImpl<AdminUserListResponse> page = new PageImpl<>(List.of(user1, user2), pageable, 5);
        given(qUserRepository.getAdminUsers(pageable)).willReturn(page);

        //when
        PageResponse<AdminUserListResponse> result = userService.getUserListForAdmin(pageable);

        //then
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).email()).isEqualTo("a@example.com");
        assertThat(result.getPageInfo().pageNumber()).isEqualTo(1);     // 1-based
        assertThat(result.getPageInfo().size()).isEqualTo(2);
        assertThat(result.getPageInfo().totalElement()).isEqualTo(5);
        assertThat(result.getPageInfo().totalPage()).isEqualTo(3);      // ceil(5 / 2)
        verify(qUserRepository).getAdminUsers(pageable);
    }

    @Test
    @DisplayName("조회 결과가 없으면 빈 목록과 총 0건 반환")
    public void getUserListForAdmin_empty(){
        //given
        PageRequest pageable = PageRequest.of(0, 30);
        given(qUserRepository.getAdminUsers(pageable)).willReturn(
                new PageImpl<>(List.of(), pageable, 0));

        //when
        PageResponse<AdminUserListResponse> result = userService.getUserListForAdmin(pageable);

        //then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getPageInfo().totalElement()).isZero();
    }




    private User userFixture (Long id){
        User user = BeanUtils.instantiateClass(User.class);

        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "email", "email@example.com");
        ReflectionTestUtils.setField(user, "name", "홍길동");
        ReflectionTestUtils.setField(user, "phone", "01012345678");
        ReflectionTestUtils.setField(user, "userStatus", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        ReflectionTestUtils.setField(user, "userRole", UserRole.MEMBER);

        return user;

    }

}
