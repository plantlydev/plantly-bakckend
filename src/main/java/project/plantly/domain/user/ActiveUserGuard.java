package project.plantly.domain.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserAuthState;
import project.plantly.domain.user.repository.UserRepository;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.exception.CommonErrorCode;

// 회원 데이터를 쓰는 경로의 입구. 호출한 트랜잭션 안에서 회원 행을 잠그고, 지금도 ACTIVE 인지 확인한다.
//
// 왜 필요한가: 탈퇴(WithdrawalService)도 같은 회원 행을 잠그고 시작한다. 그래서 탈퇴와 동시에 처리 중이던 요청
// (다른 기기의 회사 등록·초안 저장·프로필 수정 등)은 탈퇴가 끝날 때까지 기다렸다가 WITHDRAWN 을 보고 401 로 멈춘다.
// 이 장치가 없으면 탈퇴가 지운 뒤에 회사·초안이 다시 생기거나, 프로필 수정이 읽어 둔 옛 행으로 익명화를 덮어써
// 상태가 ACTIVE 로 되살아날 수 있다(User 는 dirty checking 이 모든 칼럼을 다시 쓴다).
// 탈퇴 뒤에 새로 들어오는 요청은 이보다 앞에서 세션 재검증(SessionUserRevalidationFilter)이 끊는다.
//
// 상태는 잠근 엔티티가 아니라 프로젝션으로 다시 읽는다. 같은 요청에서 이미 읽은 User 가 영속성 컨텍스트에 있으면
// 락 쿼리가 돌려준 엔티티 값은 갱신되지 않기 때문이다(UserRepository#findByIdForUpdate 주석 참고).
@Component
@RequiredArgsConstructor
public class ActiveUserGuard {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        UserAuthState state = userRepository.findAuthStateById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        if (state.userStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
