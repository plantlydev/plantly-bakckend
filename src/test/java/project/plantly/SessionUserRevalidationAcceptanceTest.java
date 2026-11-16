package project.plantly;

import io.restassured.filter.cookie.CookieFilter;
import org.apache.catalina.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

// 세션 재검증을 실제 서버 + 실제 필터 체인으로 확인한다. 상태·역할 변경은 앱 API 가 없어 SQL 로 바꾼다
// (운영에서 이 문제가 드러나는 경로도 SQL 정지·승격이다).
class SessionUserRevalidationAcceptanceTest extends AcceptanceTest {

    private static final String PROTECTED_PATH = "/api/v1/users/me";
    // 익명에게 열린 API. 세션이 끊겨도 비로그인 응답으로 계속 쓸 수 있어야 한다.
    private static final String PUBLIC_PATH = "/api/v1/countries";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServletWebServerApplicationContext webServerContext;

    @Test
    @DisplayName("로그인 뒤 정지되면 같은 세션의 보호 API 는 401, 세션은 서버에서 사라진다")
    void suspendedUserSessionIsCut() throws Exception {
        CookieFilter cookies = new CookieFilter();
        String sessionId = signUpAndLogin(cookies, "suspend@example.com");
        given().filter(cookies).get(PROTECTED_PATH).then().statusCode(200);

        jdbcTemplate.update("update users set user_status = 'SUSPENDED' where email = ?", "suspend@example.com");

        given().filter(cookies).get(PROTECTED_PATH).then().statusCode(401);
        assertThat(serverHasSession(sessionId)).isFalse();
    }

    @Test
    @DisplayName("세션이 끊겨도 공개 API 는 비로그인 응답(200)으로 계속 쓸 수 있다")
    void publicApiStillWorksAfterCut() {
        CookieFilter cookies = new CookieFilter();
        signUpAndLogin(cookies, "public@example.com");

        jdbcTemplate.update("update users set user_status = 'WITHDRAWN' where email = ?", "public@example.com");

        given().filter(cookies).get(PUBLIC_PATH).then().statusCode(200);
        // 끊긴 뒤라 보호 API 는 여전히 401
        given().filter(cookies).get(PROTECTED_PATH).then().statusCode(401);
    }

    @Test
    @DisplayName("관리자에서 강등되면 기존 세션은 끊기고, 다시 로그인하면 새 역할로 이어진다")
    void demotedAdminMustLogInAgain() throws Exception {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, "admin@example.com").then().statusCode(201);
        jdbcTemplate.update("update users set user_role = 'ADMIN' where email = ?", "admin@example.com");
        String sessionId = login(cookies, csrf, "admin@example.com", VALID_PASSWORD, false)
                .then().statusCode(200).extract().cookie("JSESSIONID");

        jdbcTemplate.update("update users set user_role = 'MEMBER' where email = ?", "admin@example.com");

        given().filter(cookies).get(PROTECTED_PATH).then().statusCode(401);
        assertThat(serverHasSession(sessionId)).isFalse();

        // 다시 로그인하면 MEMBER 권한으로 실린 새 세션이 정상 동작한다.
        CookieFilter fresh = new CookieFilter();
        loginExisting(fresh, "admin@example.com");
        given().filter(fresh).get(PROTECTED_PATH).then().statusCode(200);
        given().filter(fresh).get(PROTECTED_PATH).then().statusCode(200);
    }

    @Test
    @DisplayName("다른 기기의 세션도 각자 다음 요청에서 끊긴다")
    void everyDeviceSessionIsCut() {
        CookieFilter deviceA = new CookieFilter();
        signUpAndLogin(deviceA, "devices@example.com");
        CookieFilter deviceB = new CookieFilter();
        loginExisting(deviceB, "devices@example.com");

        jdbcTemplate.update("update users set user_status = 'SUSPENDED' where email = ?", "devices@example.com");

        given().filter(deviceA).get(PROTECTED_PATH).then().statusCode(401);
        given().filter(deviceB).get(PROTECTED_PATH).then().statusCode(401);
    }

    // ---- helpers ----

    private String signUpAndLogin(CookieFilter cookies, String email) {
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, email).then().statusCode(201);
        return login(cookies, csrf, email, VALID_PASSWORD, false)
                .then().statusCode(200).extract().cookie("JSESSIONID");
    }

    private void loginExisting(CookieFilter cookies, String email) {
        String csrf = issueCsrfToken(cookies);
        login(cookies, csrf, email, VALID_PASSWORD, false).then().statusCode(200);
    }

    private boolean serverHasSession(String sessionId) throws Exception {
        Context tomcatContext = (Context) ((TomcatWebServer) webServerContext.getWebServer())
                .getTomcat().getHost().findChildren()[0];
        return tomcatContext.getManager().findSession(sessionId) != null;
    }
}
