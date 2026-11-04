package project.plantly.global.meta.dto;

import project.plantly.domain.upload.ImageFormat;
import project.plantly.domain.upload.storage.StorageProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 이미지 업로드 제약. 프론트가 파일을 고르는 순간 스스로 막을 근거다.
 *
 * <p>계약으로 여는 이유는 두 값의 성격이 다르기 때문이다.
 * <ul>
 *   <li>{@code maxFileSizeBytes} 는 <b>배포마다 달라진다</b> — {@code app.upload.max-file-size} 가
 *       환경변수({@code UPLOAD_MAX_FILE_SIZE})로 주입되므로 코드 상수가 아니다. 프론트가 10MB 를
 *       하드코딩하면 상한을 낮춘 배포에서 조용히 어긋나, 프론트가 통과시킨 파일이 서버에서 400 이 된다.
 *       서블릿 multipart 상한도 같은 값에서 도출하므로({@code StorageConfig}) 여기서 알려준 크기는 서버가
 *       실제로 받는다.</li>
 *   <li>{@code allowed*} 는 사실상 불변이지만({@link ImageFormat} 이 시그니처 판별 코드와 짝이라 형식을
 *       늘리려면 코드를 짜야 한다) <b>같은 목록이 두 곳에 살면 안 된다</b>. 형식을 하나 늘렸을 때
 *       {@code <input accept>} 가 따라오지 않으면 사용자는 고를 수 없는 형식을 서버만 받아들이는 상태가 된다.</li>
 * </ul>
 *
 * <p>{@code allowedExtensions} 는 <b>입력으로 받아들이는</b> 확장자다({@link ImageFormat#getAcceptedExtensions}).
 * 저장용 대표 확장자({@link ImageFormat#getExtension}, JPEG 는 {@code jpg} 하나)를 내보내면 서버는 받는
 * {@code .jpeg}·{@code .jfif} 파일을 프론트가 막는다. 서버가 파일명을 보지 않고 바이트로 최종 판정하므로,
 * 프론트 사전 검사의 기준은 "서버가 받을 파일을 거부하지 않는다" 이고 이 목록은 그 기준에 맞춰 넓게 잡는다.
 * 저장 확장자는 계약에 싣지 않는다 — 발급된 URL 은 클라이언트에게 불투명한 값이다.
 *
 * <p>바이트로 내려보내는 이유는 프론트가 비교할 대상이 {@code File.size}(바이트)이기 때문이다.
 * "10MB" 같은 표기는 화면이 만든다 — 사람이 읽는 문구는 서버가 소유하지 않는다
 * ({@link project.plantly.global.meta.OptionCatalog} 의 라벨 기준과 같은 판단).
 *
 * <p><b>장수</b> 제한은 여기 없다 — 요청 DTO 의 {@code @Size} 라 폼 제약 응답
 * ({@code GET /api/v1/meta/constraints/{form}})의 {@code maxItems} 가 준다. 여기 있는 것은 "한 장당" 제약이다.
 */
public record UploadConstraintsResponse(
        long maxFileSizeBytes,
        List<String> allowedContentTypes,
        List<String> allowedExtensions
) {

    /**
     * 설정과 {@link ImageFormat} 에서 도출한다. 목록을 손으로 나열하지 않는 것이 핵심 —
     * 형식을 추가하면 이 응답이 저절로 따라온다.
     */
    public static UploadConstraintsResponse of(StorageProperties storageProperties) {
        return new UploadConstraintsResponse(
                storageProperties.maxFileSize().toBytes(),
                Arrays.stream(ImageFormat.values()).map(ImageFormat::getContentType).toList(),
                Arrays.stream(ImageFormat.values())
                        .flatMap(format -> format.getAcceptedExtensions().stream())
                        .toList());
    }
}
