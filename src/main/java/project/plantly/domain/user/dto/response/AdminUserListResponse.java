package project.plantly.domain.user.dto.response;

import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;

import java.time.LocalDateTime;

public record AdminUserListResponse(String email,
                                    String name,
                                    String phone,
                                    LocalDateTime createdAt,
                                    UserRole userRole,
                                    UserStatus userStatus
) {
}
