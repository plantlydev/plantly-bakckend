package project.plantly.global.seed;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyImage;
import project.plantly.domain.company.entity.link.CompanyCategory;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.repository.CompanyCategoryRepository;
import project.plantly.domain.company.repository.CompanyImageRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.company.service.CompanyService;

import java.util.List;

/**
 * 회사 생성·상태 조정.
 *
 * <p>생성은 반드시 {@link CompanyService} 의 실제 등록 경로를 탄다. 인증 소비, 초안 삭제, 소유자 멤버십,
 * 검색 도큐먼트·카테고리 closure 동기화가 전부 그 안에서 일어나므로, 리포지토리로 직접
 * 저장하면 "DB 엔 있는데 목록·검색엔 안 나오는" 회사가 생긴다.
 *
 * <p>등록 이후의 상태(삭제·운영 플래그)는 관리자 API 가 하는 것과 같은 도메인 메서드로 바꾼다.
 * 등록 시점에는 만들 수 없는 상태(소프트 삭제, 검수·추천·스팟라이트 고정)를 케이스로 만들려면 이 단계가 필요하다.
 */
@Component
@Profile(SeedConfig.PROFILE)
@RequiredArgsConstructor
public class SeedCompanyFactory {

    private final CompanyService companyService;
    private final CompanyRepository companyRepository;
    private final CompanyCategoryRepository companyCategoryRepository;
    private final CompanyImageRepository imageRepository;
    private final CompanySearchDocumentWriter searchDocumentWriter;

    /** 관리자 등록. 소유자 미연동(CompanyMember 0건)으로 시작한다. */
    public Long createByAdmin(Long adminId, CompanyCreateRequest request) {
        return companyService.createByAdmin(adminId, request);
    }

    /** 자가등록. 선행 인증을 소비하고 소유자 멤버십·businessVerified 까지 실제 경로로 채운다. */
    public Long createByUser(Long userId, MyCompanyCreateRequest request) {
        return companyService.createByUser(userId, request);
    }

    /** 관리자 운영 플래그. 등록 시엔 전부 false 로 시작하므로 목표 상태를 그대로 지정한다. */
    @Transactional
    public void applyFlags(Long companyId, boolean verified, boolean featured, boolean spotlight, int spotlightOrder) {
        Company company = load(companyId);
        company.changeVerified(verified);
        company.changeFeatured(featured);
        if (spotlight) {
            company.turnOnSpotlight(spotlightOrder);
        } else {
            company.turnOffSpotlight();
        }
    }

    /** 소프트 삭제. 공개 목록·상세에서 빠지고 관리자 목록에만 남는다(복구 대상). */
    @Transactional
    public void softDelete(Long companyId) {
        load(companyId).delete();
    }

    /**
     * 앞쪽 일부만 남기고 컬렉션을 비활성화(active=false)한다.
     *
     * <p>이 메서드가 필요한 이유는 검증 공백 때문이다. 프로덕션 경로에는 항목을 끄는 주체가 없어(등급 재조정이
     * 폐지됐다) {@code active = false} 인 행이 한 건도 만들어지지 않는다. 그러면 조회·카드·색인에 넣어둔 active
     * 필터가 전부 '한 번도 참이 아닌 조건'으로 남아 동작을 확인할 수 없다. active 플래그를 걷어낼 때 함께 지운다.
     *
     * <p>남기는 기준은 {@code displayOrder} 앞에서부터다. 등록·PUT 교체가 요청 순서를 그대로
     * displayOrder 로 삼으므로, 이건 "사용자가 앞에 둔 것을 남긴다"는 뜻이 된다.
     *
     * <p>마지막에 검색 도큐먼트를 다시 쓴다. 카테고리 closure 는 active 인 링크만으로 재생성되므로, 이걸
     * 빼먹으면 카드에서는 사라진 카테고리가 패싯 검색에는 계속 잡힌다.
     */
    @Transactional
    public void deactivateOverflow(Long companyId, int keepCategories, int keepDetailImages) {
        List<CompanyCategory> links = companyCategoryRepository.findLinksByCompanyId(companyId);
        for (int i = keepCategories; i < links.size(); i++) {
            links.get(i).changeActive(false);
        }

        List<CompanyImage> detailImages = imageRepository.findByCompanyIdOrderByDisplayOrderAsc(companyId).stream()
                .filter(image -> image.getProjectReference() == null && image.getImageType() == ImageType.DETAIL)
                .toList();
        for (int i = keepDetailImages; i < detailImages.size(); i++) {
            detailImages.get(i).changeActive(false);
        }

        searchDocumentWriter.write(companyId);
    }

    private Company load(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("회사가 없습니다. companyId=" + companyId));
    }
}
