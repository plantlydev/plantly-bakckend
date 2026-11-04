package project.plantly.global.meta;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import project.plantly.global.meta.dto.FormConstraintsResponse;
import project.plantly.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
public class MetaConstraintsController {

    private final FormConstraintsReader formConstraintsReader;

    /**
     * 폼 하나의 입력 제약. 프론트가 저장을 누르기 전에 스스로 막을 근거다.
     *
     * <p>프론트가 사전 검증을 하려면 제약 값을 알아야 하는데, 그 값을 화면 코드에 적어두면 서버 DTO 를
     * 고칠 때 따라오지 않는다. 그러면 프론트가 통과시킨 입력을 서버가 400 으로 되돌리는(혹은 그 반대의)
     * 상태가 조용히 생긴다. 값을 서버에서 받아 쓰면 그 어긋남이 성립하지 않는다.
     *
     * <p>내려가는 것은 <b>우리가 정한 값</b>뿐이다 — 필수 여부·개수·길이. 형식 규칙(사업자등록번호 10자리,
     * 우편번호 5자리, {@code #RRGGBB}, 이메일 표기)은 외부가 정했거나 사실상 불변이라 프론트가 알고 있으면
     * 되고, 계약에 넣으면 못 빼게 되므로 싣지 않는다. 물론 서버는 그대로 강제하므로 형식 오류는 400 으로
     * 되돌아온다. 자세한 근거는 {@link FormConstraintsReader} 참고.
     *
     * <p>그 밖에 프론트가 폼을 그리고 막는 근거가 셋 더 있고, 출처가 다르다:
     * <ul>
     *   <li>고정 선택지(TRL·견적방식)는 제약이 아니라 값 목록이라 {@code GET /api/v1/meta/options} 가
     *       따로 준다. 그쪽은 폼 밖(공개 상세의 값 표시)에서도 쓰이므로 비로그인도 읽을 수 있다.</li>
     *   <li>이미지 업로드 제약(한 장당 용량·허용 형식)은 요청 DTO 에 없다 — 상한은 설정, 형식은
     *       {@code ImageFormat} enum 이 정본이라 {@code GET /api/v1/meta/upload} 가 따로 준다.</li>
     *   <li>중복 여부(이메일·사업자번호)는 값을 봐야 알 수 있어 사전 검증이 불가능하다. 전용 확인
     *       엔드포인트를 왕복한다.</li>
     * </ul>
     *
     * <p>없는 폼 이름은 404 다. 열려 있는 폼은 {@link ConstraintForm} 에 적힌 것뿐이다.
     */
    @GetMapping("/api/v1/meta/constraints/{form}")
    public ApiResponse<FormConstraintsResponse> getFormConstraints(@PathVariable String form) {

        return ApiResponse.success(formConstraintsReader.read(ConstraintForm.of(form)));
    }
}
