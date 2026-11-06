package project.plantly.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.dto.AdminCompanyFlagsRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.CertificationRequest;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

// 회사 수정 전담 서비스. 등록(CompanyService)·조회(CompanyQueryService)와 분리한 변경 경로.
// 본체 기본 정보는 부분 수정(PATCH), 컬렉션은 전체 교체(PUT)로 다룬다.
// 어떤 수정이든 mutateOwned 를 거쳐 (소유 검증 → 변경 → 검색 재동기화) 순서를 강제해 비정규화 색인을 최신화한다.
// 접근제어: 유저 경로는 소유(멤버)여야 하고, 관리자 경로는 컨트롤러 @PreAuthorize 가 담당한다(추후 추가).
@Service
@RequiredArgsConstructor
@Transactional
public class CompanyUpdateService {

    private final CompanyRepository companyRepository;
    private final CompanyMemberRepository companyMemberRepository;
    private final CompanyVerificationRepository verificationRepository;
    private final CompanyChildWriter childWriter;
    private final CompanyLinkWriter linkWriter;
    private final CompanySearchDocumentWriter searchDocumentWriter;

    // 컬렉션 개수 한도는 요청 DTO 의 @Size(CompanyConstraints 의 개수 상한) 가 모든 회사에 똑같이 건다.
    // 서비스 계층에서 다시 검사하는 정책은 없다(등급별 차등이 폐지되어 행위자·회사 무관하게 같은 한도다).

    // ===== 기본 정보 부분 수정 =====

    public void updateBasicInfoByUser(Long companyId, Long userId, CompanyUpdateRequest request) {
        mutateOwned(companyId, userId,
                company -> {
                    assertVerifiedIdentityUnchanged(company, request);
                    company.updateBasicInfo(
                            request.companyName(), request.ceoName(), request.establishmentDate(),
                            request.postalCode(), request.roadAddress(), request.jibunAddress(), request.detailAddress(),
                            request.website(), request.logoUrl(), request.coverImageUrl(), request.introTitle(), request.content(),
                            request.trlLevel(), request.videoUrl(), request.leadTime(), request.asInfo(),
                            request.pricingType(), request.brandColor());
                });
    }

    // 국세청 검증을 통과한 회사는 대표자명·개업일자를 바꿀 수 없다.
    // 이걸 열어두면 인증을 통과한 뒤 두 값을 아무렇게나 바꿔놓고 "국세청 확인" 배지를 유지할 수 있다.
    // (사업자번호는 애초에 updateBasicInfo 경로에 없다 — 등록 시점 인증본이 유일한 출처)
    private void assertVerifiedIdentityUnchanged(Company company, CompanyUpdateRequest request) {
        if (!company.isBusinessVerified()) {
            return;
        }
        boolean touchesCeoName = request.ceoName() != null && !request.ceoName().equals(company.getCeoName());
        boolean touchesStartDate = request.establishmentDate() != null
                && !request.establishmentDate().equals(company.getEstablishmentDate());
        if (touchesCeoName || touchesStartDate) {
            throw new BusinessException(CompanyErrorCode.VERIFIED_FIELD_NOT_EDITABLE);
        }
    }

    // ===== 공개/비공개 전환 =====
    // 소유자 경로는 멤버 검증 후, 관리자 경로는 소유 무관으로 목표 상태를 지정한다(멱등). 둘 다 공통 실행 골격을
    // 재사용한다 — visibility 는 검색 도큐먼트에 없어 재색인이 불필요하지만(공개 필터는 company.visibility 를 실시간 참조),
    // 경로를 하나로 유지하려고 mutateOwned/mutateAsAdmin 을 그대로 태운다(도큐먼트 재생성은 멱등).

    public void changeVisibilityByUser(Long companyId, Long userId, CompanyVisibility visibility) {
        mutateOwned(companyId, userId, company -> company.changeVisibility(visibility));
    }

    public void changeVisibilityByAdmin(Long companyId, CompanyVisibility visibility) {
        mutateAsAdmin(companyId, company -> company.changeVisibility(visibility));
    }

    // ===== 소프트 삭제 / 복구 =====
    // 삭제는 소유자(자가삭제)·관리자 모두 가능하고, 복구는 관리자 전용이다 — 삭제된 회사는 소유자 목록/공개 경로에서
    // 사라지므로(관리자에게만 보임) 되살리는 건 모더레이션 성격이라 관리자 경로로만 노출한다.
    // 삭제/복구 모두 명시적 방향(토글 아님)이라 멱등하다: 이미 삭제된 걸 또 삭제해도, 이미 활성인 걸 또 복구해도 같은 상태로 수렴.

    public void deleteByUser(Long companyId, Long userId) {
        mutateOwned(companyId, userId, Company::delete);
    }

    public void deleteByAdmin(Long companyId) {
        mutateAsAdmin(companyId, Company::delete);
    }

    // 복구는 활성 상태로 되돌리므로 사업자번호 활성 유니크(부분 인덱스)를 다시 지켜야 한다.
    // 삭제된 사이 같은 번호로 새 회사가 등록됐다면 복구 시 활성 2건이 되어 인덱스 위반 → 미리 친화적 에러로 막는다.
    // (복구 대상은 아직 deleted=true 라 exists...DeletedFalse 조회에 자기 자신은 안 걸린다.)
    public void restoreByAdmin(Long companyId) {
        Company company = loadCompany(companyId);
        if (company.getBusinessNumber() != null
                && companyRepository.existsByBusinessNumberAndDeletedFalse(company.getBusinessNumber())) {
            throw new BusinessException(CompanyErrorCode.BUSINESS_NUMBER_TAKEN);
        }
        company.restore();
        searchDocumentWriter.write(companyId);
    }

    // ===== 컬렉션 전체 교체 =====
    // 각 메서드는 해당 컬렉션만 통째로 새 리스트로 교체한다(빈 리스트 = 전부 비우기).

    public void replaceTagsByUser(Long companyId, Long userId, List<String> tagNames) {
        mutateOwned(companyId, userId, company -> childWriter.replaceTags(company, tagNames));
    }

    public void replaceMaterialsByUser(Long companyId, Long userId, List<String> materialNames) {
        mutateOwned(companyId, userId, company -> childWriter.replaceMaterials(company, materialNames));
    }

    public void replaceEquipmentByUser(Long companyId, Long userId, List<String> equipmentNames) {
        mutateOwned(companyId, userId, company -> childWriter.replaceEquipment(company, equipmentNames));
    }

    public void replaceGalleryImagesByUser(Long companyId, Long userId, List<CompanyCreateRequest.ImageRequest> images) {
        mutateOwned(companyId, userId, company -> childWriter.replaceGalleryImages(company, images));
    }

    public void replaceContactsByUser(Long companyId, Long userId, List<CompanyCreateRequest.ContactRequest> contacts) {
        mutateOwned(companyId, userId, company -> childWriter.replaceContacts(company, contacts));
    }

    public void replaceReferencesByUser(Long companyId, Long userId, List<CompanyCreateRequest.ReferenceRequest> references) {
        mutateOwned(companyId, userId, company -> childWriter.replaceReferences(company, references));
    }

    public void replaceCategoriesByUser(Long companyId, Long userId, List<Long> categoryIds) {
        mutateOwned(companyId, userId, company -> linkWriter.replaceCategories(company, categoryIds));
    }

    public void replaceIndustriesByUser(Long companyId, Long userId, List<Long> industryIds) {
        mutateOwned(companyId, userId, company -> linkWriter.replaceIndustries(company, industryIds));
    }

    public void replaceCertificationsByUser(Long companyId, Long userId, List<CertificationRequest> certifications) {
        mutateOwned(companyId, userId, company -> linkWriter.replaceCertifications(company, certifications));
    }

    public void replaceCountriesByUser(Long companyId, Long userId, List<Long> countryIds) {
        mutateOwned(companyId, userId, company -> linkWriter.replaceCountries(company, countryIds));
    }

    public void replaceRegionsByUser(Long companyId, Long userId, List<Long> domesticRegionIds) {
        mutateOwned(companyId, userId, company -> linkWriter.replaceRegions(company, domesticRegionIds));
    }

    // ===== 관리자 경로 =====
    // 소유(멤버) 검증 없이 대상 회사를 로드한다(삭제/미연동 포함). 권한(ADMIN)은 컨트롤러 @PreAuthorize 가 담당한다.
    // 변경 로직·구조 불변식은 유저 경로와 완전히 동일하게 재사용한다.

    // 관리자에게도 같은 가드를 적용한다. 관리자가 값을 고쳐야 한다면 인증을 먼저 회수하고 고치면 된다 —
    // 그래야 "businessVerified=true 인 회사의 대표자명·개업일자는 국세청 검증본"이라는 불변식이 예외 없이 유지된다.
    public void updateBasicInfoByAdmin(Long companyId, CompanyUpdateRequest request) {
        mutateAsAdmin(companyId,
                company -> {
                    assertVerifiedIdentityUnchanged(company, request);
                    company.updateBasicInfo(
                            request.companyName(), request.ceoName(), request.establishmentDate(),
                            request.postalCode(), request.roadAddress(), request.jibunAddress(), request.detailAddress(),
                            request.website(), request.logoUrl(), request.coverImageUrl(), request.introTitle(), request.content(),
                            request.trlLevel(), request.videoUrl(), request.leadTime(), request.asInfo(),
                            request.pricingType(), request.brandColor());
                });
    }

    // ===== 사업자 인증 회수 (관리자) =====
    // 사칭 신고 등으로 인증을 되돌린다. 인증 레코드는 REVOKED 로 남겨 회수 사실과 사유를 보존하고,
    // 회사의 비정규화 플래그도 함께 내린다. 사업자번호는 지우지 않는다 — 활성 유니크로 같은 번호의
    // 재등록을 계속 막아야 하고, 어떤 번호로 인증받았었는지가 분쟁 기록으로 남아야 한다.
    // 검색 도큐먼트에 businessVerified 가 없어 재색인은 부르지 않는다(플래그 조정 경로와 같은 이유).
    // 회사 행을 비관적 락으로 잡는다 — 재인증 반영(CompanyVerificationWriter#applyReverification)도 같은 락을
    // 잡고 최종 상태를 재확인하므로, 회수가 진행 중이거나 커밋된 뒤의 재인증은 회수를 되돌리지 못한다.
    public void revokeBusinessVerificationByAdmin(Long companyId, String reason) {
        Company company = companyRepository.findByIdForUpdate(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));
        company.revokeBusinessVerification();
        verificationRepository.findByCompanyId(companyId)
                .ifPresent(verification -> verification.revoke(reason, LocalDateTime.now()));
    }

    public void replaceTagsByAdmin(Long companyId, List<String> tagNames) {
        mutateAsAdmin(companyId, company -> childWriter.replaceTags(company, tagNames));
    }

    public void replaceMaterialsByAdmin(Long companyId, List<String> materialNames) {
        mutateAsAdmin(companyId, company -> childWriter.replaceMaterials(company, materialNames));
    }

    public void replaceEquipmentByAdmin(Long companyId, List<String> equipmentNames) {
        mutateAsAdmin(companyId, company -> childWriter.replaceEquipment(company, equipmentNames));
    }

    public void replaceGalleryImagesByAdmin(Long companyId, List<CompanyCreateRequest.ImageRequest> images) {
        mutateAsAdmin(companyId, company -> childWriter.replaceGalleryImages(company, images));
    }

    public void replaceContactsByAdmin(Long companyId, List<CompanyCreateRequest.ContactRequest> contacts) {
        mutateAsAdmin(companyId, company -> childWriter.replaceContacts(company, contacts));
    }

    public void replaceReferencesByAdmin(Long companyId, List<CompanyCreateRequest.ReferenceRequest> references) {
        mutateAsAdmin(companyId, company -> childWriter.replaceReferences(company, references));
    }

    public void replaceCategoriesByAdmin(Long companyId, List<Long> categoryIds) {
        mutateAsAdmin(companyId, company -> linkWriter.replaceCategories(company, categoryIds));
    }

    public void replaceIndustriesByAdmin(Long companyId, List<Long> industryIds) {
        mutateAsAdmin(companyId, company -> linkWriter.replaceIndustries(company, industryIds));
    }

    public void replaceCertificationsByAdmin(Long companyId, List<CertificationRequest> certifications) {
        mutateAsAdmin(companyId, company -> linkWriter.replaceCertifications(company, certifications));
    }

    public void replaceCountriesByAdmin(Long companyId, List<Long> countryIds) {
        mutateAsAdmin(companyId, company -> linkWriter.replaceCountries(company, countryIds));
    }

    public void replaceRegionsByAdmin(Long companyId, List<Long> domesticRegionIds) {
        mutateAsAdmin(companyId, company -> linkWriter.replaceRegions(company, domesticRegionIds));
    }

    // ===== 관리자 운영 플래그 조정 (인증/추천/스팟라이트) =====
    // sparse: null 필드는 미변경, 값이 오면 그 상태로 설정(멱등). 소유 무관(권한은 컨트롤러 @PreAuthorize).
    // 검색 도큐먼트엔 이 셋이 없고 정렬은 company 원본을 실시간 참조하므로 재동기화(write) 를 부르지 않는다 —
    // 컬렉션 교체 경로(mutateAsAdmin)와 달리 검색 색인을 건드릴 이유가 없다.
    public void changeFlagsByAdmin(Long companyId, AdminCompanyFlagsRequest request) {
        Company company = loadCompany(companyId);
        if (request.verified() != null) {
            company.changeVerified(request.verified());
        }
        if (request.featured() != null) {
            company.changeFeatured(request.featured());
        }
        if (request.spotlight() != null) {
            company.changeSpotlight(request.spotlight());
        }
    }

    // ===== 공통 실행 골격 =====

    // 소유(멤버) 검증 → 변경 적용 → 검색 도큐먼트 재동기화. 소유자 경로의 모든 수정(본체·컬렉션·공개 설정·삭제)이 쓴다.
    // (검색 색인 대상이 아닌 컬렉션까지 매번 재동기화하지만, 도큐먼트 재생성은 멱등이라 정합성 우선으로 일괄 호출한다)
    private void mutateOwned(Long companyId, Long userId, Consumer<Company> mutation) {
        Company company = loadOwnedCompany(companyId, userId);
        mutation.accept(company);
        searchDocumentWriter.write(companyId);
    }

    // 관리자 경로: 소유 검증 없이 로드 → 변경 → 재동기화. (getForAdmin 과 동일하게 상태 무관 로드)
    private void mutateAsAdmin(Long companyId, Consumer<Company> mutation) {
        Company company = loadCompany(companyId);
        mutation.accept(company);
        searchDocumentWriter.write(companyId);
    }

    // 소유(멤버) 검증까지 통과한 회사를 로드한다. (getOwnerView 와 동일 정책)
    private Company loadOwnedCompany(Long companyId, Long userId) {
        Company company = loadCompany(companyId);
        if (!companyMemberRepository.existsByCompanyIdAndUserId(companyId, userId)) {
            throw new BusinessException(CompanyErrorCode.COMPANY_ACCESS_DENIED);
        }
        return company;
    }

    private Company loadCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));
    }
}
