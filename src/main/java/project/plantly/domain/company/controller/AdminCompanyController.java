package project.plantly.domain.company.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.plantly.domain.company.dto.CompanyConstraints;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.CertificationRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ContactRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ImageRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ReferenceRequest;
import project.plantly.domain.company.dto.AdminCompanyFlagsRequest;
import project.plantly.domain.company.dto.AdminVerificationRevokeRequest;
import project.plantly.domain.company.dto.CompanyDetailResponse;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.dto.CompanyVisibilityUpdateRequest;
import project.plantly.domain.company.search.dto.AdminCompanySearchRequest;
import project.plantly.domain.company.search.dto.AdminCompanySummary;
import project.plantly.domain.company.service.CompanyQueryService;
import project.plantly.domain.company.service.CompanyService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.global.PageResponse;
import project.plantly.global.response.ApiResponse;
import project.plantly.global.response.IdResponse;
import project.plantly.global.security.UserPrincipal;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AdminCompanyController {

    private final CompanyService companyService;
    private final CompanyQueryService companyQueryService;
    private final CompanyUpdateService companyUpdateService;

    // 관리자 등록 — 소유자 미연동(userId=null) 상태로 생성. registeredBy 에 등록한 관리자 id 기록.
    @PostMapping("/api/v1/admin/companies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<IdResponse> createCompanyByAdmin(@AuthenticationPrincipal UserPrincipal principal,
                                                        @Valid @RequestBody CompanyCreateRequest request) {

        Long id = companyService.createByAdmin(principal.getUser().getId(), request);
        return ApiResponse.success("회사 등록이 완료되었습니다.", new IdResponse(id));
    }

    // 관리자 회사 목록 — 기본 전체(삭제 포함). verified/featured/spotlight/deleted(3-상태)·회사명·소유자 id 로
    // 교집합 필터링, 운영 필드 포함 카드로 최신 등록순 페이징. ('my' 같은 세그먼트 충돌은 admin 경로엔 없다)
    @GetMapping("/api/v1/admin/companies")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<AdminCompanySummary>> listCompaniesByAdmin(@ModelAttribute AdminCompanySearchRequest request,
                                                                               @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(companyQueryService.listForAdmin(request.toCriteria(), pageable));
    }

    // 관리자 상세 조회 — 상태(삭제/미연동 등) 무관하게 전체를 본다. 소유자 뷰와 동일한 상세 응답을 재사용한다.
    @GetMapping("/api/v1/admin/companies/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CompanyDetailResponse> getCompanyByAdmin(@PathVariable Long id) {
        return ApiResponse.success(companyQueryService.getForAdmin(id));
    }

    // ===== 관리자 수정 — 소유 무관, 모든 회사 대상. 유저 수정과 동일한 변경/구조 불변식을 재사용한다. =====
    // (등급 한도·변형 정책은 유저 경로와 동일하게 아직 미적용 — CompanyUpdateService 의 TODO 참고)

    @PatchMapping("/api/v1/admin/companies/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> updateCompanyByAdmin(@PathVariable Long id,
                                                  @Valid @RequestBody CompanyUpdateRequest request) {
        companyUpdateService.updateBasicInfoByAdmin(id, request);
        return ApiResponse.ok();
    }

    // 공개/비공개 전환 — 관리자, 소유 무관. 목표 상태(PUBLIC/PRIVATE)를 지정한다(멱등).
    @PatchMapping("/api/v1/admin/companies/{id}/visibility")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> changeVisibilityByAdmin(@PathVariable Long id,
                                                     @Valid @RequestBody CompanyVisibilityUpdateRequest request) {
        companyUpdateService.changeVisibilityByAdmin(id, request.visibility());
        return ApiResponse.ok();
    }

    // 관리자 운영 플래그 조정 — 인증/추천/스팟라이트를 목표값으로 설정한다(sparse: null=미변경, 멱등). 소유 무관.
    // 응답은 본문 없이 성공만. (수정 값은 이미 화면에 반영되므로)
    @PatchMapping("/api/v1/admin/companies/{id}/flags")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> changeFlagsByAdmin(@PathVariable Long id,
                                                @RequestBody AdminCompanyFlagsRequest request) {
        companyUpdateService.changeFlagsByAdmin(id, request);
        return ApiResponse.ok();
    }

    // 사업자 인증 회수 — 사칭 신고 등으로 국세청 인증 상태를 되돌린다. 사유 필수(분쟁 대응 근거).
    // 관리자 운영 플래그(/flags 의 verified = 에디터 선정 배지)와는 다른 축이라 별도 경로로 둔다 —
    // 하나로 합치면 추천 배지를 켜다가 사업자 인증 자격까지 부여하는 사고가 난다.
    // 회수된 인증은 REVOKED 로 남아 같은 번호의 재인증을 막는 근거가 된다(미인증 상태로 되돌아가지 않는다).
    @PatchMapping("/api/v1/admin/companies/{id}/verification")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> revokeBusinessVerificationByAdmin(@PathVariable Long id,
                                                               @Valid @RequestBody AdminVerificationRevokeRequest request) {
        companyUpdateService.revokeBusinessVerificationByAdmin(id, request.reason());
        return ApiResponse.ok();
    }

    // 관리자 삭제(소프트) — 소유 무관, 모든 회사 대상. 멱등.
    @DeleteMapping("/api/v1/admin/companies/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteCompanyByAdmin(@PathVariable Long id) {
        companyUpdateService.deleteByAdmin(id);
        return ApiResponse.ok();
    }

    // 관리자 복구 — 삭제된 회사를 다시 활성화한다(관리자 전용). 삭제된 사이 같은 사업자번호가 활성으로 재등록됐다면
    // 409(BUSINESS_NUMBER_TAKEN). 멱등(이미 활성이어도 성공).
    @PostMapping("/api/v1/admin/companies/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> restoreCompanyByAdmin(@PathVariable Long id) {
        companyUpdateService.restoreByAdmin(id);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/tags")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceTagsByAdmin(@PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.TAGS_MAX, message = "태그는 10개를 넘을 수 없습니다.")
                    List<@NotBlank(message = "태그는 비어 있을 수 없습니다.")
                            @Size(max = CompanyConstraints.TAG_NAME_MAX, message = "태그는 20자를 넘을 수 없습니다.") String> tagNames) {
        companyUpdateService.replaceTagsByAdmin(id, tagNames);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/materials")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceMaterialsByAdmin(@PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.MATERIALS_MAX, message = "취급 소재는 20개를 넘을 수 없습니다.")
                    List<@NotBlank(message = "소재명은 비어 있을 수 없습니다.")
                            @Size(max = CompanyConstraints.MATERIAL_NAME_MAX, message = "소재명은 50자를 넘을 수 없습니다.") String> materialNames) {
        companyUpdateService.replaceMaterialsByAdmin(id, materialNames);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/equipment")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceEquipmentByAdmin(@PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.EQUIPMENTS_MAX, message = "보유 장비는 20개를 넘을 수 없습니다.")
                    List<@NotBlank(message = "장비명은 비어 있을 수 없습니다.")
                            @Size(max = CompanyConstraints.EQUIPMENT_NAME_MAX, message = "장비명은 50자를 넘을 수 없습니다.") String> equipmentNames) {
        companyUpdateService.replaceEquipmentByAdmin(id, equipmentNames);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/images")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceGalleryImagesByAdmin(@PathVariable Long id, @Valid @RequestBody @Size(max = CompanyConstraints.DETAIL_IMAGES_CEILING, message = "상세 이미지는 30장을 넘을 수 없습니다.")
                    List<@NotNull(message = "이미지 항목은 비어 있을 수 없습니다.") ImageRequest> images) {
        companyUpdateService.replaceGalleryImagesByAdmin(id, images);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/contacts")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceContactsByAdmin(@PathVariable Long id, @Valid @RequestBody @Size(max = CompanyConstraints.CONTACTS_MAX, message = "연락처는 현재 1건만 등록할 수 있습니다.")
                    List<@NotNull(message = "연락처 항목은 비어 있을 수 없습니다.") ContactRequest> contacts) {
        companyUpdateService.replaceContactsByAdmin(id, contacts);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/references")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceReferencesByAdmin(@PathVariable Long id, @Valid @RequestBody @Size(max = CompanyConstraints.REFERENCES_MAX, message = "프로젝트 레퍼런스는 현재 1건만 등록할 수 있습니다.")
                    List<@NotNull(message = "레퍼런스 항목은 비어 있을 수 없습니다.") ReferenceRequest> references) {
        companyUpdateService.replaceReferencesByAdmin(id, references);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceCategoriesByAdmin(@PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.CATEGORIES_CEILING, message = "카테고리는 10개를 넘을 수 없습니다.")
                    List<@NotNull(message = "카테고리 항목은 비어 있을 수 없습니다.") Long> categoryIds) {
        companyUpdateService.replaceCategoriesByAdmin(id, categoryIds);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/industries")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceIndustriesByAdmin(@PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.INDUSTRIES_MAX, message = "산업군은 5개를 넘을 수 없습니다.")
                    List<@NotNull(message = "산업군 항목은 비어 있을 수 없습니다.") Long> industryIds) {
        companyUpdateService.replaceIndustriesByAdmin(id, industryIds);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/certifications")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceCertificationsByAdmin(@PathVariable Long id,
                                                         @Valid @RequestBody @Size(max = CompanyConstraints.CERTIFICATIONS_MAX, message = "인증은 10개를 넘을 수 없습니다.")
                    List<@NotNull(message = "인증 항목은 비어 있을 수 없습니다.") CertificationRequest> certifications) {
        companyUpdateService.replaceCertificationsByAdmin(id, certifications);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/countries")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceCountriesByAdmin(@PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.COUNTRIES_MAX, message = "대응 가능 국가는 20개를 넘을 수 없습니다.")
                    List<@NotNull(message = "국가 항목은 비어 있을 수 없습니다.") Long> countryIds) {
        companyUpdateService.replaceCountriesByAdmin(id, countryIds);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/admin/companies/{id}/regions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replaceRegionsByAdmin(@PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.DOMESTIC_REGIONS_MAX, message = "대응 가능 지역은 20개를 넘을 수 없습니다.")
                    List<@NotNull(message = "지역 항목은 비어 있을 수 없습니다.") Long> domesticRegionIds) {
        companyUpdateService.replaceRegionsByAdmin(id, domesticRegionIds);
        return ApiResponse.ok();
    }
}
