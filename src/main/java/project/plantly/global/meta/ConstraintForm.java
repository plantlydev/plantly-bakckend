package project.plantly.global.meta;

import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.dto.CompanyUpdateRequest;
import project.plantly.domain.company.dto.MyCompanyCreateRequest;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.exception.CommonErrorCode;

/**
 * 제약을 공개하는 폼 목록. 슬러그 하나가 요청 DTO 하나에 대응한다.
 *
 * <p>임의의 클래스 이름을 경로로 받지 않고 화이트리스트를 두는 이유는, 그렇게 하면 이 엔드포인트가
 * "아무 클래스나 리플렉션으로 열어보는 창구" 가 되기 때문이다. 여기 적힌 폼만 열린다.
 *
 * <p>슬러그는 클래스 이름에서 유도하지 않고 손으로 적는다 — 클래스 이름을 바꿔도 프론트가 쓰는 경로가
 * 따라 바뀌지 않게 하려는 것이다. 슬러그가 곧 공개 계약이다.
 *
 * <p>등록이 두 개인 것은 경로가 둘이기 때문이다. 자가등록({@code my-company-create})은 신원 3종을
 * 아예 받지 않아 그 칸이 폼에 없고, 관리자 등록({@code company-create})은 받는다.
 */
public enum ConstraintForm {

    /** 관리자 등록 폼. */
    COMPANY_CREATE("company-create", CompanyCreateRequest.class),

    /** 유저 자가등록 폼. 선행 인증이 채우는 신원 3종(사업자번호·대표자명·개업일자)이 빠져 있다. */
    MY_COMPANY_CREATE("my-company-create", MyCompanyCreateRequest.class),

    /**
     * 회사 기본 정보 수정 폼.
     *
     * <p>등록과 제약이 겹쳐 보이지만 같지 않다 — 수정은 sparse PATCH 라 <b>필수 규칙이 하나도 없고</b>
     * (null = 미변경), 대신 빈 문자열로 지울 수 없는 칸에 {@code minLength 1} 이 붙는다. 이 차이는 PATCH
     * 의미론에서 오는 것이다. 프론트가 수정 화면에
     * 등록 폼의 제약을 재사용하면 미변경으로 두어도 되는 칸을 필수로 막게 된다.
     *
     * <p><b>본체 스칼라만 담긴다.</b> 수정 화면의 컬렉션(태그·소재·카테고리 등 11종)은 이 DTO 가 아니라
     * 각자의 교체 PUT 엔드포인트로 가고, 그 제약은 컨트롤러 파라미터에 붙어 있어 이 응답에 나오지 않는다.
     * 값은 등록 경로와 같은 {@code CompanyConstraints} 상수를 쓰므로 프론트는 등록 폼 응답의 개수 상한을
     * 그대로 보면 되지만, 그건 지금 두 경로가 같은 상수를 참조한다는 사실일 뿐 이 API 가 약속하는 계약은
     * 아니다. 두 경로의 개수 한도가 갈리기 시작하면 그때 이 응답에 컬렉션을 합칠지 정해야 한다.
     */
    COMPANY_UPDATE("company-update", CompanyUpdateRequest.class);

    // 회원가입 폼은 일부러 열지 않는다. 비밀번호 규칙은 가입 화면이 "10자 이상, 특수문자 1개 포함" 같은
    // 안내 문구로 어차피 화면에 적어야 하는 값이라, API 로 한 번 더 주면 같은 사실이 두 곳에 산다.
    // 열게 된다면 비로그인도 읽어야 하므로 SecurityConfig 에 permitAll 도 함께 필요하다.

    private final String slug;
    private final Class<?> type;

    ConstraintForm(String slug, Class<?> type) {
        this.slug = slug;
        this.type = type;
    }

    /**
     * 경로 변수의 슬러그로 폼을 찾는다.
     *
     * <p>없는 슬러그는 404 다 — 잘못된 입력값(400)이 아니라 존재하지 않는 자원이기 때문이다.
     * 프론트가 오타 난 폼 이름을 보냈을 때 "그런 폼은 없다" 로 읽혀야 한다.
     */
    public static ConstraintForm of(String slug) {
        for (ConstraintForm form : values()) {
            if (form.slug.equals(slug)) {
                return form;
            }
        }

        throw new BusinessException(CommonErrorCode.NOT_FOUND);
    }

    public String slug() {
        return slug;
    }

    public Class<?> type() {
        return type;
    }
}
