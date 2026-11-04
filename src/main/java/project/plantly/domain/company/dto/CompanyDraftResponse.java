package project.plantly.domain.company.dto;

import java.time.LocalDateTime;

/**
 * 임시저장 초안 조회 응답. 저장했던 폼 상태(payload)와 마지막 저장 시각을 함께 준다.
 * 프런트는 payload 로 폼 전체(기본 필드 + 컬렉션)를 복원하고, updatedAt 으로 "n분 전 저장됨" 등을 표시한다.
 *
 * <p>verification 은 <b>지금 이 번호로 발행에 쓸 인증</b>이다 — 인증 발급 응답과 같은 모양(검증된 신원 3종)이라,
 * 이어쓰기 화면이 인증 직후 화면과 같은 재료로 그려진다. 이게 없으면 인증 식별자를 잃은
 * 클라이언트(기기 변경·저장소 초기화)는 읽기 전용 칸을 채우려고 다시 인증해야 하고, 그때마다 국세청
 * 쿼터와 일일 시도 한도를 쓴다. 인증 정보는 이미 서버에 있으니 다시 물을 이유가 없다.
 *
 * <p>payload.verificationId 는 저장 당시 값이라 낡을 수 있다. 발행할 때는 이 verification.verificationId 를 쓴다.
 * 이어쓸 인증이 없으면(전부 회수됨) null 이고, 그때는 사업자 인증부터 다시 받아야 한다.
 */
public record CompanyDraftResponse(
        MyCompanyCreateRequest payload,
        LocalDateTime updatedAt,
        CompanyVerificationResponse verification
) {
}
