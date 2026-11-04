package project.plantly.domain.company.policy.rule;

import org.springframework.stereotype.Component;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.policy.CompanyPolicyView;
import project.plantly.domain.company.policy.CompanyRegistrationPolicy;
import project.plantly.global.exception.BusinessException;

// 정책(구조 검증): 회사 직속 갤러리(images)에는 상세 이미지(ImageType.DETAIL)만 허용한다.
// PROJECT 이미지는 references 경로로만 들어와야 하므로, images 에 DETAIL 외(또는 null) 타입이 섞이면 거부한다.
// 구조 검증이라 관리자 등록·자가등록 모두에 똑같이 적용한다.
// 수정 경로에서는 CompanyChildWriter.replaceGalleryImages 가 동일 규칙을 강제하므로 CompanyMutationPolicy 를 달지 않는다(등록 전용).
@Component
public class GalleryImageTypePolicy implements CompanyRegistrationPolicy {

    @Override
    public void apply(CompanyPolicyView view) {
        if (view.galleryImages() == null) {
            return;
        }

        boolean hasNonDetail = view.galleryImages().stream()
                .anyMatch(image -> image.imageType() != ImageType.DETAIL);
        if (hasNonDetail) {
            throw new BusinessException(CompanyErrorCode.GALLERY_IMAGE_TYPE_NOT_ALLOWED);
        }
    }
}
