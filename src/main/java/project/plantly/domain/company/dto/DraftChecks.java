package project.plantly.domain.company.dto;

/**
 * 임시저장(PUT /api/v1/companies/drafts/{번호})이 검사하는 제약만 모은 Bean Validation 그룹.
 *
 * <p>초안은 미완성 폼이라 필수값·원소·형식 검증을 하지 않는다(그건 발행 = 등록 POST 의 {@code @Valid} 몫이다).
 * 다만 컬렉션 개수 상한은 건다 — 검증 없는 초안 blob 에 태그 수천 개 같은 본문이 그대로 쌓이지 않게 하는 손쉬운 둑이다.
 * 그래서 개수 {@code @Size} 에만 {@code groups = {Default.class, DraftChecks.class}} 를 붙인다
 * (Default 를 함께 적어야 등록 경로의 기본 검증에서도 계속 발화한다).
 */
public interface DraftChecks {
}
