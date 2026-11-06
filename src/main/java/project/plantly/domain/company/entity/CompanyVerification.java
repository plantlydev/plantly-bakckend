package project.plantly.domain.company.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import project.plantly.domain.company.enums.VerificationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 국세청 진위확인을 통과한 사업자 정보. 회사 등록보다 <b>먼저</b> 생긴다.
 *
 * <p>회사 등록 폼 앞단에서 인증을 끝내고(아이디 중복 확인과 같은 자리) 그 결과를 이 레코드로 발급한 뒤,
 * 등록 요청은 사업자번호·대표자명·개업일자를 본문에 담지 않고 이 레코드의 id 만 참조한다. 그래서
 * "인증은 A 로 받고 저장은 B 로 하는" 조작이 구조적으로 불가능하다.
 *
 * <p>companyId 는 등록이 끝난 뒤에 채워진다(그 전엔 null). 즉 이 엔티티는 Company 와 1:1 이지만
 * 생성 순서가 반대라 Company 를 참조하지 않고 raw id 로 뒤늦게 연결한다.
 */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class CompanyVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 인증을 수행한 유저. 이 유저만 이 인증으로 회사를 등록할 수 있다(타인의 인증 도용 차단).
    @NotNull
    @Column(nullable = false)
    private Long userId;

    // 국세청 검증을 통과한 값들. 전부 정규화·검증된 상태로만 들어온다.
    @NotNull
    @Column(nullable = false)
    private String businessNumber;

    @NotNull
    @Column(nullable = false)
    private String ceoName;

    // 개업일자(사업자등록 기준). 법인 등기 설립일과 다를 수 있어 별도 개념으로 받는다.
    @NotNull
    @Column(nullable = false)
    private LocalDate businessStartDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status;

    // 등록 완료 시 연결되는 회사. 미사용 인증은 null.
    private Long companyId;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime verifiedAt;

    // 미사용 인증의 유효기간. 사용자가 인증만 받아두고 방치한 레코드가 영원히 유효하면
    // 그 사이 사업자 상태가 바뀌어도(폐업 등) 옛 판정으로 등록할 수 있게 된다.
    @NotNull
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private String revokedReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private CompanyVerification(Long userId, String businessNumber, String ceoName, LocalDate businessStartDate,
                                LocalDateTime verifiedAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.businessNumber = businessNumber;
        this.ceoName = ceoName;
        this.businessStartDate = businessStartDate;
        this.status = VerificationStatus.VERIFIED;
        this.verifiedAt = verifiedAt;
        this.expiresAt = expiresAt;
    }

    /** 국세청 판정을 통과한 직후 발급한다. 통과하지 못한 시도는 이 레코드를 만들지 않고 감사 로그에만 남는다. */
    public static CompanyVerification issue(Long userId, String businessNumber, String ceoName,
                                            LocalDate businessStartDate, LocalDateTime now, java.time.Duration ttl) {
        return new CompanyVerification(userId, businessNumber, ceoName, businessStartDate, now, now.plus(ttl));
    }

    /** 회사 등록에 사용한다. 한 번 쓰면 재사용 불가. */
    public void consume(Long companyId) {
        this.status = VerificationStatus.CONSUMED;
        this.companyId = companyId;
    }

    /**
     * 재인증. 이미 회사에 소비된(CONSUMED) 인증의 검증값을 국세청 재확인 결과로 갱신한다.
     *
     * <p>국세청 등록정보가 바뀌었을 수 있으므로 이전 값과 비교하지 않고 새 값으로 덮어쓰고, 인증 시각을
     * 갱신한다. status·companyId 는 그대로 둔다 — 여전히 이 회사에 연결된 유효한 인증이다.
     * (사업자번호는 바꾸지 않는다: 재인증은 저장된 번호로만 질의하는 것이 규칙이다.)
     */
    public void reverify(String ceoName, LocalDate businessStartDate, LocalDateTime verifiedAt) {
        this.ceoName = ceoName;
        this.businessStartDate = businessStartDate;
        this.verifiedAt = verifiedAt;
    }

    /** 관리자 인증 회수. 사유를 남겨 분쟁 대응 근거로 쓴다. */
    public void revoke(String reason, LocalDateTime now) {
        this.status = VerificationStatus.REVOKED;
        this.revokedAt = now;
        this.revokedReason = reason;
    }

    /**
     * 만료된 판정을 국세청 재질의 결과로 되살린다.
     *
     * <p>{@link #reverify}와 달리 검증값(대표자명·개업일자)을 바꾸지 않는다 — 저장된 값 그대로 다시 물어
     * 통과한 경우에만 불리므로 바꿀 것이 없다. 바뀌는 것은 판정의 신선도뿐이라 인증 시각과 유효기간만
     * 새로 찍고, EXPIRED 로 내려앉았던 상태도 VERIFIED 로 되돌린다.
     *
     * <p>CONSUMED/REVOKED 에는 부를 수 없다 — 이미 회사에 쓰였거나 관리자가 회수한 인증을 되살리는 경로가 되면
     * 안 된다. 호출부의 사전 확인({@link #needsRefresh})은 국세청을 기다리는 사이 낡을 수 있으므로(동시 발행),
     * 반영 경로는 락 아래에서 {@link #isAutoRefreshable} 을 다시 보고, 여기서도 한 번 더 막는다.
     */
    public void refresh(LocalDateTime verifiedAt, LocalDateTime expiresAt) {
        assertAutoRefreshable();
        this.status = VerificationStatus.VERIFIED;
        this.verifiedAt = verifiedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 자동 재질의로 되살릴 수 있는 상태인지. 두 경우를 함께 본다 —
     * 아직 VERIFIED 인데 기한만 지난 경우와, 이전 시도에서 이미 EXPIRED 로 찍힌 경우다.
     * 후자를 빼면 한 번 만료 판정을 받은 인증은 영영 되살아나지 못해 초안이 발행될 길이 사라진다.
     */
    public boolean needsRefresh(LocalDateTime now) {
        return isExpired(now) || status == VerificationStatus.EXPIRED;
    }

    /** 미사용 상태로 유효기간이 지났는지. 만료 판정은 읽는 시점에 하고, 상태 전이는 사용 시도 때 기록한다. */
    public boolean isExpired(LocalDateTime now) {
        return status == VerificationStatus.VERIFIED && now.isAfter(expiresAt);
    }

    /** 만료를 확정 기록한다. {@link #refresh} 와 같은 이유로 CONSUMED/REVOKED 에는 부를 수 없다. */
    public void markExpired() {
        assertAutoRefreshable();
        this.status = VerificationStatus.EXPIRED;
    }

    /**
     * 만료 처리(재질의 갱신·만료 확정)의 대상이 될 수 있는 상태인지 — 아직 회사에 쓰이지 않았고 회수되지도 않았다.
     * CONSUMED/REVOKED 는 종착 상태라 자동 재질의가 건드리면 안 된다.
     */
    public boolean isAutoRefreshable() {
        return status == VerificationStatus.VERIFIED || status == VerificationStatus.EXPIRED;
    }

    private void assertAutoRefreshable() {
        if (!isAutoRefreshable()) {
            throw new IllegalStateException("만료 처리 대상이 아닌 인증 상태: " + status);
        }
    }

    public boolean isUsable() {
        return status == VerificationStatus.VERIFIED;
    }
}
