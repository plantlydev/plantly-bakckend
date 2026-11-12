package project.plantly;

import io.restassured.filter.cookie.CookieFilter;
import jakarta.servlet.http.Cookie;
import org.apache.catalina.Context;
import org.apache.catalina.Session;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import project.plantly.domain.user.User;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.global.security.CustomUserDetailsService;
import project.plantly.global.security.UserPrincipal;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;


// 세션 기반 인증의 전체 흐름을 실제 서버(RANDOM_PORT) + 실제 필터 체인으로 검증하는 인수 테스트.
// CSRF가 켜져 있어 모든 변경(POST) 요청은 GET /api/v1/auth/csrf 로 받은 토큰을 헤더로 함께 보내야 한다.
class AuthAcceptanceTest extends AcceptanceTest {

    // 인증이 필요한 임의의 보호 자원(핸들러는 없음). 미인증이면 401, 인증되면 인가 게이트를 통과해 404가 된다.
    private static final String PROTECTED_PATH = "/api/v1/users/me";

    private static final String REMEMBER_ME_COOKIE = "remember-me";

    // remember-me 는 비활성화했지만 키 설정은 '재도입 시 사용'으로 남아 있다. 예전 쿠키를 재현하는 데 쓴다.
    @Value("${app.remember-me.key}")
    private String rememberMeKey;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private ServletWebServerApplicationContext webServerContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("회원가입 → 로그인(세션 발급) → 보호 자원 접근 통과 → 로그아웃 → 세션 만료 전체 흐름")
    void fullAuthFlow() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);

        // 1) 회원가입
        signUp(cookies, csrf, "flow@example.com")
                .then()
                .statusCode(201)
                .body("success", equalTo(true));

        // 2) 로그인: 200 + ApiResponse(data에 사용자 정보) + JSESSIONID 발급
        Response loginRes = login(cookies, csrf, "flow@example.com", VALID_PASSWORD, false);

        loginRes.then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.email", equalTo("flow@example.com"))
                .body("data.name", equalTo("홍길동"))
                .body("data.userStatus", equalTo("ACTIVE"));

        assertThat(loginRes.cookie("JSESSIONID")).isNotNull();

        // 3) 세션 쿠키로 본인 프로필 조회 → 200 + 인증 주체 본인 정보 반환(IDOR 방지: 외부 id 입력 불가)
        given().filter(cookies)
                .when()
                .get(PROTECTED_PATH)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.email", equalTo("flow@example.com"));

        // 4) 로그아웃: 200 성공 응답
        given().filter(cookies).header("X-XSRF-TOKEN", csrf)
                .when()
                .post("/api/v1/auth/logout")
                .then()
                .statusCode(200)
                .body("success", equalTo(true));

        // 5) 로그아웃 후 같은 보호 자원 → 다시 401
        given().filter(cookies)
                .when()
                .get(PROTECTED_PATH)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("미인증 상태로 보호 자원 접근 시 401과 UNAUTHORIZED 메시지(EntryPoint)")
    void accessProtectedWithoutAuth() {
        given()
                .when()
                .get(PROTECTED_PATH)
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("error", equalTo("로그인이 필요합니다."));
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인하면 401과 INVALID_CREDENTIALS 메시지")
    void loginWithWrongPassword() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "wrong@example.com").then().statusCode(201);

        login(cookies, csrf, "wrong@example.com", "Wrongpass1!", false)
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("error", equalTo("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인하면 401과 INVALID_CREDENTIALS 메시지")
    void loginWithUnknownEmail() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);

        login(cookies, csrf, "ghost@example.com", VALID_PASSWORD, false)
                .then()
                .statusCode(401)
                .body("error", equalTo("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("정지된 계정으로 로그인하면 403과 ACCOUNT_SUSPENDED 메시지")
    void loginWithSuspendedAccount() {
        User suspended = User.builder()
                .email("suspended@example.com")
                .password(passwordEncoder.encode(VALID_PASSWORD))
                .name("정지유저")
                .phone("01099998888")
                .userStatus(UserStatus.SUSPENDED)
                .build();
        userRepository.save(suspended);

        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);

        login(cookies, csrf, "suspended@example.com", VALID_PASSWORD, false)
                .then()
                .statusCode(403)
                .body("error", equalTo("계정이 비활성화 되었습니다."));
    }

    // ---- remember-me 비활성화 정책 ----
    // 첫 배포는 세션 인증만 쓴다. remember-me 쿠키는 발급하지 않고, 남아 있는 쿠키로 인증을 복구하지도 않는다.
    // "쿠키가 없어서 401" 이 아니라 "서명이 유효한 쿠키가 있어도 읽지 않아서 401" 임을 보여야 하므로,
    // 예전 설정과 같은 키·알고리즘으로 실제 서명한 쿠키를 만들어 보낸다(validRememberMeCookie).

    @Test
    @DisplayName("remember=true로 로그인해도 remember-me 쿠키는 발급되지 않고 세션 인증만 유지된다")
    void loginWithRememberDoesNotIssueRememberMeCookie() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "remember@example.com").then().statusCode(201);

        Response res = login(cookies, csrf, "remember@example.com", VALID_PASSWORD, true);

        res.then().statusCode(200);
        assertThat(res.cookie("JSESSIONID")).isNotNull();
        assertThat(res.cookie(REMEMBER_ME_COOKIE)).isNull();

        given().filter(cookies)
                .when()
                .get(PROTECTED_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("로그아웃은 예전에 발급된 remember-me 쿠키도 만료시키고, 그 쿠키로 인증이 복구되지 않는다")
    void logoutExpiresRememberMeCookieAndDoesNotRestore() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "logout@example.com").then().statusCode(201);
        login(cookies, csrf, "logout@example.com", VALID_PASSWORD, false).then().statusCode(200);
        String rememberMe = validRememberMeCookie("logout@example.com");

        Response logoutRes = given().filter(cookies)
                .header("X-XSRF-TOKEN", csrf)
                .cookie(REMEMBER_ME_COOKIE, rememberMe)
                .when()
                .post("/api/v1/auth/logout");

        logoutRes.then().statusCode(200);
        assertThat(logoutRes.detailedCookie(REMEMBER_ME_COOKIE)).isNotNull();
        assertThat(logoutRes.detailedCookie(REMEMBER_ME_COOKIE).getMaxAge()).isZero();

        // 브라우저가 쿠키를 지우지 못했더라도(예: 응답 유실) 그 쿠키로는 로그인 상태가 되지 않는다.
        given().filter(cookies)
                .cookie(REMEMBER_ME_COOKIE, rememberMe)
                .when()
                .get(PROTECTED_PATH)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("기존 remember-me 쿠키 보유자: 서명이 유효한 쿠키만 들고 오면 인증되지 않는다(401, 500 아님)")
    void existingRememberMeCookieIsIgnored() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "holder@example.com").then().statusCode(201);

        given().cookie(REMEMBER_ME_COOKIE, validRememberMeCookie("holder@example.com"))
                .when()
                .get(PROTECTED_PATH)
                .then()
                .statusCode(401)
                .body("error", equalTo("로그인이 필요합니다."));
    }

    @Test
    @DisplayName("세션이 끊긴 뒤에는 remember-me 쿠키가 있어도 인증이 복구되지 않는다")
    void expiredSessionIsNotRestoredByRememberMe() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "expired@example.com").then().statusCode(201);
        login(cookies, csrf, "expired@example.com", VALID_PASSWORD, false).then().statusCode(200);

        // 세션 만료를 흉내 낸다: 서버가 모르는 JSESSIONID(=만료돼 사라진 세션) + 유효한 remember-me 쿠키.
        given().cookie("JSESSIONID", "EXPIRED-SESSION-ID")
                .cookie(REMEMBER_ME_COOKIE, validRememberMeCookie("expired@example.com"))
                .when()
                .get(PROTECTED_PATH)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("로그인으로 만들어진 세션의 유휴 만료는 1일이다")
    void sessionIdleTimeoutIsOneDay() throws Exception {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "timeout@example.com").then().statusCode(201);
        String sessionId = login(cookies, csrf, "timeout@example.com", VALID_PASSWORD, false)
                .then().statusCode(200)
                .extract().cookie("JSESSIONID");

        Context tomcatContext = (Context) ((TomcatWebServer) webServerContext.getWebServer())
                .getTomcat().getHost().findChildren()[0];
        Session session = tomcatContext.getManager().findSession(sessionId);

        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo((int) Duration.ofDays(1).toSeconds());
    }

    // 예전 설정(TokenBasedRememberMeServices, 같은 키, SHA256)으로 서명한 remember-me 쿠키 값.
    // 먼저 그 서비스로 직접 autoLogin 이 되는지 확인해, 이 쿠키가 "예전 같으면 통과했을" 유효한 쿠키임을 보장한다.
    private String validRememberMeCookie(String email) {
        TokenBasedRememberMeServices legacy = new TokenBasedRememberMeServices(
                rememberMeKey, userDetailsService, TokenBasedRememberMeServices.RememberMeTokenAlgorithm.SHA256);
        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(email);
        MockHttpServletResponse issued = new MockHttpServletResponse();
        legacy.onLoginSuccess(new MockHttpServletRequest(), issued,
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        Cookie cookie = issued.getCookie(REMEMBER_ME_COOKIE);
        assertThat(cookie).as("remember-me 쿠키 생성").isNotNull();

        MockHttpServletRequest replay = new MockHttpServletRequest();
        replay.setCookies(cookie);
        assertThat(legacy.autoLogin(replay, new MockHttpServletResponse()))
                .as("예전 설정이라면 이 쿠키로 자동 로그인됐어야 한다")
                .isNotNull();

        return cookie.getValue();
    }

    @Test
    @DisplayName("CSRF 토큰 없이 변경(POST) 요청하면 403(AccessDeniedHandler)")
    void postWithoutCsrfIsRejected() {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "nocsrf@example.com").then().statusCode(201);

        // CSRF 쿠키/헤더 없이 로그인 시도
        String body = """
                {"email":"nocsrf@example.com","password":"%s","remember":false}
                """.formatted(VALID_PASSWORD);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(403)
                .body("error", equalTo("접근 권한이 없습니다."));
    }
}