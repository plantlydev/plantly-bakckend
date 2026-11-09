package project.plantly.domain.company.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.dto.CompanyDraftResponse;
import project.plantly.domain.company.dto.CompanyDraftSummary;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.domain.company.dto.CompanyVerificationResponse;
import project.plantly.domain.company.entity.CompanyDraft;
import project.plantly.domain.company.enums.VerificationStatus;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyDraftRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;
import project.plantly.domain.company.support.BusinessNumbers;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 회사 자가등록 임시저장(초안) 서비스.
 *
 * <p>초안은 <b>사업자번호</b>로 키잉된다(인증 레코드가 아니라). 이유는 {@link CompanyDraft} 주석 참고 —
 * 인증은 만료·재발급되며 식별자가 바뀌지만 사용자가 쓴 글은 그럴 이유가 없기 때문이다.
 *
 * <p>대신 접근 권한은 여전히 인증이 준다: 매 요청마다 "이 사용자가 이 번호로 국세청을 통과한 적이 있는가"
 * 를 확인한다. 통과 이력이 없으면 아무 사업자번호로나 초안을 만들 수 없고, 남의 번호로 남의 초안을
 * 건드릴 수도 없다(조회 자체가 userId 로 스코프된다).
 *
 * <p>저장은 <b>검증하지 않는다</b> — 작성 중 부분 입력을 그대로 담아야 하기 때문이며, 필수값·마스터 검증은
 * 발행({@code POST /api/v1/companies})에서만 한다.
 */
@Service
@RequiredArgsConstructor
public class CompanyDraftService {

    private final CompanyDraftRepository draftRepository;
    private final CompanyVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // 발행에 다시 쓸 수 있는 인증 상태. 이유는 findResumableVerification 주석 참고.
    private static final Set<VerificationStatus> RESUMABLE = EnumSet.of(VerificationStatus.VERIFIED, VerificationStatus.EXPIRED);

    /**
     * 자동저장(upsert). 초안은 사용자·사업자번호당 1개라 그 짝으로 찾아 있으면 교체, 없으면 새로 만든다.
     * 이 번호로 만든 회사가 살아 있으면 초안이 아니라 수정 대상 — 저장을 거절한다. 삭제된 회사의 번호는
     * 재등록할 수 있으므로 막지 않는다(판정 근거는 {@link CompanyVerificationRepository#existsConsumedByLiveCompany}).
     *
     * <p><b>동시성:</b> "찾아서 없으면 만든다" 는 확인 후 실행이라, 첫 저장이 겹치면(자동저장과 수동저장) 둘 다 "없음" 을 보고
     * INSERT 해 (user, 번호) 유니크 위반 500 이 난다(리뷰 ⑥). 그래서 사용자 행을 잠가 같은 사용자의 저장을 줄 세운다 —
     * 뒤 요청은 앞 요청이 만든 초안을 보고 갱신 경로를 탄다(나중 저장이 이긴다). Postgres 의 {@code ON CONFLICT} upsert 를
     * 쓰지 않은 이유는 H2 위에서 도는 인수 테스트가 {@code DO UPDATE} 를 지원하지 않기 때문이다. 잠금 범위가 사용자 전체지만
     * 경합하는 것은 그 사용자 자신의 요청뿐이다.
     */
    @Transactional
    public void save(Long userId, String rawBusinessNumber, MyCompanyCreateRequest payload) {
        // 인증된 요청의 사용자가 없다면 정합성이 깨진 것이다. 락 없이 넘어가면 직렬화가 소리 없이 풀리므로 막는다.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 사용자 행이 없습니다: userId=" + userId));
        String businessNumber = requireVerifiedBusinessNumber(userId, rawBusinessNumber);
        if (verificationRepository.existsConsumedByLiveCompany(userId, businessNumber)) {
            throw new BusinessException(CompanyErrorCode.VERIFICATION_ALREADY_USED);
        }

        String json = serialize(payload);
        draftRepository.findByUserIdAndBusinessNumber(userId, businessNumber)
                .ifPresentOrElse(
                        draft -> draft.updatePayload(json),
                        () -> draftRepository.save(CompanyDraft.create(userId, businessNumber, json)));
    }

    /**
     * 재진입 시 폼 복원용. 저장된 초안이 없으면 404.
     * 이어쓸 인증(식별자·신원 3종)을 함께 실어, 클라이언트가 재인증 없이 폼을 다시 그리게 한다.
     */
    @Transactional(readOnly = true)
    public CompanyDraftResponse get(Long userId, String rawBusinessNumber) {
        String businessNumber = requireVerifiedBusinessNumber(userId, rawBusinessNumber);
        CompanyDraft draft = draftRepository.findByUserIdAndBusinessNumber(userId, businessNumber)
                .orElseThrow(() -> new BusinessException(CompanyErrorCode.DRAFT_NOT_FOUND));
        return new CompanyDraftResponse(deserialize(draft.getPayload()), draft.getUpdatedAt(),
                findResumableVerification(userId, businessNumber));
    }

    /**
     * 내 초안 목록(최근 저장순). 사업자번호를 몰라도 이어쓸 초안을 고를 수 있게 한다 — 단건 조회는 번호를
     * 알아야 해서, 번호를 기억해 둔 기기(브라우저 저장소)에서만 이어쓰기가 성립했다.
     *
     * <p>인증 이력 검사를 하지 않는다. 조회가 userId 로 스코프되므로 남의 초안이 섞일 수 없고, 내 초안은
     * 저장 시점에 이미 인증 이력 검사를 통과했다.
     *
     * <p>페이징하지 않는다. 초안은 사업자번호당 1개이고 인증 시도에 일일 한도가 있으며, 발행하면 지워진다.
     */
    @Transactional(readOnly = true)
    public List<CompanyDraftSummary> list(Long userId) {
        return draftRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(draft -> new CompanyDraftSummary(
                        draft.getBusinessNumber(), readCompanyName(draft.getPayload()), draft.getUpdatedAt()))
                .toList();
    }

    /**
     * 발행에 쓸 수 있는 가장 최근 인증. 없으면 null.
     *
     * <p>VERIFIED 는 시간상 만료(expiresAt 경과)여도 포함한다 — 발행 직전 자동 재질의가 되살리므로 사용자에게는
     * 만료가 아니다({@link CompanyVerificationService#refreshIfExpired}). EXPIRED 도 같은 이유로 포함한다 — 재질의
     * 대상이고, 국세청 판정이 그사이 바뀌었으면(휴업 해제 등) 통과한다. 빠지는 것은 이미 회사에 쓰인 CONSUMED
     * 와 관리자가 회수한 REVOKED 뿐이다(둘 다 되살리는 경로가 없다).
     */
    private CompanyVerificationResponse findResumableVerification(Long userId, String businessNumber) {
        return verificationRepository
                .findFirstByUserIdAndBusinessNumberAndStatusInOrderByIdDesc(userId, businessNumber, RESUMABLE)
                .map(CompanyVerificationResponse::from)
                .orElse(null);
    }

    /** 수동 폐기. 발행 성공 시의 삭제는 {@code CompanyService} 가 담당한다. 초안이 없어도 멱등하게 성공한다. */
    @Transactional
    public void delete(Long userId, String rawBusinessNumber) {
        String businessNumber = requireVerifiedBusinessNumber(userId, rawBusinessNumber);
        draftRepository.deleteByUserIdAndBusinessNumber(userId, businessNumber);
    }

    /**
     * 정규화한 사업자번호를 돌려주되, 이 사용자가 그 번호로 국세청을 통과한 적이 없으면 거절한다.
     *
     * <p>정규화를 먼저 하는 이유는 저장 표기를 하나로 고정하기 위해서다 — 경로에 하이픈을 넣고 빼는 것만으로
     * 같은 사업자의 초안이 두 행으로 갈리면 안 된다. 형식이 아예 아닌 값은 조회하기 전에 400 으로 끊는다.
     */
    private String requireVerifiedBusinessNumber(Long userId, String rawBusinessNumber) {
        String businessNumber = BusinessNumbers.normalize(rawBusinessNumber);
        if (businessNumber == null) {
            throw new BusinessException(CompanyErrorCode.BUSINESS_NUMBER_INVALID_FORMAT);
        }
        if (!verificationRepository.existsByUserIdAndBusinessNumber(userId, businessNumber)) {
            throw new BusinessException(CompanyErrorCode.VERIFICATION_NOT_FOUND);
        }
        return businessNumber;
    }

    //Json 문자열 변환
    //ObjectMapper 로 객체를 Json문자열로 변환함(ObjectMapper.writeValueAsString())
    private String serialize(MyCompanyCreateRequest payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(CompanyErrorCode.DRAFT_SERIALIZATION_FAILED);
        }
    }

    // 목록용으로 회사명 한 칸만 꺼낸다. 폼 전체를 역직렬화하지 않는 이유: 목록 한 줄이 깨진 payload 때문에
    // 목록 전체를 실패시키면 안 된다 — 읽지 못하면 이름 없이(null) 내보내고, 그 초안을 실제로 열 때(단건 조회)
    // 오류를 알린다. 비었거나 공백뿐이면 아직 안 쓴 것으로 본다.
    private String readCompanyName(String json) {
        try {
            JsonNode name = objectMapper.readTree(json).path("companyName");
            return name.isTextual() && !name.asText().isBlank() ? name.asText().strip() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private MyCompanyCreateRequest deserialize(String json) {
        try {
            return objectMapper.readValue(json, MyCompanyCreateRequest.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(CompanyErrorCode.DRAFT_SERIALIZATION_FAILED);
        }
    }
}
