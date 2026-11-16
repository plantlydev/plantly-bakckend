package project.plantly.domain.user.repository;

import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;

// 세션 재검증에 필요한 두 값만 읽는 프로젝션. 엔티티를 불러오지 않아 영속성 컨텍스트에 남는 것이 없다
// (SessionUserRevalidationFilter 참고).
public record UserAuthState(UserStatus userStatus, UserRole userRole) {
}
