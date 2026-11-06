package project.plantly.globalTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import project.plantly.global.config.OpenInViewConfig;
import project.plantly.global.config.OpenInViewConfig.SelectiveOpenEntityManagerInViewInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

// OSIV 제외 판정 단위 테스트. 국세청을 기다리는 세 엔드포인트만 빠지고, 같은 URL 의 다른 메서드
// (특히 공개 목록·검색 GET /api/v1/companies)와 인접 경로는 OSIV 를 그대로 받아야 한다.
// 실제 요청에서 커넥션을 붙잡지 않는지는 ReverificationOsivAcceptanceTest 가 본다.
@DisplayName("OpenInViewConfig: OSIV 제외 대상 판정")
class OpenInViewConfigTest {

    private final SelectiveOpenEntityManagerInViewInterceptor interceptor =
            new SelectiveOpenEntityManagerInViewInterceptor(OpenInViewConfig.EXCLUDED);

    @ParameterizedTest(name = "{0} {1} → 제외={2}")
    @CsvSource({
            // 제외: 국세청 호출 경로
            "POST,   /api/v1/companies/verification,      true",
            "POST,   /api/v1/companies/42/verification,   true",
            "POST,   /api/v1/companies,                   true",
            // 유지: 같은 URL 의 다른 메서드
            "GET,    /api/v1/companies,                   false",
            "GET,    /api/v1/companies/42/verification,   false",
            // 유지: 인접 경로
            "POST,   /api/v1/companies/drafts,            false",
            "PATCH,  /api/v1/companies/42,                false",
            "POST,   /api/v1/admin/companies,             false",
            "POST,   /api/v1/companies/42/verification/x, false",
    })
    void excludesOnlyNtsEndpoints(String method, String path, boolean excluded) {
        assertThat(interceptor.isExcluded(request(method, path, ""))).isEqualTo(excluded);
    }

    @ParameterizedTest(name = "context-path={0}")
    @CsvSource({"''", "/app"})
    @DisplayName("컨텍스트 경로가 있어도 애플리케이션 내부 경로로 판정한다")
    void ignoresContextPath(String contextPath) {
        assertThat(interceptor.isExcluded(request("POST", contextPath + "/api/v1/companies", contextPath))).isTrue();
    }

    private static ServletWebRequest request(String method, String uri, String contextPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath(contextPath);
        return new ServletWebRequest(request);
    }
}
