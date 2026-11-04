package project.plantly.global.seed;

import java.util.List;

/**
 * 매니페스트의 사람이 읽는 표현. 프론트 작업 중 옆에 띄워 두고 참조하는 문서라, 값 나열보다
 * "무엇을 확인하려고 이 행이 존재하는가"(proves)가 눈에 들어오도록 배치한다.
 */
final class SeedManifestMarkdown {

    private SeedManifestMarkdown() {
    }

    static String render(SeedManifest manifest) {
        StringBuilder out = new StringBuilder();

        out.append("# 시드 케이스 목록\n\n");
        out.append("> 이 파일은 시드 실행 시 자동 생성됩니다. 직접 고치지 마세요 — 케이스 정의는 ")
                .append("`src/main/java/project/plantly/global/seed/` 아래 코드가 소유합니다.\n\n");
        out.append("- 생성 시각: `").append(manifest.generatedAt()).append("`\n");
        out.append("- 실행: `").append(manifest.howToRun()).append("`\n");
        out.append("- 공통 비밀번호: `").append(manifest.sharedPassword()).append("`\n\n");
        out.append("케이스 코드(C13, D04 …)는 시드를 다시 돌려도 변하지 않는 안정 키입니다. ")
                .append("`--app.seed.reset=true` 로 실행하면 id 시퀀스도 초기화되므로 id 까지 같은 값으로 재현됩니다.\n\n");

        renderPagination(out, manifest.pagination());
        renderAccounts(out, manifest.accounts());
        renderCompanies(out, manifest.companies());
        renderDrafts(out, manifest.drafts());

        return out.toString();
    }

    private static void renderPagination(StringBuilder out, SeedManifest.PaginationRow pagination) {
        out.append("## 페이지네이션 정족수\n\n");
        out.append("페이지네이션은 테이블 전체가 아니라 **뷰마다** 확인해야 합니다. ")
                .append("기본 페이지 크기는 ").append(pagination.defaultPageSize())
                .append(" 이고, `?size=` 로 줄이면 적은 데이터로도 경계를 확인할 수 있습니다.\n\n");
        out.append("| 엔드포인트 | 행 수 | 기본 size 기준 페이지 |\n|---|---:|---|\n");
        appendPaginationRow(out, "GET /api/v1/companies", pagination.publicList(), pagination.defaultPageSize());
        appendPaginationRow(out, "GET /api/v1/admin/companies", pagination.adminList(), pagination.defaultPageSize());
        appendPaginationRow(out, "GET /api/v1/companies/my (U1)", pagination.ownedByOwner1(), pagination.defaultPageSize());
        appendPaginationRow(out, "GET /api/v1/companies/favorites (U1)", pagination.favoritesOfOwner1(), pagination.defaultPageSize());
        out.append('\n');
    }

    private static void appendPaginationRow(StringBuilder out, String endpoint, long rows, int pageSize) {
        long pages = rows == 0 ? 0 : (rows + pageSize - 1) / pageSize;
        out.append("| `").append(endpoint).append("` | ").append(rows).append(" | ").append(pages).append(" |\n");
    }

    private static void renderAccounts(StringBuilder out, List<SeedManifest.AccountRow> accounts) {
        out.append("## 계정\n\n");
        out.append("| 코드 | userId | 이메일 | 권한 | 상태 | 용도 |\n|---|---:|---|---|---|---|\n");
        for (SeedManifest.AccountRow account : accounts) {
            out.append("| `").append(account.code()).append("` | ").append(account.userId())
                    .append(" | `").append(account.email()).append("` | ").append(account.role())
                    .append(" | ").append(account.status()).append(" | ").append(account.note()).append(" |\n");
        }
        out.append('\n');
    }

    private static void renderCompanies(StringBuilder out, List<SeedManifest.CompanyRow> companies) {
        out.append("## 회사\n\n");
        out.append("| 코드 | id | 회사명 | 공개 | 등록 | 소유 | 플래그 | 확인 대상 |\n");
        out.append("|---|---:|---|---|---|---|---|---|\n");

        for (SeedManifest.CompanyRow company : companies) {
            out.append("| `").append(company.code()).append("` | ").append(company.id())
                    .append(" | ").append(company.name())
                    .append(" | ").append("PUBLIC".equals(company.visibility()) ? "공개" : "비공개")
                    .append(" | ").append("USER".equals(company.registrationSource()) ? "자가" : "관리자")
                    .append(" | ").append(company.ownerCode() == null ? "—" : company.ownerCode())
                    .append(" | ").append(flags(company))
                    .append(" | ").append(company.proves()).append(" |\n");
        }
        out.append('\n');
    }

    private static void renderDrafts(StringBuilder out, List<SeedManifest.DraftRow> drafts) {
        out.append("## 인증 · 임시저장\n\n");
        out.append("초안 API 는 회사 id 가 아니라 **`verificationId`** 를 경로 변수로 받습니다 ")
                .append("(`GET|PUT|DELETE /api/v1/companies/drafts/{verificationId}`). ")
                .append("초안은 아직 회사가 아니기 때문입니다.\n\n");
        out.append("| 코드 | verificationId | 계정 | 인증 상태 | 만료 | 초안 | 회사 | 확인 대상 |\n");
        out.append("|---|---:|---|---|---|---|---:|---|\n");

        for (SeedManifest.DraftRow draft : drafts) {
            out.append("| `").append(draft.code()).append("` | ")
                    .append(draft.verificationId() == null ? "—" : draft.verificationId())
                    .append(" | ").append(draft.userCode())
                    .append(" | ").append(nullToDash(draft.verificationStatus()))
                    .append(" | ").append(nullToDash(draft.expiresAt()))
                    .append(" | ").append(draft.hasDraft() ? "있음" : "없음")
                    .append(" | ").append(draft.companyId() == null ? "—" : draft.companyId())
                    .append(" | ").append(draft.proves()).append(" |\n");
        }
        out.append('\n');
    }

    private static String flags(SeedManifest.CompanyRow company) {
        List<String> flags = new java.util.ArrayList<>();
        if (company.deleted()) {
            flags.add("삭제");
        }
        if (company.verified()) {
            flags.add("검수");
        }
        if (company.businessVerified()) {
            flags.add("사업자인증");
        }
        if (company.featured()) {
            flags.add("추천");
        }
        if (company.spotlight()) {
            flags.add("스팟" + company.spotlightOrder());
        }
        // 꺼진 링크가 있으면 "활성/전체" 로 적는다 — 공개 화면에 몇 개가 보여야 하는지가 이 표의 확인 대상이다.
        flags.add(company.activeCategoryCount() == company.categoryCount()
                ? "카테고리" + company.categoryCount()
                : "카테고리" + company.activeCategoryCount() + "/" + company.categoryCount());
        return String.join(", ", flags);
    }

    private static String nullToDash(String value) {
        return value == null ? "—" : value;
    }
}
