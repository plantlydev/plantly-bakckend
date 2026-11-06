package project.plantly.domain.company.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import project.plantly.domain.company.entity.Company;

import java.util.Optional;

/**
 * {@link CompanyLockRepository} 구현.
 *
 * <p><b>왜 {@code @Lock(PESSIMISTIC_WRITE)} 쿼리 메서드로는 부족한가:</b> OSIV(open-in-view)가 켜져 있으면
 * 요청 하나가 EntityManager 하나를 공유한다. 재인증처럼 한 요청 안에서 앞 트랜잭션이 회사를 이미 읽어 둔
 * 경우, 뒤 트랜잭션의 락 쿼리는 행 락은 잡지만 Hibernate 가 이미 관리 중인 인스턴스를 <i>갱신하지 않고</i>
 * 그대로 돌려준다. 락 아래에서 확인한 값이 실제로는 앞 단계의 옛 값이 되어, 그 사이 커밋된 관리자 회수를
 * 보지 못하고 덮어쓴다({@code ReverificationOsivAcceptanceTest} 가 재현한다).
 *
 * <p>그래서 회사를 먼저 찾은 뒤 {@code refresh(PESSIMISTIC_WRITE)} 로 잠그면서 DB 값으로 다시 읽는다.
 * 영속성 컨텍스트에 무엇이 남아 있든 락을 얻은 시점의 커밋된 상태를 보장한다. 대가는 회사가 아직 로드되지
 * 않은 경우 SELECT 가 한 번 더 나가는 것뿐이다(Company 는 연관관계가 없어 refresh 가 다른 테이블로 번지지 않는다).
 */
class CompanyLockRepositoryImpl implements CompanyLockRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Company> findByIdForUpdate(Long id) {
        Company company = em.find(Company.class, id);
        if (company == null) {
            return Optional.empty();
        }
        em.refresh(company, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(company);
    }
}
