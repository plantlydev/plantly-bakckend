package project.plantly.global.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import project.plantly.domain.company.nts.FakeNtsClient;
import project.plantly.domain.company.nts.NtsClient;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.search.CompanySearchDocumentWriter;
import project.plantly.domain.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 프론트 개발용 fake data 시드 진입점.
 *
 * <p>목적은 화면을 채우는 것이 아니라 <b>프론트-백엔드 계약·페이지네이션·권한</b>을 실제 응답으로 확인하는
 * 것이다. 그래서 데이터의 단위가 "행 수"가 아니라 "케이스"다 — 공개/비공개, 관리자등록/자가등록,
 * 발행/임시저장, 검수·추천·스팟라이트 고정, 삭제·미인증까지 각 행이 무엇을 증명하는지 이름을 갖는다.
 *
 * <p>회사 생성은 항상 {@code CompanyService} 의 실제 등록 경로를 탄다. 인증 소비·초안 삭제·소유자
 * 멤버십·검색 도큐먼트 동기화가 실서비스와 동일하게 일어나야, 여기서 만든 데이터로 확인한 화면이
 * 실제 동작과 어긋나지 않는다.
 *
 * <p>실행: {@code --spring.profiles.active=local,seed [--app.seed.reset=true]}
 */
@Slf4j
@Component
@Profile(SeedConfig.PROFILE)
@Order(FakeDataSeedRunner.ORDER)
@RequiredArgsConstructor
public class FakeDataSeedRunner implements ApplicationRunner {

    /** 마스터 시드·부분 유니크 인덱스 러너가 끝난 뒤에 실행되어야 한다. */
    static final int ORDER = 100;

    /**
     * U1 이 즐겨찾기할 계약 케이스 회사. 스팟라이트 고정·검수·추천 조합이 섞여 있어,
     * 즐겨찾기 목록에서도 카드 배지가 플래그별로 다르게 그려지는지 확인할 수 있다.
     * 모두 공개·미삭제·관리자 등록이라 "남의 회사를 찜한다"는 실제 흐름과도 어긋나지 않는다.
     * (C13 은 비공개라 뺀다 — 찜 목록에서도 가려져, 넣으면 저장 건수와 화면 건수가 어긋난다.)
     */
    private static final Set<String> FAVORITE_CASE_CODES = Set.of(
            "C08", "C09", "C10", "C11", "C12", "C14", "C15", "C16", "C17", "C18", "C19");

    private final NtsClient ntsClient;
    private final SeedProperties properties;
    private final SeedResetter resetter;
    private final SeedState state;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    private final SeedMasterCatalog masters;
    private final SeedAccountFactory accountFactory;
    private final SeedCompanyCases companyCases;
    private final SeedDraftCases draftCases;
    private final SeedPadding padding;
    private final SeedFavorites favorites;
    private final CompanySearchDocumentWriter searchDocumentWriter;
    private final SeedManifestWriter manifestWriter;

    @Override
    public void run(ApplicationArguments args) {
        guardFakeNtsClient();
        state.ensureTable();

        if (properties.reset()) {
            resetter.reset();
        } else if (state.completed()) {
            log.info("[seed] 이미 시드된 DB 입니다. 다시 심으려면 --app.seed.reset=true 로 실행하세요.");
            return;
        } else {
            requireNoLeftovers();
        }

        long startedAt = System.currentTimeMillis();
        log.info("[seed] 시드를 시작합니다.");

        masters.load();
        SeedAccounts accounts = accountFactory.create();

        List<SeedCompanyRef> companies = new ArrayList<>(companyCases.create(accounts));
        SeedDraftCases.Result draftResult = draftCases.create(accounts);
        companies.addAll(draftResult.companies());

        SeedPadding.Result paddingResult = padding.create(accounts);
        companies.addAll(paddingResult.all());

        favorites.create(accounts.owner1(), favoriteTargets(companies, paddingResult));

        // 소프트 삭제·운영 플래그는 등록 이후에 엔티티를 직접 바꾼 것이라 검색 도큐먼트에 반영돼
        // 있지 않다. 전체 재색인으로 한 번에 맞춘다. (backfillAll 은 지금까지 호출부가 없던 메서드다)
        searchDocumentWriter.backfillAll();

        manifestWriter.write(accounts, companies, draftResult.drafts());

        // 여기까지 와야 완료다. 앞 단계에서 예외가 나면 표식이 남지 않고, 다음 기동은 아래 requireNoLeftovers()
        // 에서 멈춘다 — 반쪽짜리 DB 가 "이미 시드됨"으로 통과하지 않게 하는 지점.
        state.markCompleted();

        log.info("[seed] 완료 — 계정 {}, 회사 {}, 인증/초안 케이스 {} ({}ms)",
                accounts.all().size(), companies.size(), draftResult.drafts().size(),
                System.currentTimeMillis() - startedAt);
    }

    /** 관리자 등록 패딩 전부 + 플래그 케이스. 합쳐서 즐겨찾기 목록이 두 페이지 이상 나온다. */
    private List<Long> favoriteTargets(List<SeedCompanyRef> companies, SeedPadding.Result paddingResult) {
        List<Long> targets = new ArrayList<>(paddingResult.adminRegistered().stream()
                .map(SeedCompanyRef::companyId)
                .toList());
        companies.stream()
                .filter(ref -> FAVORITE_CASE_CODES.contains(ref.code()))
                .map(SeedCompanyRef::companyId)
                .forEach(targets::add);
        return targets;
    }

    /**
     * 실제 국세청 호출 차단.
     *
     * <p>base {@code application.properties} 의 기본값이 {@code app.nts.fake=${NTS_FAKE:false}} 라,
     * {@code local} 없이 {@code seed} 만 활성화하고 셸에 {@code NTS_SERVICE_KEY} 가 남아 있으면 실제
     * 클라이언트가 뜬다. 시드는 인증을 여러 건 발급하므로 실수 한 번의 비용이 크다 — 호출이 나가기 전에
     * 여기서 멈춘다.
     */
    private void guardFakeNtsClient() {
        if (!(ntsClient instanceof FakeNtsClient)) {
            throw new IllegalStateException(
                    "시드는 가짜 국세청 클라이언트에서만 실행할 수 있습니다. 실제 국세청 API 로 요청이 나가는 것을 막기 위해 중단합니다. "
                            + "app.nts.fake=true 인지, local 프로파일이 함께 활성화됐는지 확인하세요 "
                            + "(--spring.profiles.active=local,seed).");
        }
    }

    /**
     * 지난 시드가 중간에 깨진 상태인지 확인한다.
     *
     * <p>완료 표식이 없는데 시드 흔적이 남아 있다면 앞선 실행이 도중에 끊긴 것이다. 그대로 다시 심으면
     * 계정 이메일이나 사업자번호 유니크 제약에 걸려, 원인이 한참 뒤의 스택트레이스로만 드러난다.
     * 시드 계정은 회사보다 먼저 만들어지므로 U1 이메일이 남아 있는지가 가장 이른 흔적이고, 계정 생성
     * 자체가 롤백된 뒤 회사만 남는 경우는 없지만 판정은 마커 회사까지 함께 본다.
     */
    private void requireNoLeftovers() {
        boolean leftover = userRepository.existsByEmail(SeedAccountFactory.OWNER1_EMAIL)
                || companyRepository.existsByBusinessNumberAndDeletedFalse(SeedBusinessNumbers.MARKER);
        if (leftover) {
            throw new IllegalStateException(
                    "지난 시드가 완료되지 못한 채 데이터가 남아 있습니다(완료 표식 없음). 남은 데이터 위에 다시 심으면"
                            + " 이메일·사업자번호 중복으로 실패하므로 중단합니다. --app.seed.reset=true 로 다시 실행하세요.");
        }
    }
}
