package project.plantly.domain.company.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.dto.AdminCompanySubscriptionResponse;
import project.plantly.domain.company.dto.CompanyAggregate;
import project.plantly.domain.company.dto.CompanyDetailResponse;
import project.plantly.domain.company.dto.CompanyPublicResponse;
import project.plantly.domain.company.dto.CompanyShowcaseResponse;
import project.plantly.domain.company.dto.CompanySubscriptionResponse;
import project.plantly.domain.company.dto.OwnerSubscriptionSummary;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanySubscription;
import project.plantly.domain.company.policy.GradePolicyRegistry;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.MemberRole;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.AdminCompanyCardRepository;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanySubscriptionRepository;
import project.plantly.domain.company.repository.FavoriteCompanyCardRepository;
import project.plantly.domain.company.repository.OwnedCompanyCardRepository;
import project.plantly.domain.company.repository.ShowcaseCardRepository;
import project.plantly.domain.company.repository.ShowcaseCardRepository.ShowcaseRail;
import project.plantly.domain.company.search.AdminCompanySearchCriteria;
import project.plantly.domain.company.search.CompanySearchCriteria;
import project.plantly.domain.company.search.CompanySearchRepository;
import project.plantly.domain.company.search.dto.AdminCompanySummary;
import project.plantly.domain.company.search.dto.CompanySummary;
import project.plantly.domain.company.stat.CompanyFavoriteRepository;
import project.plantly.domain.company.stat.CompanyLikeRepository;
import project.plantly.global.PageResponse;
import project.plantly.global.exception.BusinessException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 회사 상세 조회 전담 서비스. 등록(CompanyService)과 분리한 읽기 전용 경로.
// 세 진입점(공개 / 소유자 / 관리자)은 '누가 무엇을 볼 수 있는가'(접근 제어 + 응답 형태)만 다르다.
// 원자료 적재(부속 fan-out)는 CompanyAggregateLoader 가 전담하고, 여기선 접근제어 + 위임 + 응답 매핑만 한다.
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyQueryService {

    private final CompanyRepository companyRepository;
    private final CompanyMemberRepository companyMemberRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CompanyAggregateLoader aggregateLoader;

    // 등급 → 혜택 매핑의 단일 출처. 쓰기 정책과 같은 표를 조회 쪽에서도 읽는다(동영상 노출 자격).
    private final GradePolicyRegistry gradePolicyRegistry;
    private final CompanySearchRepository companySearchRepository;
    private final OwnedCompanyCardRepository ownedCompanyCardRepository;
    private final FavoriteCompanyCardRepository favoriteCompanyCardRepository;
    private final AdminCompanyCardRepository adminCompanyCardRepository;
    private final CompanyLikeRepository companyLikeRepository;
    private final CompanyFavoriteRepository companyFavoriteRepository;
    private final ShowcaseCardRepository showcaseCardRepository;

    // 메인 화면 노출 자리 수. 상수가 아니라 설정값인 이유는 "자리를 늘린다"가 후보 초과에 대한
    // 가장 흔하고 가장 싼 대응이기 때문이다 — 코드 수정 없이 값만 바꿔 대응할 수 있어야 한다.
    @Value("${app.showcase.spotlight-slots}")
    private int spotlightSlots;

    @Value("${app.showcase.featured-slots}")
    private int featuredSlots;

    // 최근 등록 레일의 자리 수. 위 둘과 성격이 다르다 — 저쪽은 "노출 자격을 몇 명에게 줄 것인가"라 늘리면
    // 공급이 늘지만, 이쪽은 순수한 화면 분량이다. 더 보려면 목록 화면으로 넘어가므로 여기서 늘릴 이유가 없다.
    @Value("${app.showcase.latest-slots}")
    private int latestSlots;

    // 공개 회사 목록/검색: 통합 키워드 + 고급 + 패싯(인증/산업군/카테고리 서브트리). 색인된·비삭제 회사만,
    // 기본 정렬(spotlight→featured→최신). 엔진 교체(PG↔ES)는 CompanySearchRepository 뒤에서만 일어난다.
    // viewerId = 로그인 유저 id(익명이면 null). 좋아요/즐겨찾기 여부는 검색 결과를 뷰어 기준으로 후처리(enrich)해 채운다.
    public PageResponse<CompanySummary> search(CompanySearchCriteria criteria, Pageable pageable, Long viewerId) {
        Page<CompanySummary> page = companySearchRepository.search(criteria, pageable);
        List<CompanySummary> content = enrichViewerFlags(page.getContent(), viewerId);
        return PageResponse.of(content, page.getTotalElements(), pageable);
    }

    // 검색 결과 한 페이지에 뷰어별 좋아요/즐겨찾기 상태를 덧입힌다. 검색 엔진(PG/ES) 밖의 후처리라 seam 을 오염시키지 않는다.
    // 익명이거나 결과가 없으면 그대로 반환(추가 쿼리 없음). 로그인 뷰어면 좋아요·즐겨찾기 각각 배치 조회 1회(행마다 N+1 없음).
    private List<CompanySummary> enrichViewerFlags(List<CompanySummary> cards, Long viewerId) {
        if (viewerId == null || cards.isEmpty()) {
            return cards;
        }
        List<Long> ids = cards.stream().map(CompanySummary::id).toList();
        Set<Long> liked = Set.copyOf(companyLikeRepository.findLikedCompanyIds(viewerId, ids));
        Set<Long> favorited = Set.copyOf(companyFavoriteRepository.findFavoritedCompanyIds(viewerId, ids));
        return cards.stream()
                .map(c -> c.withViewerFlags(liked.contains(c.id()), favorited.contains(c.id())))
                .toList();
    }

    // 메인 화면 노출 영역: 스팟라이트·추천·최근 등록 세 레일을 자리 수만큼 잘라서 함께 내려준다.
    // 노출 자격 판단은 전부 ShowcaseCardRepository 안에 있다 — 여기선 자리 수 적용과 개인화만 한다.
    //
    // 세 레일을 이어 붙여 enrich 를 한 번만 태운다. 레일별로 따로 태우면 배치 조회가 2회 → 6회로 늘고,
    // 여러 레일에 같은 회사가 있으면 같은 회사를 반복 조회하게 된다.
    public CompanyShowcaseResponse getShowcase(Long viewerId) {
        ShowcaseRail spotlightRail = showcaseCardRepository.findSpotlight(spotlightSlots);
        ShowcaseRail featuredRail = showcaseCardRepository.findFeatured(featuredSlots);
        // 최근 등록만 반환 타입이 다르다 — 후보 초과가 정상 상태라 셀 이유가 없어 총수를 싣지 않는다.
        // 그래서 아래 초과 경고도 이 레일에는 없다(붙일 값 자체가 없고, 붙였다면 매 호출 로그가 찍혔을 것이다).
        List<CompanySummary> latestCards = showcaseCardRepository.findLatest(latestSlots);

        warnIfOverflow("스팟라이트", spotlightRail, spotlightSlots);
        warnIfOverflow("추천", featuredRail, featuredSlots);

        List<CompanySummary> combined = new ArrayList<>(spotlightRail.cards());
        combined.addAll(featuredRail.cards());
        combined.addAll(latestCards);
        List<CompanySummary> enriched = enrichViewerFlags(combined, viewerId);

        int spotlightEnd = spotlightRail.cards().size();
        int featuredEnd = spotlightEnd + featuredRail.cards().size();
        return new CompanyShowcaseResponse(
                List.copyOf(enriched.subList(0, spotlightEnd)),
                List.copyOf(enriched.subList(spotlightEnd, featuredEnd)),
                List.copyOf(enriched.subList(featuredEnd, enriched.size())));
    }

    // 후보가 자리보다 많아지면 초과분은 조용히 잘린다 — 돈을 받고도 노출되지 않는 고객이 생기는데
    // 에러가 나지 않아 아무도 모른다. 이 로그가 "로테이션을 붙일 때가 됐다"를 알리는 유일한 신호다.
    private void warnIfOverflow(String railName, ShowcaseRail rail, int slots) {
        if (rail.candidateCount() > slots) {
            log.warn("{} 레일 후보 {}건이 자리 {}칸을 초과했습니다 — {}건이 노출되지 않습니다. 로테이션 도입 검토 필요.",
                    railName, rail.candidateCount(), slots, rail.candidateCount() - slots);
        }
    }

    // 내 회사 목록: 로그인 유저가 소유(userId=본인)한 미삭제 회사를 요약 카드로, 최신 등록순 페이징. 검색/패싯 없음.
    public PageResponse<CompanySummary> listMyCompanies(Long ownerUserId, Pageable pageable) {
        Page<CompanySummary> page = ownedCompanyCardRepository.findOwnedBy(ownerUserId, pageable);
        return PageResponse.of(page.getContent(), page.getTotalElements(), pageable);
    }

    // 내 즐겨찾기 목록: 로그인 유저가 즐겨찾기한 미삭제 회사를 요약 카드로, 즐겨찾기순(담은 최신순) 페이징. 검색/패싯 없음.
    // 카드 프로젝션은 viewer 독립이라 플래그가 false 로 깔려 나오므로 검색과 동일하게 enrich 를 태운다 —
    // 안 태우면 즐겨찾기 목록인데 하트가 전부 빈 채로 내려간다. favoritedByMe 는 이 목록에선 정의상 전부 true 지만,
    // 개인화 경로를 하나로 유지하려고 특수 처리 없이 같은 배치 조회를 쓴다(페이지 id 들에 대한 색인 IN 조회 1회).
    public PageResponse<CompanySummary> listMyFavorites(Long viewerId, Pageable pageable) {
        Page<CompanySummary> page = favoriteCompanyCardRepository.findFavoritedBy(viewerId, pageable);
        List<CompanySummary> content = enrichViewerFlags(page.getContent(), viewerId);
        return PageResponse.of(content, page.getTotalElements(), pageable);
    }

    // 관리자 회사 목록: 기본 전체(삭제 포함), 불리언 3-상태(verified/featured/spotlight/deleted)·회사명·소유자 id 로
    // 교집합 필터링. 운영 필드를 포함한 카드로 최신 등록순 페이징. (권한 검증은 컨트롤러 @PreAuthorize 가 담당)
    public PageResponse<AdminCompanySummary> listForAdmin(AdminCompanySearchCriteria criteria, Pageable pageable) {
        Page<AdminCompanySummary> page = adminCompanyCardRepository.findForAdmin(criteria, pageable);
        return PageResponse.of(page.getContent(), page.getTotalElements(), pageable);
    }

    // 공개(비소유자) 조회: 소프트 삭제되거나 비공개(PRIVATE)인 회사는 미존재로 취급한다(존재 노출 방지 → 404).
    // viewerId = 로그인 유저 id (익명이면 null). 좋아요/즐겨찾기 여부(likedByMe/favoritedByMe)는 이 뷰어 기준으로 계산한다.
    public CompanyPublicResponse getPublic(Long companyId, Long viewerId) {
        Company company = companyRepository.findById(companyId)
                .filter(c -> !c.isDeleted())
                .filter(c -> c.getVisibility() == CompanyVisibility.PUBLIC)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));

        // 익명 뷰어는 개인화 상태가 없으므로 조회 없이 false. 로그인 뷰어만 존재 여부를 확인한다.
        boolean likedByMe = viewerId != null && companyLikeRepository.existsByUserIdAndCompanyId(viewerId, companyId);
        boolean favoritedByMe = viewerId != null && companyFavoriteRepository.existsByUserIdAndCompanyId(viewerId, companyId);

        CompanyAggregate aggregate = aggregateLoader.load(company);
        // 마지막 false = 꺼진(active=false) 컬렉션 항목은 응답에서 아예 뺀다. 등급을 여기서 다시 읽지 않는다 —
        // "왜 꺼졌는가"는 쓰기·재조정 쪽이 이미 판단해 저장해 둔 상태이고, 조회는 그 상태만 읽는다.
        return CompanyPublicResponse.from(aggregate, likedByMe, favoritedByMe, false);
    }

    // 소유자 전용 상세: 요청자가 해당 회사의 멤버여야 한다. 삭제된 회사도 소유자에게는 보인다.
    public CompanyDetailResponse getOwnerView(Long companyId, Long requesterId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));

        if (!companyMemberRepository.existsByCompanyIdAndUserId(companyId, requesterId)) {
            throw new BusinessException(CompanyErrorCode.COMPANY_ACCESS_DENIED);
        }

        CompanyAggregate aggregate = aggregateLoader.load(company);
        return CompanyDetailResponse.from(aggregate);
    }

    // 관리자 상세: 상태(삭제/미연동 등) 무관하게 전체를 본다. (권한 검증은 컨트롤러 @PreAuthorize 가 담당)
    public CompanyDetailResponse getForAdmin(Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));

        CompanyAggregate aggregate = aggregateLoader.load(company);
        return CompanyDetailResponse.from(aggregate);
    }

    // 소유자 전용 구독 조회: 요청자가 해당 회사의 멤버여야 한다. 접근제어는 getOwnerView 와 동일하게 미러한다.
    // 회사 집계는 적재하지 않고 구독 사실만 단독으로 내려준다. (구독은 등록 트랜잭션에서 회사당 1건 생성되므로 항상 존재)
    public CompanySubscriptionResponse getSubscriptionForOwner(Long companyId, Long requesterId) {
        // 존재 검증 겸 companyName 확보. 어차피 하던 존재 조회(existsById)를 findById 로 승격했을 뿐 — 조인·추가 쿼리 없음.
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));

        if (!companyMemberRepository.existsByCompanyIdAndUserId(companyId, requesterId)) {
            throw new BusinessException(CompanyErrorCode.COMPANY_ACCESS_DENIED);
        }

        CompanySubscription subscription = companySubscriptionRepository.getByCompanyId(companyId);

        return CompanySubscriptionResponse.from(subscription, company.getCompanyName(),
                gradePolicyRegistry.of(subscription.effectiveGrade()));
    }

    // 관리자 구독 조회: 소유(멤버) 검증 없이(상태 무관) 회사+구독을 로드해 감사 필드까지 내려준다.
    // 권한(ADMIN)은 컨트롤러 @PreAuthorize 가 담당한다. getForAdmin 과 동일하게 삭제/미연동 회사도 조회된다.
    public AdminCompanySubscriptionResponse getSubscriptionForAdmin(Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND));

        CompanySubscription subscription = companySubscriptionRepository.getByCompanyId(companyId);

        return AdminCompanySubscriptionResponse.from(subscription, company.getCompanyName(),
                gradePolicyRegistry.of(subscription.effectiveGrade()));
    }

    // 유저 → '소유(OWNER)한 회사의 구독 요약' 매핑을 배치로 해석한다. 관리자 유저 목록의 등급 배지(연착륙)용.
    // 구독은 회사 소유라 유저 관점에선 파생 뷰일 뿐이다 — effectiveGrade 는 엔티티 규칙을 그대로 재사용해 파생한다.
    // 소유 회사가 없는 유저는 맵에 없다(호출부가 배지 null 처리). 계정당 소유 회사 1건 가정이며, 다건이면 임의 1건을 남긴다.
    public Map<Long, OwnerSubscriptionSummary> findOwnerSubscriptionSummaries(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return companySubscriptionRepository.findOwnerSubscriptionRows(MemberRole.OWNER, userIds).stream()
                .collect(Collectors.toMap(
                        row -> row.userId(),
                        row -> OwnerSubscriptionSummary.from(row.subscription()),
                        (existing, duplicate) -> existing));
    }
}
