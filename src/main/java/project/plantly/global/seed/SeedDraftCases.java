package project.plantly.global.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 인증·임시저장 케이스 D01~D07.
 *
 * <p>"완전 저장(발행) / 임시 저장(초안)" 축을 담당한다. 초안은 회사가 아니라 <b>선행 인증에 1:1로 매달린
 * JSON 문서</b>라서 회사 케이스와 매트릭스가 분리된다 — 아직 회사가 없으니 컬렉션 테이블에 행을 넣을 수
 * 없고, 그래서 폼 상태 전체가 payload 한 덩어리로 들어간다.
 *
 * <p>초안은 전부 U1 에 몰아둔다. 한 계정으로 로그인해 초안 상태 전부를 훑을 수 있는 편이 프론트 작업에
 * 유리하고, 현재 초안 조회는 {@code verificationId} 단건이라 "한 유저의 초안 목록" 같은 화면이 없어서
 * 여러 건을 들고 있어도 부자연스러운 곳이 없다.
 *
 * <p>payload 는 {@link MyCompanyCreateRequest} 를 그대로 직렬화한다. 프론트는 이 문서를 받아 폼을
 * 복원하므로 형태가 발행 요청과 정확히 같아야 하고, 손으로 JSON 을 쓰면 DTO 가 바뀔 때 조용히 어긋난다.
 */
@Slf4j
@Component
@Profile(SeedConfig.PROFILE)
@RequiredArgsConstructor
public class SeedDraftCases {

    private final SeedVerificationFactory verifications;
    private final SeedCompanyFactory companies;
    private final SeedMasterCatalog masters;

    public Result create(SeedAccounts accounts) {
        List<SeedDraftRef> refs = new ArrayList<>();
        List<SeedCompanyRef> publishedCompanies = new ArrayList<>();
        SeedAccount owner = accounts.owner1();

        // D01 — 인증만 받고 아직 아무것도 안 쓴 상태. 프론트가 '새 작성 시작'으로 진입하는 지점.
        Long onlyVerified = issue(owner, 1);
        refs.add(new SeedDraftRef("D01", onlyVerified, owner.code(),
                "유효한 인증만 있고 초안 없음. 초안 조회 시 '없음' 응답이 나오고 새 작성으로 진입해야 한다"));

        // D02 — 저장 버튼만 한 번 눌린 상태. 초안은 검증이 없으므로 거의 빈 문서도 그대로 저장된다.
        Long emptyDraft = issue(owner, 2);
        verifications.saveDraft(emptyDraft, owner.userId(), emptyPayload(emptyDraft));
        refs.add(new SeedDraftRef("D02", emptyDraft, owner.code(),
                "거의 빈 초안. 필수값 없이도 임시저장이 되고, 복원 시 빈 폼이 그려져야 한다"));

        // D03 — 기본 정보만 쓰고 컬렉션은 손대지 않은 중간 상태.
        Long partialDraft = issue(owner, 3);
        verifications.saveDraft(partialDraft, owner.userId(), partialPayload(partialDraft, SeedIndexes.forDraft(3)));
        refs.add(new SeedDraftRef("D03", partialDraft, owner.code(),
                "기본 정보만 채운 초안(컬렉션 전부 없음). 이어쓰기 복원 시 채운 값만 살아 있어야 한다"));

        // D04 — 이대로 발행 버튼을 누르면 회사가 만들어지는 완성 초안. 프론트가 발행 플로우를 실제로 돌려볼 대상.
        Long publishableDraft = issue(owner, 4);
        verifications.saveDraft(publishableDraft, owner.userId(),
                fullPayload(publishableDraft, SeedIndexes.forDraft(4)));
        refs.add(new SeedDraftRef("D04", publishableDraft, owner.code(),
                "발행 가능한 완전 초안. POST /api/v1/companies 로 그대로 보내면 회사가 생기고 초안은 삭제되어야 한다"));

        // D05 — 초안은 남았고 인증만 만료된 상태. 만료가 곧 실패는 아니다: 발행하면 서버가 저장된 값 그대로
        //       국세청에 다시 물어(자동 재질의) 통과하면 조용히 되살리고 그대로 등록한다. 사용자에게 다시
        //       받을 값이 없으므로 폼에는 아무것도 드러나지 않는 것이 정상이다.
        Long expiredVerification = verifications.issueExpired(
                owner.userId(),
                SeedBusinessNumbers.draftNumber(5),
                SeedVocabulary.ceoName(SeedIndexes.forDraft(5)),
                SeedVocabulary.establishmentDate(SeedIndexes.forDraft(5)));
        verifications.saveDraft(expiredVerification, owner.userId(),
                fullPayload(expiredVerification, SeedIndexes.forDraft(5)));
        refs.add(new SeedDraftRef("D05", expiredVerification, owner.code(),
                "인증이 만료된 초안(국세청은 여전히 통과). 발행하면 자동 재질의로 되살아나 그대로 등록되어야 한다 "
                        + "— 사용자에게 만료가 드러나면 안 된다"));

        // D05B — 같은 만료 상태지만 국세청이 실제로 다른 답을 주는 경우(폐업). 자동 재질의가 있어도 여기서는
        //        되살릴 수 없고, 이때만 사용자가 만료를 만난다. 프론트가 이 갈래의 안내를 그려볼 대상이다.
        //        번호 끝 4자리로 FakeNtsClient 의 폐업 분기를 태운다.
        Long closedVerification = verifications.issueExpired(
                owner.userId(),
                SeedBusinessNumbers.NTS_CLOSED,
                SeedVocabulary.ceoName(SeedIndexes.forDraft(5)),
                SeedVocabulary.establishmentDate(SeedIndexes.forDraft(5)));
        verifications.saveDraft(closedVerification, owner.userId(),
                fullPayload(closedVerification, SeedIndexes.forDraft(5)));
        refs.add(new SeedDraftRef("D05B", closedVerification, owner.code(),
                "만료 + 국세청이 폐업으로 답하는 초안. 발행하면 VERIFICATION_CLOSED 로 막히고 인증은 EXPIRED 로 "
                        + "확정된다. 작성분은 사업자번호로 키잉돼 있어 그대로 남아야 한다"));

        // D06 — 발행까지 끝낸 상태. 인증은 CONSUMED 가 되고 초안은 사라지며 회사가 남는다.
        //       이 세 가지가 한 트랜잭션에서 함께 일어나는지를 실제 경로로 확인하는 케이스다.
        int publishIndex = SeedIndexes.forDraft(6);
        Long consumedVerification = issue(owner, 6);
        verifications.saveDraft(consumedVerification, owner.userId(), fullPayload(consumedVerification, publishIndex));
        Long publishedCompanyId = companies.createByUser(owner.userId(),
                publishableBuilder(publishIndex)
                        .companyName("[D06] " + SeedVocabulary.companyName(publishIndex) + "발행완료")
                        .buildMy(consumedVerification));
        refs.add(new SeedDraftRef("D06", consumedVerification, owner.code(),
                "초안을 발행해 소비된 인증. 상태가 CONSUMED 이고 초안은 삭제됐으며 회사 id="
                        + publishedCompanyId + " 가 생겼어야 한다"));
        publishedCompanies.add(new SeedCompanyRef("D06", publishedCompanyId, owner.code(),
                "임시저장 → 발행 경로로 만들어진 회사. 초안 삭제와 인증 소비가 함께 일어났는지 확인"));

        // D07 — 인증 실패 감사 로그. 유일하게 FakeNtsClient 를 실제로 태우는 경로다.
        //       U3(회사 0건 계정)로 3회만 소비해, 하루 5회 한도 중 2회를 프론트가 직접 눌러볼 몫으로 남긴다.
        SeedAccount failer = accounts.empty();
        failed(failer, SeedBusinessNumbers.NTS_MISMATCH, 71);
        failed(failer, SeedBusinessNumbers.NTS_CLOSED, 72);
        failed(failer, SeedBusinessNumbers.NTS_NOT_REGISTERED, 73);
        refs.add(new SeedDraftRef("D07", null, failer.code(),
                "인증 실패 감사 로그 3건(불일치/폐업/미등록). " + failer.email()
                        + " 로 로그인하면 하루 5회 한도 중 2회가 남아 있어 실패 화면을 직접 재현할 수 있다"));

        log.info("[seed] 인증·초안 케이스 {}건 생성", refs.size());
        return new Result(refs, publishedCompanies);
    }

    /** 초안 케이스가 만든 회사는 회사 매니페스트에도 실려야 하므로 함께 돌려준다. */
    public record Result(List<SeedDraftRef> drafts, List<SeedCompanyRef> companies) {
    }

    private Long issue(SeedAccount owner, int sequence) {
        int index = SeedIndexes.forDraft(sequence);
        return verifications.issue(
                owner.userId(),
                SeedBusinessNumbers.draftNumber(sequence),
                SeedVocabulary.ceoName(index),
                SeedVocabulary.establishmentDate(index));
    }

    private void failed(SeedAccount account, String businessNumber, int index) {
        verifications.recordFailedAttempt(
                account.userId(), businessNumber,
                SeedVocabulary.ceoName(index), SeedVocabulary.establishmentDate(index));
    }

    /** 발행 가능한 초안. 최소 모양 그대로라 입력 상한(CompanyConstraints) 안에 있어 실제로 발행된다. */
    private SeedCompanyRequestBuilder publishableBuilder(int index) {
        return SeedCompanyRequestBuilder.of(index, masters);
    }

    private MyCompanyCreateRequest fullPayload(Long verificationId, int index) {
        return publishableBuilder(index)
                .companyName(SeedVocabulary.companyName(index) + "정공")
                .buildMy(verificationId);
    }

    /**
     * 막 시작한 초안. 초안 저장은 검증을 하지 않으므로({@code CompanyDraftService}) 필수값이 전부 비어 있어도
     * 그대로 보관된다 — 이 상태가 실제로 저장되고 복원되는지가 이 케이스의 요점이다.
     */
    private MyCompanyCreateRequest emptyPayload(Long verificationId) {
        return new MyCompanyCreateRequest(
                verificationId, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null);
    }

    /** 기본 정보만 채우고 컬렉션은 아직 손대지 않은 중간 상태. */
    private MyCompanyCreateRequest partialPayload(Long verificationId, int index) {
        return new MyCompanyCreateRequest(
                verificationId,
                SeedVocabulary.companyName(index) + "산업",
                SeedVocabulary.postalCode(index),
                SeedVocabulary.roadAddress(masters.addressRegionName(masters.region(index), index), index),
                null,
                SeedVocabulary.detailAddress(index),
                null,
                SeedVocabulary.logoUrl(index),
                // 커버는 아직 안 올린 중간 상태 — 로고만 있고 커버가 없는 초안이 복원되는지 확인한다.
                null,
                SeedVocabulary.introTitle(index),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
