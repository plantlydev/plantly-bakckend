package project.plantly.global.seed;

import java.util.List;

/**
 * 매니페스트 문서 모델. <b>의도가 아니라 DB 에서 다시 읽은 실제 값</b>으로 채운다 — 시드가 "PREMIUM 을
 * 넣었다"고 적어놓고 실제로는 정책에 걸려 다른 값이 저장돼 있으면, 그 문서를 믿은 프론트가 헛수고를 한다.
 */
public record SeedManifest(
        String generatedAt,
        String howToRun,
        String sharedPassword,
        List<AccountRow> accounts,
        List<CompanyRow> companies,
        List<DraftRow> drafts,
        PaginationRow pagination
) {

    public record AccountRow(
            String code,
            Long userId,
            String email,
            String password,
            String name,
            String role,
            String status,
            String note
    ) {
    }

    public record CompanyRow(
            String code,
            Long id,
            String name,
            String businessNumber,
            String visibility,
            String registrationSource,
            String ownerCode,
            Long ownerUserId,
            boolean deleted,
            boolean verified,
            boolean businessVerified,
            boolean featured,
            boolean spotlight,
            int spotlightOrder,
            int categoryCount,
            String proves
    ) {
    }

    public record DraftRow(
            String code,
            Long verificationId,
            String userCode,
            String businessNumber,
            String verificationStatus,
            String expiresAt,
            boolean hasDraft,
            Long companyId,
            String proves
    ) {
    }

    /** 뷰별 정족수. 페이지네이션은 테이블 전체가 아니라 각 뷰에서 따로 확인해야 하므로 뷰 단위로 센다. */
    public record PaginationRow(
            int defaultPageSize,
            long publicList,
            long adminList,
            long ownedByOwner1,
            long favoritesOfOwner1
    ) {
    }
}
