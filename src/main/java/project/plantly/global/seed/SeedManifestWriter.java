package project.plantly.global.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.entity.Company;
import project.plantly.domain.company.entity.CompanyVerification;
import project.plantly.domain.company.enums.MemberRole;
import project.plantly.domain.company.repository.CompanyCategoryRepository;
import project.plantly.domain.company.repository.CompanyDraftRepository;
import project.plantly.domain.company.repository.CompanyMemberRepository;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.domain.company.repository.CompanyVerificationRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 케이스↔id 매핑 산출물 생성. JSON(기계 판독)과 Markdown(사람 판독)을 함께 쓴다.
 *
 * <p>id 는 시드할 때마다 새로 부여되지만, {@code --app.seed.reset=true} 경로는 테이블을
 * {@code RESTART IDENTITY} 로 비우고 항상 같은 순서로 심으므로 id 까지 재현된다. 그래서 이 산출물은
 * 커밋하지 않고({@code .gitignore} 의 {@code /docs/seed/}) 필요할 때 다시 심어 얻는다 — 케이스 정의는
 * 이 패키지의 코드가 소유하고, 문서는 그 실행 결과일 뿐이다. 케이스 코드(C13 등)는 id 가 어떻게 매겨지든
 * 변하지 않는 안정 키라, 프론트는 이 코드로 참조하면 된다.
 */
@Slf4j
@Component
@Profile(SeedConfig.PROFILE)
@RequiredArgsConstructor
public class SeedManifestWriter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HOW_TO_RUN =
            "./gradlew bootRun --args='--spring.profiles.active=local,seed --app.seed.reset=true'";
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CompanyRepository companyRepository;
    private final CompanyVerificationRepository verificationRepository;
    private final CompanyDraftRepository draftRepository;
    private final CompanyMemberRepository memberRepository;
    private final CompanyCategoryRepository companyCategoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SeedProperties properties;

    @Transactional(readOnly = true)
    public void write(SeedAccounts accounts, List<SeedCompanyRef> companyRefs, List<SeedDraftRef> draftRefs) {
        SeedManifest manifest = new SeedManifest(
                LocalDateTime.now().format(TIMESTAMP),
                HOW_TO_RUN,
                SeedAccountFactory.PASSWORD,
                accountRows(accounts),
                companyRows(companyRefs),
                draftRows(draftRefs),
                pagination(accounts));

        Path directory = Path.of(properties.outputDir());
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("seed-manifest.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("SEED_CASES.md"),
                    SeedManifestMarkdown.render(manifest),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("시드 매니페스트를 쓰지 못했습니다: " + directory.toAbsolutePath(), e);
        }

        log.info("[seed] 매니페스트 생성 — {}", directory.toAbsolutePath());
    }

    private List<SeedManifest.AccountRow> accountRows(SeedAccounts accounts) {
        return accounts.all().stream()
                .map(account -> new SeedManifest.AccountRow(
                        account.code(), account.userId(), account.email(), account.password(),
                        account.name(), account.role().name(), account.status().name(), account.note()))
                .toList();
    }

    private List<SeedManifest.CompanyRow> companyRows(List<SeedCompanyRef> refs) {
        List<SeedManifest.CompanyRow> rows = new ArrayList<>();

        for (SeedCompanyRef ref : refs) {
            Company company = companyRepository.findById(ref.companyId()).orElseThrow();
            Long ownerUserId = memberRepository.findByCompanyIdAndRole(ref.companyId(), MemberRole.OWNER)
                    .map(member -> member.getUserId()).orElse(null);

            rows.add(new SeedManifest.CompanyRow(
                    ref.code(),
                    company.getId(),
                    company.getCompanyName(),
                    company.getBusinessNumber(),
                    company.getVisibility().name(),
                    company.getRegistrationSource().name(),
                    ref.ownerCode(),
                    ownerUserId,
                    company.isDeleted(),
                    company.isVerified(),
                    company.isBusinessVerified(),
                    company.isFeatured(),
                    company.isSpotlight(),
                    company.getSpotlightOrder(),
                    companyCategoryRepository.findLinksByCompanyId(ref.companyId()).size(),
                    ref.proves()));
        }
        return rows;
    }

    private List<SeedManifest.DraftRow> draftRows(List<SeedDraftRef> refs) {
        List<SeedManifest.DraftRow> rows = new ArrayList<>();

        for (SeedDraftRef ref : refs) {
            if (ref.verificationId() == null) {
                // D07 처럼 인증 레코드가 남지 않는 케이스(실패 시도는 감사 로그만 남는다).
                rows.add(new SeedManifest.DraftRow(
                        ref.code(), null, ref.userCode(), null, null, null, false, null, ref.proves()));
                continue;
            }

            CompanyVerification verification = verificationRepository.findById(ref.verificationId()).orElseThrow();
            rows.add(new SeedManifest.DraftRow(
                    ref.code(),
                    verification.getId(),
                    ref.userCode(),
                    verification.getBusinessNumber(),
                    verification.getStatus().name(),
                    verification.getExpiresAt().toString(),
                    draftRepository.findByUserIdAndBusinessNumber(
                            verification.getUserId(), verification.getBusinessNumber()).isPresent(),
                    verification.getCompanyId(),
                    ref.proves()));
        }
        return rows;
    }

    /**
     * 뷰별 행 수. 시드가 "몇 건 만들었다"가 아니라 각 목록 API 가 실제로 몇 건을 돌려주는지를 세야,
     * 페이지네이션 정족수를 채웠는지 판단할 수 있다.
     */
    private SeedManifest.PaginationRow pagination(SeedAccounts accounts) {
        return new SeedManifest.PaginationRow(
                DEFAULT_PAGE_SIZE,
                count("SELECT count(*) FROM company WHERE deleted = false AND visibility = 'PUBLIC'"),
                count("SELECT count(*) FROM company"),
                count("SELECT count(*) FROM company_member m JOIN company c ON c.id = m.company_id "
                        + "WHERE m.user_id = ? AND m.role = 'OWNER' AND c.deleted = false",
                        accounts.owner1().userId()),
                count("SELECT count(*) FROM company_favorite WHERE user_id = ?", accounts.owner1().userId()));
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNullElse(jdbcTemplate.queryForObject(sql, Long.class, args), 0L);
    }
}
