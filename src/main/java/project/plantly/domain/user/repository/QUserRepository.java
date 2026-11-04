package project.plantly.domain.user.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;
import project.plantly.domain.user.QUser;
import org.springframework.data.domain.Page;
import project.plantly.domain.user.dto.response.AdminUserListResponse;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class QUserRepository {

    private final JPAQueryFactory jpaQueryFactory;
    private final QUser qUser = QUser.user;

    public Page<AdminUserListResponse> getAdminUsers (Pageable pageable){
        List<AdminUserListResponse> content = jpaQueryFactory
                .select(Projections.constructor(AdminUserListResponse.class,
                        qUser.email,
                        qUser.name,
                        qUser.phone,
                        qUser.createdAt,
                        qUser.userRole,
                        qUser.userStatus))
                .from(qUser)
                .where()
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(qUser.id.desc())
                .fetch();

        JPAQuery<Long> countQuery = jpaQueryFactory
                .select(qUser.count())
                .from(qUser);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }
}
