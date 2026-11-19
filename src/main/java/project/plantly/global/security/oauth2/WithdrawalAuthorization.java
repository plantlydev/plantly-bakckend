package project.plantly.global.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

// 탈퇴 전용 인가 요청. 탈퇴 의도를 세션이 아니라 '그 인가 요청(OAuth2 state)'에 묶는다.
//
// 왜: 의도가 세션에만 있으면 같은 세션의 아무 소셜 로그인 콜백이나 탈퇴를 완료할 수 있다. 탈퇴 화면에서 제공자로 갔다가
// 탭을 닫으면(콜백이 안 와서) 의도가 10분 남는데, 그 사이 다른 탭이나 외부 링크로 /oauth2/authorization/naver 에
// 이동만 해도(GET 이라 CSRF 보호 밖, 제공자 세션이 있으면 화면도 없이 통과) 탈퇴가 끝난다.
//
// 흐름:
//  1) 시작 API 가 의도와 1회용 난수를 세션에 두고 /oauth2/authorization/{id}?withdrawal={난수} 를 준다.
//  2) 인가 시작(Resolver): withdrawal 파라미터가 있으면 이 인가 요청의 attributes 에 난수를 적는다.
//     인가 요청은 state 로 세션에 저장되므로 이 표시는 이 요청의 콜백에만 따라온다.
//  3) 콜백(Repository): 필터가 state 로 인가 요청을 꺼낼 때 그 요청을 request attribute 에 남긴다 — 핸들러는
//     인증 결과만 받으므로 "이 콜백이 어떤 인가 요청에서 왔는지"를 따로 넘겨야 한다.
//     같은 자리에서 제공자에게 '로그인 상태여도 다시 확인하라'는 파라미터를 붙인다(reauthenticationParameters) —
//     재인가만으로는 제공자 세션이 살아 있으면 화면 없이 통과하므로 본인 확인이 되지 않는다.
//  4) 성공·실패 핸들러: 이 콜백의 인가 요청에 난수가 있을 때만 탈퇴로 처리하고, 세션 의도와 난수가 맞아야 의도를 꺼낸다.
// 난수를 모르는 외부 링크는 탈퇴 표시가 붙은 요청을 만들어도 의도와 맞지 않아 탈퇴로 이어지지 않는다.
public final class WithdrawalAuthorization {

    public static final String PARAM = "withdrawal";
    static final String ATTRIBUTE = WithdrawalAuthorization.class.getName() + ".NONCE";
    private static final String CALLBACK_REQUEST_ATTRIBUTE = WithdrawalAuthorization.class.getName() + ".CALLBACK";

    private WithdrawalAuthorization() {
    }

    // 콜백 요청에서: 이 콜백이 탈퇴 전용 인가 요청에서 왔으면 그 난수. 일반 로그인 콜백이면 empty.
    public static Optional<String> callbackNonce(HttpServletRequest request) {
        if (request.getAttribute(CALLBACK_REQUEST_ATTRIBUTE) instanceof OAuth2AuthorizationRequest authorizationRequest
                && authorizationRequest.getAttribute(ATTRIBUTE) instanceof String nonce) {
            return Optional.of(nonce);
        }
        return Optional.empty();
    }

    // 인가 시작: 기본 resolver 가 만든 요청에 탈퇴 표시(난수)를 더한다. 표시가 없으면 기본 요청 그대로다.
    public static class Resolver implements OAuth2AuthorizationRequestResolver {

        private final DefaultOAuth2AuthorizationRequestResolver delegate;

        public Resolver(ClientRegistrationRepository clientRegistrationRepository) {
            this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository,
                    OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return markWithdrawal(request, delegate.resolve(request));
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
            return markWithdrawal(request, delegate.resolve(request, clientRegistrationId));
        }

        private static OAuth2AuthorizationRequest markWithdrawal(HttpServletRequest request,
                                                                 OAuth2AuthorizationRequest authorizationRequest) {
            String nonce = request.getParameter(PARAM);
            if (authorizationRequest == null || !StringUtils.hasText(nonce)) {
                return authorizationRequest;
            }
            String registrationId = authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
            return OAuth2AuthorizationRequest.from(authorizationRequest)
                    .attributes(attributes -> attributes.put(ATTRIBUTE, nonce))
                    .additionalParameters(parameters -> parameters.putAll(reauthenticationParameters(registrationId)))
                    .build();
        }

        // 탈퇴 요청에만 붙는 제공자별 재인증 파라미터. 일반 로그인 요청에는 붙지 않는다.
        //  - 네이버: auth_type=reauthenticate — 로그인 상태여도 비밀번호를 다시 입력받는다.
        //  - 구글: prompt=select_account — 계정 선택 화면을 반드시 띄워 화면 없는 통과만 막는다(최소 조치).
        //    구글 OAuth 에는 비밀번호 재입력을 강제할 수단이 없다(max_age 는 OIDC 이고 무시될 수 있음). 이 공백은
        //    인정하고, 서비스 자체 본인 확인(이메일 OTP 등)을 후속으로 둔다.
        private static Map<String, Object> reauthenticationParameters(String registrationId) {
            return switch (registrationId == null ? "" : registrationId) {
                case "naver" -> Map.of("auth_type", "reauthenticate");
                case "google" -> Map.of("prompt", "select_account");
                default -> Map.of();
            };
        }
    }

    // 인가 요청 저장소(세션). 콜백에서 꺼낸 인가 요청을 핸들러가 볼 수 있게 request attribute 에 남긴다.
    // 필터는 성공·실패(제공자 화면 취소 포함) 판정 전에 인가 요청부터 꺼내므로 두 핸들러 모두 볼 수 있다.
    public static class Repository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

        private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
                new HttpSessionOAuth2AuthorizationRequestRepository();

        @Override
        public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
            return delegate.loadAuthorizationRequest(request);
        }

        @Override
        public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                             HttpServletRequest request, HttpServletResponse response) {
            delegate.saveAuthorizationRequest(authorizationRequest, request, response);
        }

        @Override
        public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                     HttpServletResponse response) {
            OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
            if (removed != null) {
                request.setAttribute(CALLBACK_REQUEST_ATTRIBUTE, removed);
            }
            return removed;
        }
    }
}
