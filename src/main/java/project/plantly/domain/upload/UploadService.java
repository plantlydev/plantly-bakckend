package project.plantly.domain.upload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import project.plantly.domain.upload.dto.UploadResponse;
import project.plantly.domain.upload.exception.UploadErrorCode;
import project.plantly.domain.upload.storage.FileStorage;
import project.plantly.domain.upload.storage.StorageProperties;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.exception.CommonErrorCode;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 파일 업로드/조회.
 *
 * <p>이 서비스가 하는 일은 <b>파일을 URL 문자열로 바꾸는 것</b> 하나다. 그 URL 이 어느 회사의 로고로
 * 쓰일지, 갤러리 몇 장까지 허용되는지는 여기서 보지 않는다 — 장수 상한은 회사 발행/수정 요청의
 * 검증({@code CompanyConstraints})이 이미 세고 있고, 두 곳에서 세면 반드시 어긋난다.
 *
 * <p>회사 id 로 스코프를 잡지 않는 것도 의도다. 등록 흐름은 사업자 인증 → 초안 작성 → 발행 순서라
 * 이미지를 고르는 시점에는 회사가 아직 없다. 그래서 업로드의 경계는 "로그인한 사용자"까지다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    /** 발급하는 URL 의 앞부분. 조회 엔드포인트 경로와 반드시 같아야 한다. */
    public static final String URL_PREFIX = "/api/v1/files/";

    private final FileStorage fileStorage;
    private final StorageProperties storageProperties;

    public UploadResponse upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(UploadErrorCode.FILE_REQUIRED);
        }
        // 서블릿 상한(같은 설정에서 StorageConfig 가 만든다)이 보통 먼저 끊지만, 그건 요청을 다 받기 전에
        // 끊는 방어선이고 이쪽이 서비스가 보장하는 계약이다 — 서블릿을 거치지 않는 호출에도 선다.
        if (file.getSize() > storageProperties.maxFileSize().toBytes()) {
            throw new BusinessException(CommonErrorCode.FILE_TOO_LARGE);
        }

        byte[] content = read(file);

        // 클라이언트가 보낸 Content-Type 과 파일명은 보지 않는다. 실제 바이트만 믿는다.
        ImageFormat format = ImageFormat.detect(content)
                .orElseThrow(() -> new BusinessException(UploadErrorCode.UNSUPPORTED_IMAGE_TYPE));

        String key = store(content, format);

        // 남용 추적(누가 얼마나 올렸는지)은 이 로그가 맡는다. 이 목적만으로 메타 테이블을 만들지 않는다 —
        // 읽는 코드가 없고, 정확한 고아 파일 정리는 어차피 회사/초안의 URL 과 대조해야 한다.
        log.info("파일 업로드 userId={} key={} size={} type={}", userId, key, content.length, format.getContentType());

        return new UploadResponse(URL_PREFIX + key, format.getContentType(), content.length);
    }

    /**
     * 서빙할 파일을 찾는다. 형태가 틀린 key 와 없는 key 를 가르지 않고 모두 404 로 모은다 —
     * 그런 파일은 존재할 수 없으므로 구분해 봐야 클라이언트에게 줄 정보가 없다.
     */
    public StoredFile load(String key) {
        ImageFormat format = FileKeys.parse(key)
                .orElseThrow(() -> new BusinessException(UploadErrorCode.FILE_NOT_FOUND));

        Resource resource = fileStorage.load(key)
                .orElseThrow(() -> new BusinessException(UploadErrorCode.FILE_NOT_FOUND));

        return new StoredFile(resource, format);
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            log.error("업로드 파일을 읽지 못했습니다", e);
            throw new BusinessException(UploadErrorCode.STORAGE_FAILURE);
        }
    }

    private String store(byte[] content, ImageFormat format) {
        try {
            return fileStorage.store(content, format);
        } catch (UncheckedIOException e) {
            // 저장소는 상태 코드를 모른다(그건 이 층의 결정이다). 원인은 로그에만 남기고 사용자에게는 일반화한다.
            log.error("파일 저장에 실패했습니다", e);
            throw new BusinessException(UploadErrorCode.STORAGE_FAILURE);
        }
    }

    /** 서빙에 필요한 두 가지 — 내용과, 응답 Content-Type 을 정할 형식. */
    public record StoredFile(Resource resource, ImageFormat format) {
    }
}
