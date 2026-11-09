package project.plantly.domain.company.stat;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.company.enums.CompanyVisibility;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.CompanyRepository;
import project.plantly.global.exception.BusinessException;

// 유저의 회사 좋아요/즐겨찾기. 원천 행(CompanyLike/CompanyFavorite)만으로 상태를 표현한다.
// 토글이 아니라 등록(PUT)/해제(DELETE)로 동사를 분리해 멱등하다 — 같은 요청을 두 번 보내도 결과 상태가 동일하다.
//   · 등록: insertIfAbsent(ON CONFLICT DO NOTHING) 로 중복 요청이 unique 위반 500 대신 no-op 이 된다.
//   · 해제: 벌크 DELETE(없는 행 삭제 = 0행) 로 그 자체가 멱등하다. 동시 해제(더블클릭)도 예외 없이 0행으로 끝난다.
// 집계 카운터 캐시(CompanyStat)는 두지 않는다 — count 가 필요해지면 원천 행 COUNT(*) 로 파생한다.
@Service
@RequiredArgsConstructor
public class CompanyStatService {

    private final CompanyRepository companyRepository;
    private final CompanyLikeRepository companyLikeRepository;
    private final CompanyFavoriteRepository companyFavoriteRepository;

    // 좋아요 등록(멱등). 이미 좋아요 중이면 아무 일도 일어나지 않는다.
    @Transactional
    public void like(Long userId, Long companyId) {
        validateActiveCompany(companyId);
        companyLikeRepository.insertIfAbsent(userId, companyId);
    }

    // 좋아요 해제(멱등). 회사 존재 여부는 검증하지 않는다 — 회사가 나중에 삭제돼도 내가 남긴 좋아요는 정리할 수 있어야 한다.
    @Transactional
    public void unlike(Long userId, Long companyId) {
        companyLikeRepository.deleteByUserIdAndCompanyId(userId, companyId);
    }

    // 즐겨찾기 등록(멱등). 목록 노출은 조회 API(findByUserId...)가 별도로 담당한다.
    @Transactional
    public void favorite(Long userId, Long companyId) {
        validateActiveCompany(companyId);
        companyFavoriteRepository.insertIfAbsent(userId, companyId);
    }

    // 즐겨찾기 해제(멱등). unlike 와 동일하게 회사 존재 여부는 검증하지 않는다.
    @Transactional
    public void unfavorite(Long userId, Long companyId) {
        companyFavoriteRepository.deleteByUserIdAndCompanyId(userId, companyId);
    }

    // 공개 상세가 미존재로 취급하는 회사(삭제·비공개)는 여기서도 미존재다 — 없는·삭제된·비공개 회사엔 신규 좋아요/즐겨찾기 불가.
    // 셋을 같은 404 로 거절해야 응답 차이로 비공개 회사의 존재가 드러나지 않는다. 공개일 때 이미 넣은 기록은 그대로 두고
    // (즐겨찾기 목록이 PUBLIC 만 보이므로 숨겨지고, 다시 공개되면 돌아온다), 해제는 검증 없이 언제든 할 수 있다.
    private void validateActiveCompany(Long companyId) {
        if (!companyRepository.existsByIdAndDeletedFalseAndVisibility(companyId, CompanyVisibility.PUBLIC)) {
            throw new BusinessException(CompanyErrorCode.COMPANY_NOT_FOUND);
        }
    }
}