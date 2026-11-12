package project.plantly.domain.auth.social;

// 회원과 연결할 수 있는 외부 인증 제공자. DB 에는 이름(문자열)으로 저장한다.
// 값 이름은 OAuth2 client registrationId("naver", "google")의 대문자형이다 — 번역층이 이 대응으로 제공자를 고른다.
public enum SocialProvider {
    NAVER,
    GOOGLE
}
