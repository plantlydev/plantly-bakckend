package project.plantly.domain.company.policy;

// 회사 등록(create) 시점에 실행되는 정책 한 가지. 서비스는 주입받은 정책들을 실행만 하고,
// 정책 내용(무엇을 어떻게 검증하는지)은 구현체가 전적으로 소유한다.
//
// 입력은 CompanyPolicyView(정책이 읽는 것만 추린 뷰)다. 뷰의 슬라이스가 null 이면 정책이 스스로 스킵한다.
// 제약 위반 시 BusinessException 을 던지고, 던지면 등록 트랜잭션 전체가 아무것도 영속화하지 않는다.
//
// 수정(update) 경로는 이 정책들을 다시 돌리지 않는다 — 같은 규칙을 CompanyChildWriter 가 직접 강제한다.
public interface CompanyRegistrationPolicy {

    void apply(CompanyPolicyView view);
}
