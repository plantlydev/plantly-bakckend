package project.plantly.authTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import project.plantly.global.security.handler.LocalLoginDisabledFilter;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 자체 로그인을 끈 상태(운영 기본값)의 동작.
//
// 스프링 컨텍스트를 띄우지 않고 필터만 건다. @TestPropertySource 로 값만 바꾼 컨텍스트를 하나 더 만들면
// 같은 H2 메모리 DB(jdbc:h2:mem:plantly)를 쓰는 다른 컨텍스트의 create-drop 과 엇갈려 다른 테스트의 테이블이
// 사라진다(실제로 UploadSizeLimitAcceptanceTest 가 깨졌다). 테스트 프로파일은 기존 인수 테스트 때문에 true 다.
// SecurityConfig 쪽 배선은 "false 면 CsrfFilter 앞에 이 필터를 넣는다" 한 줄이다.
class LocalLoginDisabledTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new LocalLoginDisabledFilter(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("운영 기본값은 꺼짐 — 설정을 빠뜨리면 닫힌 쪽이 된다")
    void defaultIsDisabled() throws Exception {
        Properties base = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(base.getProperty("app.auth.local-login-enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("로그인 API 는 404 NOT_FOUND — 컨트롤러까지 가지 않는다")
    void login_notFound() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@plantly.co.kr\",\"password\":\"rawPassword!\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("회원가입 API 는 404 NOT_FOUND")
    void signUp_notFound() throws Exception {
        mockMvc.perform(post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 경로의 다른 메서드, 다른 인증 경로는 그대로 통과한다")
    void otherRequestsPassThrough() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login")).andExpect(content().string("passed"));
        mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(content().string("passed"));
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(content().string("passed"));
        mockMvc.perform(post("/api/v1/auth/social/signup")).andExpect(content().string("passed"));
    }

    // 필터를 통과한 요청이 닿는 자리. 막힌 두 경로도 매핑해 둬서, 404 가 "매핑이 없어서"가 아니라 필터 때문임을 보장한다.
    @RestController
    static class StubController {
        @PostMapping({"/api/v1/auth/login", "/api/v1/users/sign-up", "/api/v1/auth/logout", "/api/v1/auth/social/signup"})
        String post() {
            return "passed";
        }

        @GetMapping({"/api/v1/auth/login", "/api/v1/auth/csrf"})
        String get() {
            return "passed";
        }
    }
}
