package project.plantly.userTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
public class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("이메일 중복시 exitsByEmail true 반환")
    public void existsByEmail_true (){
        //given
        userRepository.save(User.create("test@example.com", "encodedPw!", "홍길동", "01012345678"));

        //when
        boolean exists = userRepository.existsByEmail("test@example.com");

        //then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("이메일 중복이 아니면 existsByEmail false 반환")
    public void existsByEmail_false (){
        //when
        boolean exists = userRepository.existsByEmail("none@example.com");

        //then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("휴대폰 번호가 없는 회원(소셜 가입자)도 저장된다")
    public void savesUserWithoutPhone (){
        //given
        User saved = userRepository.saveAndFlush(User.create("social@example.com", "encodedPw!", "홍길동", null));

        //when
        User found = userRepository.findById(saved.getId()).orElseThrow();

        //then
        assertThat(found.getPhone()).isNull();
    }

    @Test
    @DisplayName("같은 이메일 저장 요청 시 unique 제약 예외 발생")
    public void uniqueEmailConstraint (){
        //given
        userRepository.saveAndFlush(User.create("dup@example.com", "pw1!", "홍길동", "01011111111"));

        //when & then - 제약 위반은 flush 시점에 터지므로 saveAndFlush로 강제
        assertThatThrownBy(() ->
                userRepository.saveAndFlush(User.create("dup@example.com", "pw1!", "홍길동", "01011111111")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
