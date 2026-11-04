package project.plantly.domain.company.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.RegistrationSource;
import project.plantly.domain.company.enums.TrlLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 소유는 CompanyMember(role=OWNER) 가 단일 진실원(SSOT). 관리자 대신등록 회사는 미연동(멤버 0건)일 수 있다.
    // (소유 유저 식별자 userId 필드는 CompanyMember 로 이관 — Company 는 더 이상 소유자를 직접 참조하지 않는다)

    // 등록 경로(USER/ADMIN). 연동 정책 확정 전까지 누가 어떤 경로로 등록했는지 추적하는 provenance 용도.
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegistrationSource registrationSource;

    // 등록 행위자 식별자. 유저 자가등록이면 본인 userId, 관리자 등록이면 admin id. (raw id로 참조)
    private Long registeredBy;

    // 사업자번호. 초안 단계 회사는 미입력 가능하므로 nullable.
    // UNIQUE 는 컬럼 제약(삭제 행 포함 전체)이 아니라 활성(deleted=false) 행끼리만 강제하는 부분 유니크 인덱스로 건다.
    // (soft delete 된 회사의 사업자번호는 재사용 가능해야 하므로 전체 UNIQUE 를 걸지 않는다 — CompanyBusinessNumberIndexInitializer)
    @Column
    private String businessNumber;

    @NotNull
    @Column(nullable = false)
    private String companyName;

    @NotNull
    @Column(nullable = false)
    private String ceoName;

    private LocalDate establishmentDate;

    // 주소 4축(우편번호/도로명/지번/상세)을 값 객체로 묶는다. 컬럼은 postal_code/road_address/jibun_address/detail_address.
    @Embedded
    @NotNull
    private Address address;

    private String website;

    // 기업 대표 이미지(단일·선택). 여러 장 이미지는 CompanyImage 로 분리 관리한다.
    //
    // NOT NULL 이 아니다. 로고가 없는 회사도 등록할 수 있어야 하고, 없을 때는 화면이 회사명 앞 두 글자로
    // 대체 배지를 그린다(프론트 담당). 서버가 자리표시자 URL 을 만들어 넣지 않는 이유는, 그러면 "로고를
    // 올린 적 없음" 과 "자리표시자를 로고로 올림" 이 저장 값으로 구분되지 않기 때문이다.
    private String logoUrl;

    // 카드 커버 이미지(단일·선택). 로고와는 다른 축이다 — 로고는 정사각 배지, 커버는 카드 배경으로 깔리는 와이드 사진.
    // CompanyImage 가 아니라 스칼라로 두는 이유는 '회사당 최대 1장'을 정책이 아니라 구조로 보장하기 위해서다
    // (갤러리에 두면 최대 1장 정책 + 갤러리 전체교체 PUT 이 커버를 날리지 않게 하는 예외가 함께 필요해진다).
    // 등급 게이트는 없다 — 커버는 카드가 비어 보이지 않게 하는 기본 요소라 전 등급이 올릴 수 있다.
    private String coverImageUrl;

    private String introTitle;  // 한 줄 요약

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    private TrlLevel trlLevel;

    private String videoUrl;

    private String leadTime;

    @Column(columnDefinition = "TEXT")
    private String asInfo;

    @Enumerated(EnumType.STRING)
    private PricingType pricingType;

    private String brandColor;

    // 관리자 큐레이션 배지("에디터 선정"). 아래 businessVerified(국세청 확인)와는 별개의 축이다 —
    // 운영 주체도 의미도 다르므로 하나로 합치지 않는다. 합치면 관리자가 추천 배지를 켜다가
    // 사업자 인증 자격까지 부여하는 사고가 난다.
    @Column(nullable = false)
    private boolean verified = false;

    // 국세청 진위확인·상태조회를 통과한 사업자인지. 권위 있는 상태는 CompanyVerification 이 갖고,
    // 이 필드는 목록·검색·카드 등 읽기 경로가 매번 조인하지 않도록 둔 비정규화 사본이다.
    // (deleted/visibility 와 같은 취급 — 상태 전이는 도메인 행위 메서드로만 한다)
    @Column(nullable = false)
    private boolean businessVerified = false;

    private LocalDateTime businessVerifiedAt;

    @Column(nullable = false)
    private boolean featured = false;

    // 스팟라이트 '관리자 수동 고정(pin)' 여부. 켜면 메인 스팟라이트 레일 후보가 된다(삭제·비공개면 빠진다).
    // 노출 여부의 최종 판단은 ShowcaseCardRepository 한 곳에 있다.
    @Column(nullable = false)
    private boolean spotlight = false;

    // pin 된 회사들 사이의 노출 순서(작을수록 앞). pin 이 아닌 회사에는 의미가 없다.
    private int spotlightOrder;

    @Column(nullable = false)
    private boolean deleted = false;

    // 공개 범위(PUBLIC/PRIVATE). 기본 공개로 시작하고, 전환은 changeVisibility 로만 한다.
    // 등록 시 유저/관리자가 고른 값도 시스템 플래그와 동일하게 등록 서비스가 changeVisibility 로 반영한다.
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyVisibility visibility = CompanyVisibility.PUBLIC;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // 비즈니스 필드만 받는다. 시스템 관리 플래그(verified/featured/spotlight/spotlightOrder/deleted/visibility)는
    // 생성 시 기본값(false/0/PUBLIC)으로 시작하고, 상태 전환은 도메인 행위 메서드로만 수행한다.
    private Company(RegistrationSource registrationSource, Long registeredBy, String businessNumber, String companyName, String ceoName, LocalDate establishmentDate, Address address, String website, String logoUrl, String coverImageUrl, String introTitle, String content, TrlLevel trlLevel, String videoUrl, String leadTime, String asInfo, PricingType pricingType, String brandColor) {
        this.registrationSource = registrationSource;
        this.registeredBy = registeredBy;
        this.businessNumber = businessNumber;
        this.companyName = companyName;
        this.ceoName = ceoName;
        this.establishmentDate = establishmentDate;
        this.address = address;
        this.website = website;
        this.logoUrl = logoUrl;
        this.coverImageUrl = coverImageUrl;
        this.introTitle = introTitle;
        this.content = content;
        this.trlLevel = trlLevel;
        this.videoUrl = videoUrl;
        this.leadTime = leadTime;
        this.asInfo = asInfo;
        this.pricingType = pricingType;
        this.brandColor = brandColor;
    }

    // 유저 자가등록: registeredBy = 본인. 소유자 연동은 호출부가 CompanyMember(OWNER) 로 별도 기록한다.
    public static Company createByUser(Long userId, String businessNumber, String companyName, String ceoName, LocalDate establishmentDate, Address address, String website, String logoUrl, String coverImageUrl, String introTitle, String content, TrlLevel trlLevel, String videoUrl, String leadTime, String asInfo, PricingType pricingType, String brandColor) {
        return new Company(RegistrationSource.USER, userId, businessNumber, companyName, ceoName, establishmentDate, address, website, logoUrl, coverImageUrl, introTitle, content, trlLevel, videoUrl, leadTime, asInfo, pricingType, brandColor);
    }

    // 관리자 등록: 소유자 미연동(멤버 0건) 상태로 시작. registeredBy 는 등록한 admin id.
    public static Company createByAdmin(Long adminId, String businessNumber, String companyName, String ceoName, LocalDate establishmentDate, Address address, String website, String logoUrl, String coverImageUrl, String introTitle, String content, TrlLevel trlLevel,  String videoUrl, String leadTime, String asInfo, PricingType pricingType, String brandColor) {
        return new Company(RegistrationSource.ADMIN, adminId, businessNumber, companyName, ceoName, establishmentDate, address, website, logoUrl, coverImageUrl, introTitle, content, trlLevel, videoUrl, leadTime, asInfo, pricingType, brandColor);
    }

    // ===== 기본 정보 부분 수정 =====
    // null = 미변경. 선택(nullable) 문자열 필드는 blank("") 을 받으면 비운다(null 로 clear).
    // 필수 필드의 blank 는 요청 DTO(@Size(min=1)) 에서 거르므로 여기선 null 여부만 본다.
    // 날짜·enum 은 blank 개념이 없어 clear 를 지원하지 않는다(값이 오면 교체만).
    // 시스템 플래그·사업자번호·등록 provenance(registrationSource/registeredBy) 는 이 경로로 바꾸지 않는다.
    public void updateBasicInfo(String companyName, String ceoName, LocalDate establishmentDate,
                                String postalCode, String roadAddress, String jibunAddress, String detailAddress,
                                String website, String logoUrl, String coverImageUrl, String introTitle, String content,
                                TrlLevel trlLevel, String videoUrl, String leadTime, String asInfo,
                                PricingType pricingType, String brandColor) {
        // 필수 필드: null = 미변경 (blank 는 DTO 에서 차단)
        if (companyName != null) this.companyName = companyName;
        if (ceoName != null) this.ceoName = ceoName;
        // 주소 4축은 값 객체가 필드별 병합 규칙(필수 교체 / jibun blank=clear)을 소유한다. 넷 다 null 이면 미변경.
        if (postalCode != null || roadAddress != null || jibunAddress != null || detailAddress != null) {
            this.address = this.address.merged(postalCode, roadAddress, jibunAddress, detailAddress);
        }

        // 선택 문자열 필드: null = 미변경, blank = 비움(null)
        // 로고도 선택 필드가 되면서 이쪽으로 왔다 — 비우면 화면이 회사명 앞 두 글자로 대체 배지를 그린다.
        if (logoUrl != null) this.logoUrl = blankToNull(logoUrl);
        if (coverImageUrl != null) this.coverImageUrl = blankToNull(coverImageUrl);
        if (website != null) this.website = blankToNull(website);
        if (introTitle != null) this.introTitle = blankToNull(introTitle);
        if (content != null) this.content = blankToNull(content);
        if (videoUrl != null) this.videoUrl = blankToNull(videoUrl);
        if (leadTime != null) this.leadTime = blankToNull(leadTime);
        if (asInfo != null) this.asInfo = blankToNull(asInfo);
        if (brandColor != null) this.brandColor = blankToNull(brandColor);

        // clear 미지원(날짜·enum): null = 미변경, 값 = 교체
        if (establishmentDate != null) this.establishmentDate = establishmentDate;
        if (trlLevel != null) this.trlLevel = trlLevel;
        if (pricingType != null) this.pricingType = pricingType;
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }

    // ===== 상태 변경 (도메인 행위) =====

    // ===== 국세청 사업자 인증 =====
    // 자가등록은 선행 인증(CompanyVerification)을 소비하면서 등록되므로, 등록 직후 이 메서드로 표시한다.
    // 관리자 등록 회사는 인증을 거치지 않아 false 로 남는다.
    public void markBusinessVerified(LocalDateTime verifiedAt) {
        this.businessVerified = true;
        this.businessVerifiedAt = verifiedAt;
    }

    // 국세청 재인증 반영. 최초 인증 후 국세청 등록정보(대표자명·개업일자)가 바뀌었거나 인증 주기(1년)가 지난 경우,
    // 소유자가 국세청 재확인을 통과한 값으로 두 필드를 덮어쓰고 인증 시각을 새로 찍는다.
    // 사업자번호는 건드리지 않는다 — 재인증은 저장된 번호로만 질의하고, 그 번호는 최초 인증본에서 온 불변값이다.
    // updateBasicInfo 가 인증 회사에 대해 막는 대표자명·개업일자 변경을, '방금 국세청을 통과했다'는 근거로만
    // 바꾸는 유일한 통로다(검증값과 배지의 정합성이 이 경로 밖에서는 깨지지 않는다).
    public void applyBusinessReverification(String ceoName, LocalDate businessStartDate, LocalDateTime verifiedAt) {
        this.ceoName = ceoName;
        this.establishmentDate = businessStartDate;
        this.businessVerified = true;
        this.businessVerifiedAt = verifiedAt;
    }

    // 관리자 인증 회수(사칭 신고 등). 사업자번호는 지우지 않는다 — 활성 유니크로 같은 번호의 재등록을
    // 계속 막아야 하고, 어떤 번호로 인증받았었는지가 분쟁 기록으로 남아야 한다.
    public void revokeBusinessVerification() {
        this.businessVerified = false;
        this.businessVerifiedAt = null;
    }

    // 관리자 인증 처리 / 해제
    public void verify() {
        this.verified = true;
    }

    public void revokeVerification() {
        this.verified = false;
    }

    // 추천(featured) 노출 / 해제
    public void feature() {
        this.featured = true;
    }

    public void unfeature() {
        this.featured = false;
    }

    // 스팟라이트 수동 고정(pin). 고정된 회사들 사이의 노출 순서를 함께 지정한다.
    public void turnOnSpotlight(int spotlightOrder) {
        this.spotlight = true;
        this.spotlightOrder = spotlightOrder;
    }

    // 수동 고정 해제. 순서값도 초기화한다.
    public void turnOffSpotlight() {
        this.spotlight = false;
        this.spotlightOrder = 0;
    }

    // 고정 유지한 채 순서만 변경
    public void changeSpotlightOrder(int spotlightOrder) {
        this.spotlightOrder = spotlightOrder;
    }

    // 등급 정책에 따라 브랜드 컬러를 강제 지정한다. (커스텀 불가 등급의 기본값 고정 등)
    public void changeBrandColor(String brandColor) {
        this.brandColor = brandColor;
    }

    // ===== 관리자 운영 플래그 직접 지정 (set-to-state) =====
    // 등록 시 전부 false 로 시작하며, 관리자가 목표값(true/false)을 그대로 지정한다 — 토글 아님, 멱등.
    // verify()/feature()/turnOnSpotlight() 등 방향별 행위 메서드와 달리 불리언을 받아 켜고 끄는 통합 전환이다.
    // (spotlight 는 노출 순서 spotlightOrder 와 별개로 on/off 만 다룬다 — 순서 큐레이션은 별도 경로)
    public void changeVerified(boolean verified) {
        this.verified = verified;
    }

    public void changeFeatured(boolean featured) {
        this.featured = featured;
    }

    public void changeSpotlight(boolean spotlight) {
        this.spotlight = spotlight;
    }

    // 소프트 삭제 / 복구
    public void delete() {
        this.deleted = true;
    }

    public void restore() {
        this.deleted = false;
    }

    // 공개 / 비공개 전환. 등록 시점 선택값 반영과 이후 토글에 모두 쓰인다(목표 상태를 그대로 지정).
    public void changeVisibility(CompanyVisibility visibility) {
        this.visibility = visibility;
    }
}
