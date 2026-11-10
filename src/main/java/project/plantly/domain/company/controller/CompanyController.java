package project.plantly.domain.company.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
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
import project.plantly.domain.company.dto.CompanyCreateRequest.CertificationRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ContactRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ImageRequest;
import project.plantly.domain.company.dto.CompanyCreateRequest.ReferenceRequest;
import project.plantly.domain.company.dto.CompanyDetailResponse;
import project.plantly.domain.company.dto.CompanyDraftResponse;
import project.plantly.domain.company.dto.CompanyDraftSummary;
import project.plantly.domain.company.dto.CompanyPublicResponse;
import project.plantly.domain.company.dto.CompanyShowcaseResponse;
import project.plantly.domain.company.dto.DraftChecks;
import project.plantly.domain.company.dto.CompanyStatsResponse;
import project.plantly.domain.company.dto.CompanyReverificationRequest;
import project.plantly.domain.company.dto.CompanyReverificationResponse;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.dto.CompanyVerificationRequest;
import project.plantly.domain.company.dto.CompanyVerificationResponse;
import project.plantly.domain.company.dto.CompanyVisibilityUpdateRequest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.search.dto.CompanySearchRequest;
import project.plantly.domain.company.search.dto.CompanySummary;
import project.plantly.domain.company.service.CompanyDraftService;
import project.plantly.domain.company.service.CompanyStatsService;
import project.plantly.domain.company.service.CompanyQueryService;
import project.plantly.domain.company.service.CompanyRegistrationService;
import project.plantly.domain.company.service.CompanyUpdateService;
import project.plantly.domain.company.service.CompanyVerificationService;
import project.plantly.global.PageResponse;
import project.plantly.global.response.ApiResponse;
import project.plantly.global.response.IdResponse;
import project.plantly.global.security.UserPrincipal;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyRegistrationService companyRegistrationService;
    private final CompanyQueryService companyQueryService;
    private final CompanyUpdateService companyUpdateService;
    private final CompanyVerificationService companyVerificationService;
    private final CompanyDraftService companyDraftService;
    private final CompanyStatsService companyStatsService;

    // 사업자 인증 — 회사 등록 폼 앞단에서 아이디 중복 확인처럼 먼저 수행한다.
    // 국세청 진위확인(사업자번호+대표자명+개업일자) + 상태조회(계속사업자 여부) + 중복 검사를 한 번에 통과해야
    // verificationId 를 발급한다. 이 식별자를 등록 요청에 실어 보내면 서버가 검증된 값을 채운다.
    // 등록보다 먼저 두는 이유: 회사 정보를 20여 개 채운 뒤 마지막에 사업자번호 중복으로 튕기지 않게 하기 위해.
    @PostMapping("/api/v1/companies/verification")
    public ApiResponse<CompanyVerificationResponse> verifyBusiness(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @Valid @RequestBody CompanyVerificationRequest request) {
        CompanyVerificationResponse response = companyVerificationService.verify(principal.getUser().getId(), request);
        return ApiResponse.success("사업자 인증이 완료되었습니다.", response);
    }

    // 사업자 재인증 — 소유자만. 사업자번호는 요청으로 받지 않고(DB 저장값 사용) 새 대표자명·개업일자만 국세청에
    // 재확인한다. 통과하면 두 값을 검증값으로 덮어쓰고 인증 시각을 새로 찍는다. 국세청 인증을 받은 회사는 일반
    // 수정(PATCH /{id})으로 대표자명·개업일자를 바꿀 수 없으므로, 국세청 정보가 바뀌었을 때 이 경로로만 갱신한다.
    // (최초 인증 후 1년 경과 시 재인증을 유도하는 이벤트의 실행 창구가 될 자리 — 이벤트 발행 자체는 추후.)
    @PostMapping("/api/v1/companies/{id}/verification")
    public ApiResponse<CompanyReverificationResponse> reverifyBusiness(@AuthenticationPrincipal UserPrincipal principal,
                                                                       @PathVariable Long id,
                                                                       @Valid @RequestBody CompanyReverificationRequest request) {
        CompanyReverificationResponse response =
                companyVerificationService.reverify(principal.getUser().getId(), id, request);
        return ApiResponse.success("사업자 재인증이 완료되었습니다.", response);
    }

    // 유저 자가등록 — 인증된 본인이 소유자가 되고, 선행 인증을 소비해 사업자 인증 완료 상태로 생성된다.
    // 요청 본문에 사업자번호·대표자명·개업일자 자리가 없다(MyCompanyCreateRequest) — 서버가 인증본에서만 채운다.
    //
    // CompanyService 가 아니라 CompanyRegistrationService 를 부르는 이유는 트랜잭션 경계다 — 인증이 만료된
    // 경우 발행 직전에 국세청 재질의가 끼어들 수 있고, 그 HTTP 호출은 등록 트랜잭션 밖에서 끝나야 한다.
    // 사용자에게 다시 받을 값이 없으므로 이 단계는 폼에 드러나지 않는다(그 빈의 주석 참고).
    @PostMapping("/api/v1/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IdResponse> createMyCompany(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody MyCompanyCreateRequest request) {

        Long id = companyRegistrationService.register(principal.getUser().getId(), request);
        return ApiResponse.success("회사 등록이 완료되었습니다.", new IdResponse(id));
    }

    // ===== 임시저장(초안) — 발행(POST /companies) 전, 작성 중인 폼 상태를 사업자번호 1건당 1개 보관한다. =====
    // 경로 키는 사업자등록번호다(선행 인증 식별자가 아니다). 인증은 만료·재발급되며 id 가 바뀌지만 사용자가 쓴
    // 글은 그럴 이유가 없어서다 — 인증 id 로 키잉하면 재인증하는 순간 작성분에 닿을 수 없게 된다(CompanyDraft 주석).
    // 덕분에 클라이언트는 인증 식별자를 잃어버려도(기기 변경·저장소 초기화) 폼 첫 칸의 사업자번호만으로 이어쓸 수 있다.
    // 하이픈은 있어도 되고 없어도 된다(서버가 정규화한다). 접근 권한은 여전히 인증 이력이 준다.
    //
    // 'drafts/{businessNumber}' 는 두 세그먼트라 공개 상세(/{id}, 단일 세그먼트)와 겹치지 않으며, SecurityConfig 의
    // 기본 규칙(anyRequest().authenticated())으로 보호된다. 목록(GET /drafts)만 단일 세그먼트라 따로 규칙을 둔다.

    // 자동저장(upsert). 필수값·원소·형식·마스터 검증은 하지 않고 부분 입력을 그대로 저장한다 — 그건 발행 시점에만 한다.
    // 크기 상한(컬렉션 개수·글자수)만은 발행과 같은 값으로 건다(DraftChecks 그룹) — 과도한 본문이 쌓이지 않게 하는 손쉬운 둑.
    // 컬렉션도 요청 본문에 함께 실려 초안 payload 안에 보관되므로, 회사 생성 전까지 한 문서로 관리된다.
    @PutMapping("/api/v1/companies/drafts/{businessNumber}")
    public ApiResponse<Void> saveDraft(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable String businessNumber,
                                       // 미완성 폼이라 @Valid(Default) 대신 크기 상한(@Size)만 검사한다 — DraftChecks 참고.
                                       @Validated(DraftChecks.class) @RequestBody MyCompanyCreateRequest request) {
        companyDraftService.save(principal.getUser().getId(), businessNumber, request);
        return ApiResponse.ok();
    }

    // 내 초안 목록 — 이어쓸 초안을 사업자번호 없이 고르게 한다(최근 저장순, 페이징 없음). 폼 복원은 고른 뒤
    // 단건 조회가 한다. 'drafts' 단일 세그먼트라 공개 상세(/{id}) permitAll 에 걸리므로 SecurityConfig 에서
    // 그 앞에 인증 규칙을 둔다('my'/'favorites' 와 같은 처리).
    @GetMapping("/api/v1/companies/drafts")
    public ApiResponse<List<CompanyDraftSummary>> listDrafts(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(companyDraftService.list(principal.getUser().getId()));
    }

    // 재진입 시 폼 복원 — 저장했던 폼 상태(기본 필드 + 컬렉션)와 마지막 저장 시각을 반환한다. 초안이 없으면 404.
    // payload 안의 verificationId 는 저장 당시 값이라 낡을 수 있다 — 발행 시에는 현재 유효한 값으로 덮어써 보낸다.
    @GetMapping("/api/v1/companies/drafts/{businessNumber}")
    public ApiResponse<CompanyDraftResponse> getDraft(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable String businessNumber) {
        return ApiResponse.success(companyDraftService.get(principal.getUser().getId(), businessNumber));
    }

    // 임시저장 수동 폐기. 발행이 성공하면 초안은 서버가 자동 삭제하므로(CompanyService.createByUser), 이 경로는
    // 사용자가 작성을 포기할 때만 쓴다. 초안이 없어도 멱등하게 성공한다.
    @DeleteMapping("/api/v1/companies/drafts/{businessNumber}")
    public ApiResponse<Void> deleteDraft(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable String businessNumber) {
        companyDraftService.delete(principal.getUser().getId(), businessNumber);
        return ApiResponse.ok();
    }

    // 공개 회사 목록/검색 — 인증 없이 누구나. 통합 키워드 + 고급검색 + 패싯(인증/산업군/카테고리 서브트리).
    // 기본 정렬(spotlight→featured→최신)으로 페이징된 요약 카드를 반환한다.
    @GetMapping("/api/v1/companies")
    public ApiResponse<PageResponse<CompanySummary>> searchCompanies(@AuthenticationPrincipal UserPrincipal principal,
                                                                     @ModelAttribute CompanySearchRequest request,
                                                                     @PageableDefault(size = 20) Pageable pageable) {
        // 인증은 선택: 로그인 상태면 카드마다 좋아요/즐겨찾기 여부를 채우고, 익명이면 principal=null → 전부 false.
        Long viewerId = (principal == null) ? null : principal.getUser().getId();
        return ApiResponse.success(companyQueryService.search(request.toCriteria(), pageable, viewerId));
    }

    // 메인 화면 노출 영역 — 인증 없이 누구나. 스팟라이트·추천 레일을 자리 수만큼 잘라 함께 반환한다.
    // 목록(GET /companies)을 받아 프론트가 플래그로 걸러내는 방식과 달리, 어느 회사가 자리를 차지하는지는
    // 서버가 정한다 — 후보가 자리보다 많아져도 화면이 조용히 비지 않는다.
    // 'my'/'favorites' 와 같은 단일 세그먼트라 공개 상세(/{id})보다 먼저 매칭된다.
    @GetMapping("/api/v1/companies/showcase")
    public ApiResponse<CompanyShowcaseResponse> getShowcase(@AuthenticationPrincipal UserPrincipal principal) {
        // 인증은 선택: 로그인 상태면 카드마다 좋아요/즐겨찾기 여부를 채우고, 익명이면 principal=null → 전부 false.
        Long viewerId = (principal == null) ? null : principal.getUser().getId();
        return ApiResponse.success(companyQueryService.getShowcase(viewerId));
    }

    // 메인 화면 현황 지표 — 인증 없이 누구나. 뷰어와 무관한 숫자라 principal 을 받지 않는다.
    // 개수만 필요한 화면이 목록 API 를 빌려 쓰지 않게 하려고 따로 둔다(size=1 조회로 총수만 빼 가는 식).
    // 'showcase'/'my' 와 같은 단일 세그먼트라 공개 상세(/{id})보다 먼저 매칭된다.
    @GetMapping("/api/v1/companies/stats")
    public ApiResponse<CompanyStatsResponse> getStats() {
        return ApiResponse.success(companyStatsService.getStats());
    }

    // 내가 등록한 회사 목록 — 인증된 본인 소유(userId=본인) 미삭제 회사를 요약 카드로, 최신순 페이징(검색 없음).
    // 단일 세그먼트 'my' 라 공개 상세(/{id})보다 먼저 매칭된다(SecurityConfig 에서도 /{id} permitAll 앞에 인증 규칙을 둔다).
    @GetMapping("/api/v1/companies/my")
    public ApiResponse<PageResponse<CompanySummary>> getMyCompanies(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(companyQueryService.listMyCompanies(principal.getUser().getId(), pageable));
    }

    // 내 즐겨찾기 회사 목록 — 인증된 본인이 즐겨찾기한 미삭제 회사를 요약 카드로, 즐겨찾기순(담은 최신순) 페이징.
    // 검색/패싯 없음: 즐겨찾기는 회사 속성이 아니라 뷰어↔회사 관계라 공개 검색(GET /companies)의 조건으로 넣지 않고
    // 별도 경로로 둔다(정렬 키 f.created_at 도 검색 쿼리엔 없다). 'my' 와 같은 단일 세그먼트라 /{id} 보다 먼저 매칭된다.
    @GetMapping("/api/v1/companies/favorites")
    public ApiResponse<PageResponse<CompanySummary>> getMyFavorites(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(companyQueryService.listMyFavorites(principal.getUser().getId(), pageable));
    }

    // 일반(공개) 상세 조회 — 누구에게나 안전한 공개 필드만 반환한다. 소프트 삭제된 회사는 404.
    // 인증은 선택: 로그인 상태면 principal 로 좋아요/즐겨찾기 여부(likedByMe/favoritedByMe)를 채우고, 익명이면 principal=null → false.
    @GetMapping("/api/v1/companies/{id}")
    public ApiResponse<CompanyPublicResponse> getCompany(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long id) {
        Long viewerId = (principal == null) ? null : principal.getUser().getId();
        return ApiResponse.success(companyQueryService.getPublic(id, viewerId));
    }

    // 소유자 전용 상세 조회 — 요청자가 해당 회사의 멤버여야 하며, 내부·운영 메타(meta)까지 포함한다.
    @GetMapping("/api/v1/companies/{id}/private")
    public ApiResponse<CompanyDetailResponse> getMyCompany(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long id) {
        return ApiResponse.success(companyQueryService.getOwnerView(id, principal.getUser().getId()));
    }

    // 기본 정보 부분 수정 — 소유자만. null=미변경(sparse). 수정 값은 화면에 이미 반영되므로 본문 없이 성공만 반환한다.
    @PatchMapping("/api/v1/companies/{id}")
    public ApiResponse<Void> updateMyCompany(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long id,
                                             @Valid @RequestBody CompanyUpdateRequest request) {
        companyUpdateService.updateBasicInfoByUser(id, principal.getUser().getId(), request);
        return ApiResponse.ok();
    }

    // 공개/비공개 전환 — 소유자만. 목표 상태(PUBLIC/PRIVATE)를 지정한다(멱등). 응답은 본문 없이 성공만.
    @PatchMapping("/api/v1/companies/{id}/visibility")
    public ApiResponse<Void> changeVisibility(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id,
                                              @Valid @RequestBody CompanyVisibilityUpdateRequest request) {
        companyUpdateService.changeVisibilityByUser(id, principal.getUser().getId(), request.visibility());
        return ApiResponse.ok();
    }

    // 소유자 자가 삭제(소프트) — 본인 소유 회사를 숨김 처리한다. 멱등(이미 삭제여도 성공). 삭제 후에는 소유자 목록·공개
    // 경로에서 사라지고 관리자에게만 보이므로, 되살리기는 관리자 복구 경로에서만 한다(<<admin-company-restore>>).
    @DeleteMapping("/api/v1/companies/{id}")
    public ApiResponse<Void> deleteMyCompany(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long id) {
        companyUpdateService.deleteByUser(id, principal.getUser().getId());
        return ApiResponse.ok();
    }

    // ===== 컬렉션 전체 교체(PUT) — 소유자만. 각 컬렉션을 통째로 새 리스트로 교체한다(빈 리스트 = 전부 비우기). =====
    // 표시 순서(displayOrder)는 클라이언트가 보내지 않고 서버가 리스트 인덱스로 재부여한다. 응답은 본문 없이 성공만.

    @PutMapping("/api/v1/companies/{id}/tags")
    public ApiResponse<Void> replaceTags(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.TAGS_MAX, message = "태그는 10개를 넘을 수 없습니다.")
                    List<@NotBlank(message = "태그는 비어 있을 수 없습니다.")
                            @Size(max = CompanyConstraints.TAG_NAME_MAX, message = "태그는 20자를 넘을 수 없습니다.") String> tagNames) {
        companyUpdateService.replaceTagsByUser(id, principal.getUser().getId(), tagNames);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/materials")
    public ApiResponse<Void> replaceMaterials(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.MATERIALS_MAX, message = "취급 소재는 20개를 넘을 수 없습니다.")
                    List<@NotBlank(message = "소재명은 비어 있을 수 없습니다.")
                            @Size(max = CompanyConstraints.MATERIAL_NAME_MAX, message = "소재명은 50자를 넘을 수 없습니다.") String> materialNames) {
        companyUpdateService.replaceMaterialsByUser(id, principal.getUser().getId(), materialNames);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/equipment")
    public ApiResponse<Void> replaceEquipment(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.EQUIPMENTS_MAX, message = "보유 장비는 20개를 넘을 수 없습니다.")
                    List<@NotBlank(message = "장비명은 비어 있을 수 없습니다.")
                            @Size(max = CompanyConstraints.EQUIPMENT_NAME_MAX, message = "장비명은 50자를 넘을 수 없습니다.") String> equipmentNames) {
        companyUpdateService.replaceEquipmentByUser(id, principal.getUser().getId(), equipmentNames);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/images")
    public ApiResponse<Void> replaceGalleryImages(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long id, @Valid @RequestBody @Size(max = CompanyConstraints.DETAIL_IMAGES_MAX, message = "상세 이미지는 30장을 넘을 수 없습니다.")
                    List<@NotNull(message = "이미지 항목은 비어 있을 수 없습니다.") ImageRequest> images) {
        companyUpdateService.replaceGalleryImagesByUser(id, principal.getUser().getId(), images);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/contacts")
    public ApiResponse<Void> replaceContacts(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long id, @Valid @RequestBody @Size(max = CompanyConstraints.CONTACTS_MAX, message = "연락처는 현재 1건만 등록할 수 있습니다.")
                    List<@NotNull(message = "연락처 항목은 비어 있을 수 없습니다.") ContactRequest> contacts) {
        companyUpdateService.replaceContactsByUser(id, principal.getUser().getId(), contacts);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/references")
    public ApiResponse<Void> replaceReferences(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable Long id, @Valid @RequestBody @Size(max = CompanyConstraints.REFERENCES_MAX, message = "프로젝트 레퍼런스는 현재 1건만 등록할 수 있습니다.")
                    List<@NotNull(message = "레퍼런스 항목은 비어 있을 수 없습니다.") ReferenceRequest> references) {
        companyUpdateService.replaceReferencesByUser(id, principal.getUser().getId(), references);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/categories")
    public ApiResponse<Void> replaceCategories(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.CATEGORIES_MAX, message = "카테고리는 10개를 넘을 수 없습니다.")
                    List<@NotNull(message = "카테고리 항목은 비어 있을 수 없습니다.") Long> categoryIds) {
        companyUpdateService.replaceCategoriesByUser(id, principal.getUser().getId(), categoryIds);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/industries")
    public ApiResponse<Void> replaceIndustries(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.INDUSTRIES_MAX, message = "산업군은 5개를 넘을 수 없습니다.")
                    List<@NotNull(message = "산업군 항목은 비어 있을 수 없습니다.") Long> industryIds) {
        companyUpdateService.replaceIndustriesByUser(id, principal.getUser().getId(), industryIds);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/certifications")
    public ApiResponse<Void> replaceCertifications(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody @Size(max = CompanyConstraints.CERTIFICATIONS_MAX, message = "인증은 10개를 넘을 수 없습니다.")
                    List<@NotNull(message = "인증 항목은 비어 있을 수 없습니다.") CertificationRequest> certifications) {
        companyUpdateService.replaceCertificationsByUser(id, principal.getUser().getId(), certifications);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/countries")
    public ApiResponse<Void> replaceCountries(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.COUNTRIES_MAX, message = "대응 가능 국가는 20개를 넘을 수 없습니다.")
                    List<@NotNull(message = "국가 항목은 비어 있을 수 없습니다.") Long> countryIds) {
        companyUpdateService.replaceCountriesByUser(id, principal.getUser().getId(), countryIds);
        return ApiResponse.ok();
    }

    @PutMapping("/api/v1/companies/{id}/regions")
    public ApiResponse<Void> replaceRegions(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable Long id, @RequestBody @Size(max = CompanyConstraints.DOMESTIC_REGIONS_MAX, message = "대응 가능 지역은 20개를 넘을 수 없습니다.")
                    List<@NotNull(message = "지역 항목은 비어 있을 수 없습니다.") Long> domesticRegionIds) {
        companyUpdateService.replaceRegionsByUser(id, principal.getUser().getId(), domesticRegionIds);
        return ApiResponse.ok();
    }
}
