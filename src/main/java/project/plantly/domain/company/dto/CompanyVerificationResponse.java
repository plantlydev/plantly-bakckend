package project.plantly.domain.company.dto;

import project.plantly.domain.company.entity.CompanyVerification;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 인증 발급 결과. verificationId 를 회사 등록 요청에 그대로 실어 보내면 서버가 검증된 값을 채운다.
 *
 * <p>검증된 세 값을 함께 돌려주는 이유는, 등록 폼에서 읽기 전용으로 보여주기 위해서다. 사용자가 다시
 * 입력하게 하면 인증본과 다른 값이 저장될 여지가 생긴다(등록 요청은 이 세 값을 받지 않는다).
 */
public record CompanyVerificationResponse(
        Long verificationId,
        String businessNumber,
        String ceoName,
        LocalDate businessStartDate,
        LocalDateTime expiresAt
) {

    public static CompanyVerificationResponse from(CompanyVerification verification) {
        return new CompanyVerificationResponse(
                verification.getId(),
                verification.getBusinessNumber(),
                verification.getCeoName(),
                verification.getBusinessStartDate(),
                verification.getExpiresAt());
    }
}
