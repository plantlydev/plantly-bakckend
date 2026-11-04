package project.plantly.global.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import project.plantly.domain.company.category.Category;
import project.plantly.domain.company.enums.CompanyVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 계약 케이스 회사 C01~C25.
 *
 * <p>이 목록이 시드의 본체다. 각 행은 채우기 위한 데이터가 아니라 <b>프론트-백엔드 계약의 한 갈래를
 * 증명하기 위한 표본</b>이고, 회사명 앞의 케이스 코드가 그 표본의 안정 키다(id 는 시드할 때마다 바뀐다).
 *
 * <p>축은 셋이다 — 공개/비공개, 관리자등록/자가등록, 그리고 삭제·인증·큐레이션(검수·추천·스팟라이트 고정) 같은
 * 상태 플래그. 등록 시점에 지정할 수 없는 값(소프트 삭제, 운영 플래그)은 등록 후 도메인 메서드로 바꾼다.
 *
 * <p>번호는 바꾸지 않는다. {@code --app.seed.reset=true} 로 심으면 회사 id 가 케이스 번호와 같아지고, 프론트 캡처
 * 시나리오가 그 id 를 경로로 쓴다. 케이스를 없앨 때는 번호를 당기지 않고 그 자리를 다른 표본으로 채운다
 * (C08~C15 는 원래 등급·구독 케이스였다).
 */
@Slf4j
@Component
@Profile(SeedConfig.PROFILE)
@RequiredArgsConstructor
public class SeedCompanyCases {

    private final SeedCompanyFactory companies;
    private final SeedVerificationFactory verifications;
    private final SeedMasterCatalog masters;

    public List<SeedCompanyRef> create(SeedAccounts accounts) {
        List<SeedCompanyRef> refs = new ArrayList<>();
        Long adminId = accounts.admin().userId();

        // ===== 자가등록(USER). 소유자 멤버십 + businessVerified 가 실제 경로로 채워진다. =====

        refs.add(userCase(accounts.owner1(), 1, "기본공개",
                "기준선. 공개 + 자가등록 + 사업자 인증 완료 상태의 카드·상세·소유자 뷰", b -> b));

        refs.add(userCase(accounts.owner1(), 2, "비공개",
                "비공개 회사. 익명·타인에게는 목록/상세에서 빠지고 소유자와 관리자에게만 보여야 한다",
                b -> b.visibility(CompanyVisibility.PRIVATE)));

        Long deletedUserCompany = userCompany(accounts.owner1(), 3, "삭제됨", b -> b);
        companies.softDelete(deletedUserCompany);
        refs.add(new SeedCompanyRef("C03", deletedUserCompany, accounts.owner1().code(),
                "소프트 삭제. 공개 목록·상세에서 제외되고 관리자 목록에만 남아 복구 대상이 된다"));

        Long deletedPrivateCompany = userCompany(accounts.owner1(), 4, "비공개삭제",
                b -> b.visibility(CompanyVisibility.PRIVATE));
        companies.softDelete(deletedPrivateCompany);
        refs.add(new SeedCompanyRef("C04", deletedPrivateCompany, accounts.owner1().code(),
                "비공개 + 삭제. 두 필터가 동시에 걸렸을 때도 관리자 목록에서는 보여야 한다"));

        // ===== 관리자등록(ADMIN). 소유자 미연동(CompanyMember 0건). =====

        refs.add(adminCase(adminId, 5, "관리자등록",
                "관리자 대신등록(unclaimed). 공개 목록엔 나오지만 어느 계정의 '내 회사'에도 없고 소유자 뷰가 없다",
                b -> b));

        refs.add(adminCase(adminId, 6, "관리자등록비공개",
                "관리자등록 + 비공개. 소유자가 없으므로 관리자만 볼 수 있다",
                b -> b.visibility(CompanyVisibility.PRIVATE)));

        refs.add(userCase(accounts.owner2(), 7, "타인소유",
                "U2 소유. U1 로 로그인해 이 회사를 수정·삭제하려 하면 차단되어야 한다", b -> b));

        // ===== 스팟라이트 고정 순서·자리 초과 =====
        // 메인 스팟라이트는 관리자가 고정(pin)한 회사만 spotlightOrder 오름차순으로 오른다(app.showcase.spotlight-slots=5).
        // 공개 고정은 C18(1)·C19(2)·C08~C12(3~7) 로 7건이라 C11·C12 가 잘린다 — 시드를 올리면 CompanyQueryService 의
        // 초과 경고 로그가 실제로 뜨고, 잘림이 순서 뒤쪽부터 일어나는지 화면으로 확인할 수 있다.
        // C18·C19 가 앞 순서인 이유: 둘은 커버·브랜드 컬러 유무를 나란히 비교하는 케이스라 레일 안에 남아야 한다.
        for (int caseNo = 8; caseNo <= 12; caseNo++) {
            int order = caseNo - 5;
            Long pinned = adminCompany(adminId, caseNo, "고정" + order, b -> b);
            companies.applyFlags(pinned, true, false, true, order);
            refs.add(new SeedCompanyRef(code("C", caseNo), pinned, null, order <= SPOTLIGHT_SLOTS
                    ? "스팟라이트 고정 순서 " + order + ". 자리(" + SPOTLIGHT_SLOTS + "칸) 안이라 메인 레일에 노출된다"
                    : "스팟라이트 고정 순서 " + order + ". 자리(" + SPOTLIGHT_SLOTS + "칸)를 넘겨 메인 레일에서 잘려야 한다"
                            + " — 관리자 목록에서는 여전히 고정 상태로 보인다"));
        }

        Long pinnedPrivate = adminCompany(adminId, 13, "고정비공개", b -> b.visibility(CompanyVisibility.PRIVATE));
        companies.applyFlags(pinnedPrivate, true, false, true, 0);
        refs.add(new SeedCompanyRef("C13", pinnedPrivate, null,
                "스팟라이트 고정 순서 0(가장 앞)이지만 비공개. 메인 레일에서 빠져야 하고, 초과 판정의 후보 수(7)에도 세지지 않는다"));

        // ===== 인증·큐레이션 플래그 =====
        // 검수(verified)와 추천(featured)은 서로 독립인 플래그다. 하나만 켜진 케이스(C14·C15)와 둘 다 켜진 케이스(C17),
        // 둘 다 없는 케이스(C16)를 나란히 두어 배지·추천 섹션이 각 플래그를 따로 읽는지 확인한다.

        Long verifiedOnly = adminCompany(adminId, 14, "검수만", b -> b);
        companies.applyFlags(verifiedOnly, true, false, false, 0);
        refs.add(new SeedCompanyRef("C14", verifiedOnly, null,
                "관리자 검수(verified)만 켜짐. 검수 배지는 붙지만 추천 섹션에는 없어야 한다"));

        Long featuredOnly = adminCompany(adminId, 15, "추천만", b -> b);
        companies.applyFlags(featuredOnly, false, true, false, 0);
        refs.add(new SeedCompanyRef("C15", featuredOnly, null,
                "추천(featured)만 켜짐. 추천 섹션에는 오르지만 검수 배지는 없어야 한다"));

        Long unverified = adminCompany(adminId, 16, "미인증", b -> b);
        refs.add(new SeedCompanyRef("C16", unverified, null,
                "관리자 검수(verified)·사업자 인증(businessVerified) 모두 없음. 배지가 하나도 붙지 않아야 한다"));

        Long featured = adminCompany(adminId, 17, "추천", b -> b);
        companies.applyFlags(featured, true, true, false, 0);
        refs.add(new SeedCompanyRef("C17", featured, null,
                "featured=true + verified=true. 추천 섹션과 에디터 선정 배지"));

        Long spotlight1 = adminCompany(adminId, 18, "스팟라이트1", b -> b);
        companies.applyFlags(spotlight1, true, false, true, 1);
        refs.add(new SeedCompanyRef("C18", spotlight1, null,
                "관리자 수동 고정(pin), 순서 1. 메인 스팟라이트 맨 앞에 와야 한다"));

        // 커버·브랜드 컬러를 일부러 비운다. 스팟라이트 카드는 커버를 배경으로, 브랜드 컬러를 배너 색으로 쓰는데
        // 둘 다 선택 필드라 없는 회사가 실제로 레일에 오른다 — 폴백(자리표시자 + 기본 배너 색)이 그려지는지
        // 개발 단계에서 눈으로 확인할 자리가 필요하다. C18 이 둘 다 갖춘 케이스라 나란히 비교된다.
        Long spotlight2 = adminCompany(adminId, 19, "스팟라이트2", SeedCompanyRequestBuilder::withoutCoverStyling);
        companies.applyFlags(spotlight2, true, false, true, 2);
        refs.add(new SeedCompanyRef("C19", spotlight2, null,
                "관리자 수동 고정(pin), 순서 2. C18 다음에 와야 한다."
                        + " 커버 이미지·브랜드 컬러가 없어 스팟라이트 카드가 폴백으로 그려져야 한다"));

        // ===== 렌더링 경계 =====

        Long empty = adminCompany(adminId, 20, "빈컬렉션",
                b -> b.withoutCollections().withoutOptionalFields());
        refs.add(new SeedCompanyRef("C20", empty, null,
                "연락처·레퍼런스·이미지·소재·장비·태그 전부 0건 + 선택 필드 전부 null."
                        + " 로고와 필수값만 있는 최소 회사 (빈 섹션 렌더링)"));

        Long maxed = adminCompany(adminId, 21, "최대치",
                b -> b.categoryIds(masters.categoryIds(SeedIndexes.forCase(21), 10))
                        .certificationIds(masters.certificationIds(SeedIndexes.forCase(21), 6))
                        // 마스터 목록에 없는 인증을 직접 적어 넣은 링크. 같은 '기타' 마스터에 이름만 달리해 2건이 붙는다
                        // — 배지가 마스터 이름("기타")이 아니라 입력한 이름으로 뜨는지 확인하는 자리다.
                        .customCertificationNames(masters.etcCertificationId(),
                                List.of("사내 표준 품질인증 QM-2024", "○○산업협회 우수기업 인증"))
                        .countryIds(masters.countryIds(SeedIndexes.forCase(21), 8))
                        .industryIds(masters.industryIds(SeedIndexes.forCase(21), 4))
                        .domesticRegionIds(masters.regionIds(SeedIndexes.forCase(21), 5))
                        .detailImageCount(30)
                        .referenceImageCount(10));
        refs.add(new SeedCompanyRef("C21", maxed, null,
                "카테고리·상세이미지·레퍼런스 이미지를 입력 상한까지 채운 회사(카테고리 10, 인증 6 + 직접입력 2,"
                        + " 상세이미지 30, 레퍼런스 이미지 10, 지역 5, 국가 8). 배지·갤러리 오버플로 처리 확인"));

        Long longText = adminCompany(adminId, 22, "긴이름",
                b -> b.companyName("[C22] " + "가나다라마바사아자차카타파하".repeat(3) + "정밀기계공업주식회사")
                        .introTitle("한 줄 요약이 한 줄에 담기지 않을 만큼 길어졌을 때 카드와 상세에서 어떻게 잘리는지 확인하기 위한 문장입니다")
                        .content(longContent()));
        refs.add(new SeedCompanyRef("C22", longText, null,
                "회사명·한줄요약·소개글이 모두 과도하게 긴 회사. 말줄임·줄바꿈·레이아웃 붕괴 확인"));

        // ===== 검색·패싯 =====

        Category leaf = masters.leafCategory(SeedIndexes.forCase(23));
        Category root = masters.rootAncestorOf(leaf);
        Long leafOnly = adminCompany(adminId, 23, "소분류전용",
                b -> b.categoryIds(List.of(leaf.getId())));
        refs.add(new SeedCompanyRef("C23", leafOnly, null,
                "소분류 '" + leaf.getCategoryName() + "' 하나에만 연결. 대분류 '" + root.getCategoryName()
                        + "'(id=" + root.getId() + ")로 필터해도 closure 를 타고 잡혀야 한다"));

        Long equipmentOnly = adminCompany(adminId, 24, "장비명검색",
                b -> b.equipmentNames(List.of(EQUIPMENT_ONLY_TOKEN, "CNC 선반")));
        refs.add(new SeedCompanyRef("C24", equipmentOnly, null,
                "'" + EQUIPMENT_ONLY_TOKEN + "' 토큰이 장비명에만 있고 회사명·소개글에는 없다."
                        + " 이 키워드로 검색해 잡히면 자식 텍스트(equipment_text)까지 색인된 것이다"));

        // ===== 비활성 항목(active=false) =====
        // 저장은 살아 있지만 공개에서 빠진 항목이 있는 회사. 프로덕션 경로에는 항목을 끄는 주체가 없어
        // (등급 재조정이 폐지됐다) 이 케이스가 없으면 조회·카드·색인에 넣은 active 필터가 한 번도 발동하지 않는다.
        // active 플래그 자체를 걷어낼 때 이 케이스도 함께 정리한다.
        Long hiddenItems = adminCompany(adminId, 25, "비활성항목",
                b -> b.categoryIds(masters.categoryIds(SeedIndexes.forCase(25), 5))
                        .detailImageCount(6));
        // 카테고리 1건, 상세이미지 3장만 남기고 나머지는 끈다.
        companies.deactivateOverflow(hiddenItems, 1, 3);
        refs.add(new SeedCompanyRef("C25", hiddenItems, null,
                "카테고리 5건 중 1건, 상세이미지 6장 중 3장만 활성. 공개 상세·카드·패싯 검색에서는 꺼진 항목이"
                        + " 아예 빠지고, 관리자/소유자 상세에서는 전부 내려오되 active=false 로 구분돼야 한다"));

        log.info("[seed] 계약 케이스 회사 {}건 생성", refs.size());
        return refs;
    }

    /** 통합검색이 자식 테이블까지 훑는지 확인하는 고유 토큰. 다른 어떤 필드에도 등장하지 않아야 한다. */
    static final String EQUIPMENT_ONLY_TOKEN = "젠틀리테스트가공기";

    /**
     * 메인 스팟라이트 자리 수. {@code app.showcase.spotlight-slots} 와 같은 값이어야 C08~C12 설명이 맞는다 —
     * 설명 문구에만 쓰므로 설정을 주입받지 않고 상수로 둔다(설정이 바뀌면 케이스 의도도 다시 봐야 한다).
     */
    private static final int SPOTLIGHT_SLOTS = 5;

    // ===== 생성 헬퍼 =====

    private SeedCompanyRef userCase(SeedAccount owner, int caseNo, String label, String proves,
                                    UnaryOperator<SeedCompanyRequestBuilder> customize) {
        Long companyId = userCompany(owner, caseNo, label, customize);
        return new SeedCompanyRef(code("C", caseNo), companyId, owner.code(), proves);
    }

    /**
     * 자가등록. 선행 인증을 먼저 발급하고 그것을 소비해 만든다 — 인증 소비, 초안 삭제, OWNER 멤버십,
     * businessVerified 가 전부 실제 등록 트랜잭션 안에서 일어난다.
     */
    private Long userCompany(SeedAccount owner, int caseNo, String label,
                             UnaryOperator<SeedCompanyRequestBuilder> customize) {
        int index = SeedIndexes.forCase(caseNo);
        Long verificationId = verifications.issue(
                owner.userId(),
                SeedBusinessNumbers.caseNumber(caseNo),
                SeedVocabulary.ceoName(index),
                SeedVocabulary.establishmentDate(index));

        SeedCompanyRequestBuilder builder = SeedCompanyRequestBuilder.of(index, masters)
                .companyName(named(caseNo, label, index))
                .filled(masters);

        return companies.createByUser(owner.userId(), customize.apply(builder).buildMy(verificationId));
    }

    private SeedCompanyRef adminCase(Long adminId, int caseNo, String label, String proves,
                                     UnaryOperator<SeedCompanyRequestBuilder> customize) {
        Long companyId = adminCompany(adminId, caseNo, label, customize);
        return new SeedCompanyRef(code("C", caseNo), companyId, null, proves);
    }

    /** 관리자등록. 한도는 자가등록과 같다(CompanyConstraints) — 케이스 회사는 둘 다 '잘 채운 모양'에서 시작한다. */
    private Long adminCompany(Long adminId, int caseNo, String label,
                              UnaryOperator<SeedCompanyRequestBuilder> customize) {
        int index = SeedIndexes.forCase(caseNo);
        SeedCompanyRequestBuilder builder = SeedCompanyRequestBuilder.of(index, masters)
                .businessNumber(SeedBusinessNumbers.caseNumber(caseNo))
                .companyName(named(caseNo, label, index))
                .filled(masters);

        return companies.createByAdmin(adminId, customize.apply(builder).build());
    }

    /** 케이스 코드를 회사명 앞에 박아 목록에서 바로 식별되게 한다. 패딩 회사에는 붙이지 않는다. */
    private String named(int caseNo, String label, int index) {
        return "[" + code("C", caseNo) + "] " + SeedVocabulary.companyName(index) + label;
    }

    private String code(String prefix, int number) {
        return prefix + String.format("%02d", number);
    }

    private String longContent() {
        return ("정밀 가공 분야에서 축적한 경험을 바탕으로 시제품 제작부터 양산까지 전 공정을 대응합니다. "
                + "도면 검토 단계에서 가공성 개선안을 함께 제안하며, 초도 물량은 전수 검사 후 납품합니다. ")
                .repeat(12);
    }
}
