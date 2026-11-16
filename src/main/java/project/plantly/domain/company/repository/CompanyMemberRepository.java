package project.plantly.domain.company.repository;

import java.util.List;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import project.plantly.domain.company.entity.link.CompanyMember;
import project.plantly.domain.company.enums.MemberRole;

import java.util.Optional;

public interface CompanyMemberRepository extends JpaRepository<CompanyMember, Long> {

    // 소유자 전용 상세 뷰 접근 제어용. 요청자가 해당 회사의 멤버인지 확인한다.
    // (현재 멤버는 OWNER 1건뿐이라 사실상 소유자 검증과 동치)
    boolean existsByCompanyIdAndUserId(Long companyId, Long userId);

    // 상세 응답 메타의 소유자 id 표시용. 회사의 OWNER 멤버(있으면 1건)를 찾는다. 미연동이면 empty.
    Optional<CompanyMember> findByCompanyIdAndRole(Long companyId, MemberRole role);

    // 회원 탈퇴 때 함께 삭제할 소유 회사 id. 삭제된 회사도 포함한다(소유 연결이 남아 있으므로 함께 정리된다).
    @Query("select m.companyId from CompanyMember m where m.userId = :userId and m.role = :role")
    List<Long> findCompanyIdsByUserIdAndRole(@Param("userId") Long userId, @Param("role") MemberRole role);

    // 회원 탈퇴 정리(WithdrawalService). JPQL 벌크 DELETE — 건별 삭제의 0건 낙관적 락 실패를 피한다(리뷰 ⑥).
    @Modifying
    @Query("delete from CompanyMember x where x.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
