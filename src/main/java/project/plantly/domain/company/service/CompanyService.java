package project.plantly.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.entity.Address;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanySubscription;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.global.exception.BusinessException;
import project.plantly.domain.company.policy.CompanyPolicyView;
import project.plantly.domain.company.policy.CompanyRegistrationPolicy;
import project.plantly.domain.company.policy.InitialSubscriptionPolicy;
import project.plantly.domain.company.repository.CompanyDraftRepository;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanySubscriptionRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;

    // 부속 엔티티 저장 위임. 자식(소유) / 링크(M:N) 각각 전담 컴포넌트가 소유한다.
    private final CompanyChildWriter childWriter;
    private final CompanyLinkWriter linkWriter;

    // 회사-유저 멤버십. 자가등록 시 등록자를 OWNER 로 1건 기록한다.
    private final CompanyMemberRepository companyMemberRepository;

    // 선행 인증 레코드. 자가등록이 소비해 신원 3종(사업자번호/대표자명/개업일자)의 출처가 된다.
    private final CompanyVerificationRepository verificationRepository;

    // 임시저장 초안. 발행이 성공하면 소임을 다한 초안을 같은 트랜잭션에서 제거한다.
    private final CompanyDraftRepository draftRepository;

    // 회사 구독. 등록 시 회사의 초기 구독(등급)을 1건 저장한다. 정책은 이 구독의 등급을 참조한다.
    private final CompanySubscriptionRepository companySubscriptionRepository;

    // 검색 동기화. 본체·자식·링크 저장 후 비정규화 검색 도큐먼트와 카테고리 closure 를 재생성한다.
    private final CompanySearchDocumentWriter searchDocumentWriter;

    // 등록 정책 모음. 정책 내용은 각 구현체가 소유하며, 서비스는 주입받은 정책들을 실행만 한다.
    // 새 정책은 CompanyRegistrationPolicy 구현 @Component 추가만으로 자동 합류한다.
    private final List<CompanyRegistrationPolicy> registrationPolicies;

    // 자가등록 회사의 초기 등급/구독 결정. 인증 발급 경로도 같은 정책을 읽어 폼에 한도를 안내하므로,
    // 여기서 등급을 직접 만들지 않고 정책에 물어본다(폼이 안내한 한도와 서버 검증이 어긋날 수 없게).
    private final InitialSubscriptionPolicy initialSubscriptionPolicy;

    // 유저 자가등록: 선행 인증을 소비해 신원 3종을 채우고, 등록 즉시 소유자 = 본인 + 사업자 인증 완료 상태가 된다.
    // 초기 구독(등급)은 InitialSubscriptionPolicy 가 인증을 보고 결정하며, 그 한도로 등록 정책이 적용된다.
    // 인증 발급 응답이 같은 정책으로 계산한 한도를 이미 폼에 내려주므로, 여기서 거부되는 건 폼을 우회한 요청뿐이다.
    //
    // 관리자 등록과 달리 이 경로만 인증을 요구하는 이유: 자가등록은 사용자가 레코드를 직접 작성하므로
    // 사업자번호와 회사명이 어긋날 수 없다. 반면 관리자가 대신 등록한 회사는 대조할 앵커가 없어
    // 국세청 통과만으로는 소유권을 증명하지 못한다(그 경로 = 클레임은 아직 열지 않았다).
    @Transactional
    public Long createByUser(Long userId, MyCompanyCreateRequest myRequest) {
        CompanyVerification verification = loadUsableVerification(userId, myRequest.verificationId());
        CompanyCreateRequest request = myRequest.toCreateRequest(verification);

        Company company = Company.createByUser(
                userId,
                request.businessNumber(), request.companyName(), request.ceoName(), request.establishmentDate(),
                Address.of(request.postalCode(), request.roadAddress(), request.jibunAddress(), request.detailAddress()),
                request.website(), request.logoUrl(), request.coverImageUrl(),
                request.introTitle(), request.content(), request.trlLevel(), request.videoUrl(), request.leadTime(), request.asInfo(), request.pricingType(), request.brandColor());
        company.markBusinessVerified(verification.getVerifiedAt());

        Long companyId = persist(company, request,
                initialSubscriptionPolicy.initialSubscription(verification, LocalDate.now()));

        // 인증을 소비 처리해 재사용을 막는다. 같은 인증으로 여러 회사를 만들 수 없다.
        verification.consume(companyId);

        // 발행 성공 → 임시저장 초안은 소임을 다했으므로 제거한다. 초안 없이 바로 등록했다면 아무 일도 안 한다(멱등).
        // 등록이 실패하면 이 트랜잭션이 롤백되어 초안도 그대로 남는다.
        // 삭제 키가 (userId, businessNumber) 인 것은 초안이 인증 레코드가 아니라 사업자번호로 키잉되기 때문이다
        // (CompanyDraft 주석 참고). 인증이 중간에 만료·재발급됐어도 발행에 쓴 번호의 초안이 정확히 지워진다.
        draftRepository.deleteByUserIdAndBusinessNumber(userId, verification.getBusinessNumber());

        // 자가등록자 = OWNER. 관리자 등록(createByAdmin)은 소유자 미연동이라 멤버를 만들지 않는다.
        // 소유의 단일 진실원(SSOT) — Company 는 소유자를 직접 참조하지 않고 이 멤버십으로만 표현한다.
        companyMemberRepository.save(CompanyMember.owner(companyId, userId));
        return companyId;
    }

    // 본인이 받은, 아직 쓰지 않은, 만료되지 않은 인증만 통과시킨다.
    // userId 를 조회 조건에 넣어 남의 인증 식별자를 주워 쓰는 경로를 막는다.
    private CompanyVerification loadUsableVerification(Long userId, Long verificationId) {
        CompanyVerification verification = verificationRepository.findByIdAndUserId(verificationId, userId)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.VERIFICATION_NOT_FOUND));

        if (verification.isExpired(LocalDateTime.now())) {
            // 만료를 읽는 시점에 확정 기록해 둔다. 이후 조회에서 상태만 보고 판단할 수 있다.
            verification.markExpired();
            throw new BusinessException(CompanyErrorCode.VERIFICATION_EXPIRED);
        }
        if (!verification.isUsable()) {
            throw new BusinessException(switch (verification.getStatus()) {
                case CONSUMED -> CompanyErrorCode.VERIFICATION_ALREADY_USED;
                case EXPIRED -> CompanyErrorCode.VERIFICATION_EXPIRED;
                default -> CompanyErrorCode.VERIFICATION_NOT_FOUND;
            });
        }
        return verification;
    }

    // 관리자 등록: 소유자 미연동(userId=null) 상태로 시작. registeredBy = 등록한 admin id.
    // 구독은 등급 한도 면제(ADMIN_EXEMPT)로 시작한다.
    @Transactional
    public Long createByAdmin(Long adminId, CompanyCreateRequest request) {
        Company company = Company.createByAdmin(
                adminId,
                request.businessNumber(), request.companyName(), request.ceoName(), request.establishmentDate(),
                Address.of(request.postalCode(), request.roadAddress(), request.jibunAddress(), request.detailAddress()),
                request.website(), request.logoUrl(), request.coverImageUrl(),
                request.introTitle(), request.content(), request.trlLevel(), request.videoUrl(), request.leadTime(), request.asInfo(), request.pricingType(), request.brandColor());

        return persist(company, request, CompanySubscription.adminExempt(LocalDate.now()));
    }

    // 공통 코어: 등록 정책 일괄 검증 후, 본체 INSERT(=id 확보) → 구독·부속(자식/링크)을 같은 트랜잭션으로 저장한다.
    // 정책은 아직 저장 전인 company 와 subscription(등급 출처)을 받아 검증/변형한다. throw 시 아무것도 영속화되지 않는다.
    private Long persist(Company company, CompanyCreateRequest request, CompanySubscription subscription) {
        // 등록 시 선택한 공개 범위 반영(미지정이면 엔티티 기본값 PUBLIC 유지). 시스템 플래그와 동일하게 도메인 행위로만 설정.
        if (request.visibility() != null) {
            company.changeVisibility(request.visibility());
        }
        // 활성 회사 중 동일 사업자번호 사전 검증. DB 부분 유니크 인덱스가 최종 방어선이지만, 여기서 먼저 걸러
        // raw DataIntegrityViolation 대신 친화적 에러를 준다(soft delete 된 번호는 활성이 아니라 통과 → 재사용 허용).
        if (company.getBusinessNumber() != null
                && companyRepository.existsByBusinessNumberAndDeletedFalse(company.getBusinessNumber())) {
            throw new BusinessException(CompanyErrorCode.BUSINESS_NUMBER_TAKEN);
        }
        CompanyPolicyView view = CompanyPolicyView.forCreate(company, request);
        registrationPolicies.forEach(policy -> policy.apply(view));

        // flush 로 INSERT 를 지금 터뜨려 활성 부분 유니크 인덱스 위반을 여기서 잡는다.
        // 위 사전 검사만으로는 동시성을 못 막는다 — 선행 인증 시점엔 아직 회사가 없어서 두 사용자가 같은
        // 사업자번호로 동시에 인증을 통과할 수 있고, 등록도 나란히 사전 검사를 통과한다. 인덱스가 최종
        // 방어선인데, 그대로 두면 커밋 시점에 raw DataIntegrityViolation 이 터져 500 이 된다.
        try {
            companyRepository.save(company);
            companyRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(CompanyErrorCode.BUSINESS_NUMBER_TAKEN);
        }
        // 본체 저장으로 확보한 id 로 구독을 회사에 연결(1:1)해 저장한다.
        subscription.assignCompany(company.getId());
        companySubscriptionRepository.save(subscription);

        childWriter.write(company, request);
        linkWriter.write(company, request);
        // 자식·링크가 모두 저장된 뒤라야 도큐먼트 집계와 카테고리 closure 가 온전하다.
        searchDocumentWriter.write(company.getId());
        return company.getId();
    }
}
