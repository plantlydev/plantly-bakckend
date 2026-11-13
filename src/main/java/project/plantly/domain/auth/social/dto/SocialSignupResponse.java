package project.plantly.domain.auth.social.dto;

// 가입 완료 뒤 프론트가 이동할 재인가 주소(/oauth2/authorization/{제공자}). 제공자에 이미 동의한 상태라
// 제공자 화면을 바로 통과해 콜백으로 돌아오고, 이번에는 연결된 회원이라 로그인된다.
public record SocialSignupResponse(String loginUrl) {
}
