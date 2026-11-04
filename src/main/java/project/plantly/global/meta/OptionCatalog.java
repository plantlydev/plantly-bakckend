package project.plantly.global.meta;

import project.plantly.domain.company.enums.PricingType;
import project.plantly.domain.company.enums.TrlLevel;
import project.plantly.global.meta.dto.EnumOption;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 선택지를 공개하는 enum 목록. 항목 하나가 응답의 키 하나에 대응한다.
 *
 * <p>화이트리스트를 두는 이유는 {@link ConstraintForm} 과 같다 — 그러지 않으면 이 엔드포인트가
 * "아무 enum 이나 열어보는 창구" 가 된다. 여기 적힌 것만 나간다.
 *
 * <p><b>키는 요청 DTO 의 필드 이름과 같게 적는다.</b> 제약 조회 응답의 {@code field}, 검증 실패 응답의
 * {@code errors[].field} 와 같은 어휘라, 프론트가 폼을 그릴 때 이름 하나로 세 응답을 이어 붙일 수 있다
 * (이 칸의 규칙 · 이 칸의 선택지 · 이 칸의 위반). 클래스 이름에서 유도하지 않고 손으로 적는 것도 같은
 * 이유다 — 클래스 이름을 바꿔도 프론트가 쓰는 키가 따라 바뀌면 안 된다.
 *
 * <p>선택지 순서는 enum 선언 순서 그대로다. 그 순서가 곧 드롭다운 순서이므로, 상수 순서를 바꾸는 것은
 * 화면을 바꾸는 일이다({@code TrlLevel} 은 성숙도 오름차순이라 특히 그렇다).
 *
 * <p><b>여기 없는 enum 은 일부러 없는 것이다.</b> 라벨을 서버가 소유할지 판단하는 기준은 셋이고,
 * <b>하나만 걸려서는 부족하다</b> — 셋을 함께 봐야 한다:
 * <ol>
 *   <li>등록 폼의 <b>공식 선택지</b>인가</li>
 *   <li>서버가 <b>정의하는 비즈니스 용어</b>인가</li>
 *   <li><b>여러 클라이언트</b>가 같은 이름을 써야 하는가</li>
 * </ol>
 *
 * <p>반대로 아래 넷에 해당하면 라벨을 붙이지 않는다:
 * <ol>
 *   <li>관리자 화면·사용자 혼자 보는 화면에서 단순히 <b>상태를 보기 좋게 표시</b>하는 것</li>
 *   <li>버튼·배지 같은 <b>UI 문구</b></li>
 *   <li>클라이언트마다 <b>표현이 달라도 되는</b> 값</li>
 *   <li><b>내부 로직용</b></li>
 * </ol>
 *
 * <p>지금 실려 있는 둘은 셋을 다 만족한다. 기술성숙도·견적 산출방식은 우리가 정의한 분류 체계이고
 * (국세청 형식이나 ISO 코드처럼 외부가 정한 것이 아니다), 등록 폼에서 고른 값이 카드·상세로 되돌아와
 * 여러 화면이 같은 이름을 써야 한다.
 *
 * <p><b>{@code CompanyVisibility} 는 한 번 넣었다가 이 기준으로 다시 뺐다(2026-09-10).</b> 등록 폼의
 * 선택지라 (1)은 만족하지만 (2)·(3)을 만족하지 않는다 — 공개/비공개는 우리가 정의한 업무 용어가 아니고,
 * 사용자에게 노출되는 자리가 "내 등록 기업 목록에서 공개 여부를 표시" 하는 정도라 배제 케이스 (1)에
 * 해당한다. 표현도 클라이언트마다 달라도 된다. <b>기준 (1)만 보고 판단하면 이 값이 들어온다</b> —
 * 실제로 그렇게 들어왔었다. 새 enum 을 볼 때 셋을 함께 확인할 것.
 *
 * <p>{@code ImageType} 은 (1)부터 걸린다. 필수 필드이긴 하지만 등록 폼의 갤러리에서 합법값이
 * {@code DETAIL} 하나뿐이라({@code GalleryImageTypePolicy}) 선택지가 아니라 상수다.
 *
 * <p>인증 그룹·대륙은 애초에 고르는 값이 아니라 받아서 그리는 값이다. 둘 다
 * 그 문구를 프론트가 소유하기로 이미 정해져 있다({@code CertificationPublicResponse}/
 * {@code CountryPublicResponse} 주석 — 배제 케이스 (2)·(3)). 나중에 그 결정을 뒤집는다면 두 주석을
 * 함께 고쳐야 한다.
 */
public enum OptionCatalog {

    /** 기술성숙도. 오름차순(프로토타입 → 양산 → 글로벌 표준)이라 선언 순서가 의미를 갖는다. */
    TRL_LEVEL("trlLevel", TrlLevel::values),

    /** 견적 산출방식. */
    PRICING_TYPE("pricingType", PricingType::values);

    private final String key;
    private final Supplier<LabeledEnum[]> values;

    OptionCatalog(String key, Supplier<LabeledEnum[]> values) {
        this.key = key;
        this.values = values;
    }

    public String key() {
        return key;
    }

    /** 선언 순서 그대로의 선택지 목록. */
    public List<EnumOption> options() {
        return Stream.of(values.get()).map(EnumOption::from).toList();
    }

    /**
     * 카탈로그 전체. 낱개 조회 엔드포인트를 두지 않는 이유는 전부 합쳐도 응답이 수백 바이트라,
     * 폼이 열릴 때 한 번 받아두는 편이 칸마다 왕복하는 것보다 단순하기 때문이다.
     * (키 순서는 이 enum 선언 순서를 따른다 — LinkedHashMap)
     */
    public static Map<String, List<EnumOption>> all() {
        Map<String, List<EnumOption>> catalog = new LinkedHashMap<>();
        for (OptionCatalog entry : values()) {
            catalog.put(entry.key, entry.options());
        }
        return catalog;
    }
}
