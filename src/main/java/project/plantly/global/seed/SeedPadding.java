package project.plantly.global.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 정족수 패딩.
 *
 * <p>페이징 엔드포인트 4개가 전부 {@code @PageableDefault(size = 20)} 이다. 그런데 페이지네이션은
 * <b>테이블이 아니라 뷰마다</b> 검증해야 한다 — 회사를 아무리 많이 넣어도 그게 전부 관리자 등록이면
 * {@code /companies/my} 는 여전히 한 페이지다. 그래서 소유자별 정족수를 따로 만든다.
 *
 * <ul>
 *   <li>P01~P12 — U1 자가등록. 계약 케이스의 U1 소유분과 합쳐 {@code /companies/my} 가
 *       {@code ?size=5} 에서 3페이지 이상 나온다.</li>
 *   <li>P13~P24 — 관리자 등록. 공개 목록을 기본 size=20 에서 3페이지까지 밀어 올린다.</li>
 * </ul>
 *
 * <p>케이스 회사와 달리 이름에 코드를 붙이지 않는다 — 목록 화면이 실제 서비스처럼 보여야 훑어보며
 * 어색한 지점을 발견할 수 있고, 코드가 붙은 행은 그 자체로 눈에 띄어 케이스 표본과 구분된다.
 */
@Slf4j
@Component
@Profile(SeedConfig.PROFILE)
@RequiredArgsConstructor
public class SeedPadding {

    /** U1 이 자가등록으로 소유하는 패딩 수. 케이스 소유분(C01/C02/D06)과 합쳐 /companies/my 를 채운다. */
    private static final int OWNED_COUNT = 12;

    /** 관리자 등록 패딩 수. 공개 목록 정족수(케이스 공개분 + 이 수 >= 41)를 맞춘다. */
    private static final int ADMIN_COUNT = 12;

    private final SeedCompanyFactory companies;
    private final SeedVerificationFactory verifications;
    private final SeedMasterCatalog masters;

    public Result create(SeedAccounts accounts) {
        List<SeedCompanyRef> owned = new ArrayList<>();
        List<SeedCompanyRef> adminRegistered = new ArrayList<>();
        SeedAccount owner = accounts.owner1();
        Long adminId = accounts.admin().userId();

        for (int n = 1; n <= OWNED_COUNT; n++) {
            int index = SeedIndexes.forPadding(n);
            Long verificationId = verifications.issue(
                    owner.userId(),
                    SeedBusinessNumbers.paddingNumber(n),
                    SeedVocabulary.ceoName(index),
                    SeedVocabulary.establishmentDate(index));

            Long companyId = companies.createByUser(owner.userId(),
                    SeedCompanyRequestBuilder.of(index, masters).buildMy(verificationId));

            owned.add(new SeedCompanyRef(code(n), companyId, owner.code(),
                    "정족수 패딩(U1 자가등록). /companies/my 페이지네이션과 공개 목록을 함께 채운다"));
        }

        for (int n = 1; n <= ADMIN_COUNT; n++) {
            int sequence = OWNED_COUNT + n;
            int index = SeedIndexes.forPadding(sequence);
            // 최소 모양과 잘 채운 모양을 번갈아 써서 목록 카드가 한 가지 모양으로만 보이지 않게 한다.
            boolean filled = n % 2 == 0;

            SeedCompanyRequestBuilder builder = SeedCompanyRequestBuilder.of(index, masters)
                    .businessNumber(SeedBusinessNumbers.paddingNumber(sequence));
            Long companyId = companies.createByAdmin(adminId, (filled ? builder.filled(masters) : builder).build());

            adminRegistered.add(new SeedCompanyRef(code(sequence), companyId, null,
                    "정족수 패딩(관리자 등록, " + (filled ? "잘 채운 모양" : "최소 모양")
                            + "). 공개 목록을 기본 size=20 기준 3페이지까지 채운다"));
        }

        log.info("[seed] 패딩 회사 {}건 생성 (U1 소유 {}, 관리자 등록 {})",
                owned.size() + adminRegistered.size(), owned.size(), adminRegistered.size());
        return new Result(owned, adminRegistered);
    }

    /** 즐겨찾기 대상으로는 관리자 등록분만 쓴다 — 남의 회사를 찜하는 것이 실제 사용 흐름이다. */
    public record Result(List<SeedCompanyRef> owned, List<SeedCompanyRef> adminRegistered) {

        public List<SeedCompanyRef> all() {
            List<SeedCompanyRef> merged = new ArrayList<>(owned);
            merged.addAll(adminRegistered);
            return merged;
        }
    }

    private String code(int sequence) {
        return "P" + String.format("%02d", sequence);
    }
}
