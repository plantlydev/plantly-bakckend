package project.plantly.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.domain.company.entity.*;
import project.plantly.domain.company.enums.ImageType;
import project.plantly.domain.company.exception.CompanyErrorCode;
import project.plantly.domain.company.repository.*;
import project.plantly.global.exception.BusinessException;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

// 자식(소유) 엔티티 저장 전담. 요청 값을 그대로 저장하며 displayOrder 는 리스트 인덱스로 부여한다.
@Component
@RequiredArgsConstructor
public class CompanyChildWriter {

    private final CompanyContactRepository contactRepository;
    private final CompanyImageRepository imageRepository;
    private final CompanyMaterialRepository materialRepository;
    private final CompanyEquipmentRepository equipmentRepository;
    private final CompanyTagRepository tagRepository;
    private final CompanyProjectReferenceRepository referenceRepository;

    public void write(Company company, CompanyCreateRequest request) {
        saveContacts(company, request.contacts());
        saveIndexed(request.images(), imageRepository,
                (img, i) -> CompanyImage.ofCompany(company, img.imageUrl(), img.imageType(), i));
        saveIndexed(request.materialNames(), materialRepository,
                (name, i) -> new CompanyMaterial(company, name, i));
        saveIndexed(request.equipmentNames(), equipmentRepository,
                (name, i) -> new CompanyEquipment(company, name, i));
        saveIndexed(request.tagNames(), tagRepository,
                (name, i) -> new CompanyTag(company, name, i));
        saveReferences(company, request.references());
    }

    // ===== 컬렉션 전체 교체(PUT) 진입점 =====
    // 각 메서드는 "기존 삭제 → 새 리스트 재저장(displayOrder 재부여)" 하며, create 경로와 저장 로직을 공유한다.
    // null/빈 리스트를 넘기면 삭제만 수행(= 전부 비우기)된다.

    public void replaceTags(Company company, List<String> tagNames) {
        tagRepository.deleteByCompanyId(company.getId());
        saveIndexed(tagNames, tagRepository, (name, i) -> new CompanyTag(company, name, i));
    }

    public void replaceMaterials(Company company, List<String> materialNames) {
        materialRepository.deleteByCompanyId(company.getId());
        saveIndexed(materialNames, materialRepository, (name, i) -> new CompanyMaterial(company, name, i));
    }

    public void replaceEquipment(Company company, List<String> equipmentNames) {
        equipmentRepository.deleteByCompanyId(company.getId());
        saveIndexed(equipmentNames, equipmentRepository, (name, i) -> new CompanyEquipment(company, name, i));
    }

    // 갤러리(회사 직속) 이미지만 교체한다. 프로젝트 레퍼런스 이미지는 replaceReferences 가 관리한다.
    // 구조 불변식: 갤러리에는 DETAIL 타입만 허용한다(GalleryImageTypePolicy 와 동일 규칙). 장수 상한은 요청 DTO 의 @Size 가 건다.
    public void replaceGalleryImages(Company company, List<CompanyCreateRequest.ImageRequest> images) {
        requireGalleryImagesDetailOnly(images);
        imageRepository.deleteByCompanyIdAndProjectReferenceIsNull(company.getId());
        saveIndexed(images, imageRepository,
                (img, i) -> CompanyImage.ofCompany(company, img.imageUrl(), img.imageType(), i));
    }

    // 연락처·레퍼런스는 삭제 뒤 flush 한다. 대표 행에 "회사당 1건" 부분 유니크 인덱스가 걸려 있는데
    // (CompanyRepresentativeIndexInitializer), 파생 delete 는 DELETE 를 flush 까지 미루고 IDENTITY PK 의 INSERT 는
    // persist 즉시 나간다. 그대로 두면 "기존 대표를 새 대표로 바꾸는" 교체가 옛 대표가 남은 채 INSERT 되어 인덱스에 물린다.
    // (CompanyLinkWriter 의 링크 교체와 같은 이유)
    public void replaceContacts(Company company, List<CompanyCreateRequest.ContactRequest> contacts) {
        contactRepository.deleteByCompanyId(company.getId());
        contactRepository.flush();
        saveContacts(company, contacts);
    }

    // 레퍼런스 교체: 딸린 프로젝트 이미지를 먼저 삭제(FK 정리)한 뒤 레퍼런스를 삭제·재저장한다.
    public void replaceReferences(Company company, List<CompanyCreateRequest.ReferenceRequest> references) {
        imageRepository.deleteByCompanyIdAndProjectReferenceIsNotNull(company.getId());
        referenceRepository.deleteByCompanyId(company.getId());
        referenceRepository.flush();
        saveReferences(company, references);
    }

    // 연락처: 초기 버전은 대표 1건만 허용. 저장하는 첫(=유일) 건을 대표로 표시한다.
    // 구조 불변식(1건)은 create(DTO @Size(max=1))·수정(PUT) 양쪽에서 지켜지도록 여기서 함께 강제한다.
    private void saveContacts(Company company, List<CompanyCreateRequest.ContactRequest> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return;
        }
        if (contacts.size() > 1) {
            throw new BusinessException(CompanyErrorCode.CONTACT_LIMIT_EXCEEDED);
        }
        List<CompanyContact> entities = IntStream.range(0, contacts.size())
                .mapToObj(i -> {
                    CompanyCreateRequest.ContactRequest c = contacts.get(i);
                    return new CompanyContact(company, c.contactName(), c.position(), c.phone(), c.email(), i);
                })
                .toList();
        entities.get(0).markAsRepresentative();   // 대표 1건 (현재는 단건만 허용)
        contactRepository.saveAll(entities);
    }

    // (요청 리스트, 저장소, "i번째 요소 + 인덱스 → 엔티티" 팩토리) 를 받아 일괄 저장. null/빈 리스트는 무시한다.
    private <T, E> void saveIndexed(List<T> source,
                                    JpaRepository<E, ?> repository,
                                    BiFunction<T, Integer, E> factory) {
        if (source == null || source.isEmpty()) {
            return;
        }
        List<E> entities = IntStream.range(0, source.size())
                .mapToObj(i -> factory.apply(source.get(i), i))
                .toList();
        repository.saveAll(entities);
    }

    // 레퍼런스는 "본체 저장 → 딸린 프로젝트 이미지 평탄화" 2단계라 별도 처리한다.
    // 구조 불변식(1건)은 create(DTO @Size(max=1))·수정(PUT) 양쪽에서 지켜지도록 여기서 함께 강제한다.
    private void saveReferences(Company company, List<CompanyCreateRequest.ReferenceRequest> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        if (references.size() > 1) {
            throw new BusinessException(CompanyErrorCode.REFERENCE_LIMIT_EXCEEDED);
        }

        // 레퍼런스 본체 저장 — displayOrder 는 요청 순서(인덱스)로 부여하고, FK 확보를 위해 먼저 저장한다.
        List<CompanyProjectReference> entities = IntStream.range(0, references.size())
                .mapToObj(i -> {
                    CompanyCreateRequest.ReferenceRequest r = references.get(i);
                    return new CompanyProjectReference(company, r.projectTitle(), r.achievements(), r.partners(), r.period(), i);
                })
                .toList();
        entities.get(0).markAsRepresentative();   // 대표 1건 (현재는 단건만 허용)
        referenceRepository.saveAll(entities);

        // 각 레퍼런스에 딸린 프로젝트 이미지 → CompanyImage(PROJECT) 로 연결. 이미지 displayOrder 는 레퍼런스 내 순서.
        List<CompanyImage> projectImages = IntStream.range(0, entities.size())
                .boxed()
                .flatMap(i -> {
                    List<String> imageUrls = references.get(i).imageUrls();
                    if (imageUrls == null) {
                        return Stream.empty();
                    }
                    CompanyProjectReference reference = entities.get(i);
                    return IntStream.range(0, imageUrls.size())
                            .mapToObj(j -> CompanyImage.ofProject(reference, imageUrls.get(j), j));
                })
                .toList();
        imageRepository.saveAll(projectImages);
    }

    // 갤러리 이미지 구조 검증: DETAIL 외(또는 null) 타입이 섞이면 거부한다. (create 는 GalleryImageTypePolicy 가 담당)
    private void requireGalleryImagesDetailOnly(List<CompanyCreateRequest.ImageRequest> images) {
        if (images == null) {
            return;
        }
        boolean hasNonDetail = images.stream().anyMatch(img -> img.imageType() != ImageType.DETAIL);
        if (hasNonDetail) {
            throw new BusinessException(CompanyErrorCode.GALLERY_IMAGE_TYPE_NOT_ALLOWED);
        }
    }
}
