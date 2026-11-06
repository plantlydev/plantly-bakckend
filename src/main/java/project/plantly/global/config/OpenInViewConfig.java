package project.plantly.global.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * OSIV(open-in-view)를 유지하되, 외부 API(국세청)를 기다리는 엔드포인트만 제외한다.
 *
 * <p><b>왜 제외하나:</b> OSIV 가 켜지면 요청 하나가 EntityManager 하나를 끝까지 들고 있고, 한 번 잡힌 DB 커넥션도
 * 요청이 끝날 때까지 반납되지 않는다. 인증 흐름은 국세청 호출을 일부러 트랜잭션 밖에 두었지만(CompanyVerificationWriter
 * 주석), OSIV 아래에서는 앞선 DB 조회에서 잡힌 커넥션을 국세청 응답(최대 read timeout)을 기다리는 내내 붙잡는다.
 * 국세청이 느려지면 이 요청들이 풀을 말려 무관한 API 까지 멈춘다({@code ReverificationOsivAcceptanceTest} 가 재현한다).
 *
 * <p><b>왜 일괄로 끄지 않나:</b> 다른 API 들이 OSIV 를 전제로 지연 로딩을 하는지 전수 점검이 끝나지 않았다.
 * 제외 대상은 서비스가 트랜잭션 안에서 응답을 다 만들어 반환하는 경로라 OSIV 가 없어도 동작이 같다.
 *
 * <p><b>왜 경로가 아니라 (메서드, 경로) 쌍인가:</b> 표준 {@code excludePathPatterns} 는 HTTP 메서드를 구분하지 못한다.
 * 등록(POST /api/v1/companies)을 빼면 같은 URL 의 공개 목록·검색(GET)까지 OSIV 에서 빠진다.
 *
 * <p>Boot 의 OSIV 자동 설정은 {@code OpenEntityManagerInViewInterceptor} 빈이 이미 있으면 물러난다
 * ({@code @ConditionalOnMissingBean}). 여기서 등록하는 하위 클래스가 그 자리를 대신하므로
 * {@code spring.jpa.open-in-view} 는 건드리지 않는다(값을 false 로 적으면 "대부분 켜져 있다"는 사실이 가려진다).
 *
 * <p>구조는 Boot 의 OSIV 자동 설정(JpaWebConfiguration)과 같다 — 인터셉터 빈과, 그걸 등록하는 WebMvcConfigurer 빈을
 * 따로 둔다. 이 클래스가 직접 WebMvcConfigurer 를 구현하면 {@code @WebMvcTest} 슬라이스가 이 클래스를 끌어들이는데,
 * 슬라이스에는 EntityManagerFactory 가 없어 컨텍스트가 뜨지 않는다(일반 {@code @Configuration} 은 슬라이스에서 빠진다).
 *
 * <p><b>새 엔드포인트가 트랜잭션 밖에서 외부 API 를 기다린다면 {@link #EXCLUDED} 에 추가해야 한다.</b>
 */
@Configuration
public class OpenInViewConfig {

    /** OSIV 를 적용하지 않는 엔드포인트. 셋 다 국세청 진위확인 API 를 호출한다. */
    public static final List<Endpoint> EXCLUDED = List.of(
            new Endpoint(HttpMethod.POST, "/api/v1/companies/verification"),      // 선행 인증
            new Endpoint(HttpMethod.POST, "/api/v1/companies/{id}/verification"), // 재인증
            new Endpoint(HttpMethod.POST, "/api/v1/companies")                    // 등록(만료 인증 자동 재질의)
    );

    @Bean
    public SelectiveOpenEntityManagerInViewInterceptor selectiveOpenEntityManagerInViewInterceptor() {
        return new SelectiveOpenEntityManagerInViewInterceptor(EXCLUDED);
    }

    @Bean
    public WebMvcConfigurer selectiveOpenEntityManagerInViewInterceptorConfigurer(
            SelectiveOpenEntityManagerInViewInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addWebRequestInterceptor(interceptor);
            }
        };
    }

    /** HTTP 메서드 + 경로 패턴(Spring MVC 와 같은 {@link PathPattern} 문법). */
    public record Endpoint(HttpMethod method, PathPattern pattern) {

        public Endpoint(HttpMethod method, String pattern) {
            this(method, PathPatternParser.defaultInstance.parse(pattern));
        }

        boolean matches(HttpServletRequest request) {
            if (!method.matches(request.getMethod())) {
                return false;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return pattern.matches(PathContainer.parsePath(path));
        }
    }

    /**
     * 제외 대상 요청이면 아무것도 하지 않는(EntityManager 를 바인딩하지 않는) OSIV 인터셉터.
     *
     * <p>바인딩(preHandle)과 해제(afterCompletion, afterConcurrentHandlingStarted)가 같은 요청에 대해 같은 판정을
     * 내려야 짝이 맞는다. 판정은 요청의 메서드·경로만 보므로 한 요청 안에서 바뀌지 않는다.
     * 제외된 요청에서는 각 {@code @Transactional} 메서드가 자기 EntityManager 를 열고 닫으며, 커넥션도 트랜잭션이
     * 끝날 때 반납된다.
     */
    public static class SelectiveOpenEntityManagerInViewInterceptor extends OpenEntityManagerInViewInterceptor {

        private final List<Endpoint> excluded;

        public SelectiveOpenEntityManagerInViewInterceptor(List<Endpoint> excluded) {
            this.excluded = excluded;
        }

        @Override
        public void preHandle(WebRequest request) {
            if (!isExcluded(request)) {
                super.preHandle(request);
            }
        }

        @Override
        public void afterCompletion(WebRequest request, Exception ex) {
            if (!isExcluded(request)) {
                super.afterCompletion(request, ex);
            }
        }

        @Override
        public void afterConcurrentHandlingStarted(WebRequest request) {
            if (!isExcluded(request)) {
                super.afterConcurrentHandlingStarted(request);
            }
        }

        public boolean isExcluded(WebRequest request) {
            if (!(request instanceof ServletWebRequest servletRequest)) {
                return false;
            }
            HttpServletRequest httpRequest = servletRequest.getRequest();
            return excluded.stream().anyMatch(endpoint -> endpoint.matches(httpRequest));
        }
    }
}
