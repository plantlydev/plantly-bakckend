package project.plantly.global.seed;

/**
 * 시드가 만든 회사 1건의 "의도" 기록. 상태값(플래그·공개범위)은 여기 담지 않는다 —
 * 매니페스트를 쓸 때 DB 에서 실제 저장된 값을 다시 읽는다. 의도와 실제가 갈라지면 매니페스트가
 * 거짓말을 하게 되고, 그 거짓말을 믿은 프론트가 헛수고를 하기 때문이다.
 *
 * @param code      케이스 코드(C01…, P01…). 시드를 다시 돌려도 변하지 않는 안정 키
 * @param companyId 이번 시드에서 부여된 id
 * @param ownerCode 소유 계정 코드(U1 등). 소유자 미연동(관리자 등록)이면 null
 * @param proves    이 행이 무엇을 검증하기 위해 존재하는지
 */
public record SeedCompanyRef(String code, Long companyId, String ownerCode, String proves) {
}
