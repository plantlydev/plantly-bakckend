package project.plantly.global.meta;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.plantly.domain.upload.storage.StorageProperties;
import project.plantly.global.meta.dto.UploadConstraintsResponse;
import project.plantly.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
public class MetaUploadController {

    private final StorageProperties storageProperties;

    /**
     * 이미지 업로드 제약. 파일을 고르는 순간 프론트가 스스로 막을 근거다 —
     * {@code <input accept>} 와 업로드 전 크기 검사에 쓴다.
     *
     * <p>폼 제약({@link MetaConstraintsController})·선택지({@link MetaOptionsController})와 경로를 나눈 이유는
     * 출처와 모양이 둘 다 다르기 때문이다. 폼 제약은 요청 DTO 의 검증 애너테이션에서 도출하고 폼 단위로
     * 묻지만, 이 값들은 <b>DTO 에 없다</b> — 상한은 설정({@code app.upload.max-file-size}), 형식은
     * {@code ImageFormat} enum 이 정본이다. 선택지 카탈로그와도 모양이 맞지 않는다(값/라벨 쌍이 아니라
     * 숫자 하나와 문자열 목록 둘이다).
     *
     * <p>업로드 자체가 로그인을 요구하므로({@code POST /api/v1/uploads}) <b>이 조회도 인증이 필요하다</b>
     * (SecurityConfig 의 기본 규칙). 선택지 카탈로그를 비로그인에 연 것과 다른 판단인데, 그쪽은 라벨이
     * 공개 상세 화면에서도 쓰이기 때문이고 이쪽은 로그인 없이 할 수 있는 일이 없다.
     *
     * <p>장수 제한은 여기 없다 — 요청 DTO 의 {@code @Size} 라 폼 제약 응답의 {@code maxItems} 가 준다.
     */
    @GetMapping("/api/v1/meta/upload")
    public ApiResponse<UploadConstraintsResponse> getUploadConstraints() {

        return ApiResponse.success(UploadConstraintsResponse.of(storageProperties));
    }
}
