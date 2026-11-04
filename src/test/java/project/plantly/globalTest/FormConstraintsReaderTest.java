package project.plantly.globalTest;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import project.plantly.domain.company.dto.CompanyConstraints;
import project.plantly.domain.company.dto.CompanyCreateRequest;
import project.plantly.global.exception.BusinessException;
import project.plantly.global.exception.CommonErrorCode;
import project.plantly.global.meta.ConstraintForm;
import project.plantly.global.meta.FormConstraintsReader;
import project.plantly.global.meta.dto.ConstraintRule;
import project.plantly.global.meta.dto.FieldConstraints;
import project.plantly.global.meta.dto.FormConstraintsResponse;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 폼 제약 조회의 도출 규칙.
 *
 * <p>이 응답이 존재하는 이유는 프론트가 저장 전에 같은 규칙으로 막게 하기 위해서다. 그래서 이 테스트가
 * 잠그는 것은 "값이 무엇인가" 가 아니라 <b>값이 어디서 오는가</b> 다 — 기대값을 숫자로 적지 않고
 * {@link CompanyConstraints} 상수와 대조한다. 숫자를 적어두면 상수를 고칠 때 이 테스트도 함께 고치게 되고,
 * 그러면 "응답이 상수를 따라간다" 는 사실을 아무도 확인하지 않는 상태가 된다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. 도출은 클래스 메타데이터만 보는 순수 계산이라 컨테이너가 필요 없다.
 */
@DisplayName("폼 제약 조회 - 검증 애너테이션에서 규칙을 뽑는다")
class FormConstraintsReaderTest {

    private static ValidatorFactory validatorFactory;
    private static FormConstraintsReader reader;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        reader = new FormConstraintsReader(validatorFactory);
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Nested
    @DisplayName("값의 출처")
    class Source {

        // 응답에 숫자를 따로 적어두면 DTO 를 고칠 때 이쪽이 따라오지 않아, 프론트가 통과시킨 값을
        // 서버가 400 으로 막는 상태가 조용히 생긴다. 그 어긋남이 성립하지 않음을 여기서 잠근다.
        @Test
        @DisplayName("길이 상한은 CompanyConstraints 상수를 그대로 따라간다")
        void lengthsComeFromConstants() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            assertThat(valueOf(response, "companyName", "maxLength"))
                    .isEqualTo(CompanyConstraints.COMPANY_NAME_MAX);
            assertThat(valueOf(response, "content", "maxLength"))
                    .isEqualTo(CompanyConstraints.CONTENT_MAX);
            assertThat(valueOf(response, "logoUrl", "maxLength"))
                    .isEqualTo(CompanyConstraints.URL_MAX);
        }

        // 컬렉션의 @Size 는 길이가 아니라 개수다. 화면에서 쓰임이 전혀 달라('더 추가' 버튼의 비활성 조건)
        // 같은 애너테이션이라도 다른 규칙 이름으로 나가야 한다.
        @Test
        @DisplayName("컬렉션의 @Size 는 maxLength 가 아니라 maxItems 로 나간다")
        void collectionSizeBecomesMaxItems() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            assertThat(valueOf(response, "contacts", "maxItems"))
                    .isEqualTo(CompanyConstraints.CONTACTS_MAX);
            assertThat(valueOf(response, "images", "maxItems"))
                    .isEqualTo(CompanyConstraints.DETAIL_IMAGES_MAX);
            assertThat(valueOf(response, "categoryIds", "maxItems"))
                    .isEqualTo(CompanyConstraints.CATEGORIES_MAX);

            assertThat(rule(field(response, "contacts"), "maxLength")).isNull();
        }

        // 사업자등록번호 10자리·우편번호 5자리는 국세청과 우정사업본부가 정한 형식이라 우리가 바꿀 수
        // 없고, #RRGGBB·이메일 표기도 마찬가지다. 프론트가 알고 있으면 되는 값을 계약에 넣으면
        // 나중에 못 뺀다. 서버는 그대로 강제하므로 놓친 형식 오류는 400 으로 되돌아온다.
        @Test
        @DisplayName("형식 규칙은 계약에 싣지 않는다")
        void formatRulesAreNotPublished() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            assertThat(allTypes(response)).doesNotContain("pattern", "email", "pastOrPresent");

            // 우편번호는 필수이기도 하므로 칸은 남고, 그 안에서 형식 규칙만 빠진다.
            assertThat(types(field(response, "postalCode"))).containsExactly("required");
        }

        // 걸린 제약이 형식뿐인 칸은 남는 것이 이름밖에 없다. 규칙 없는 칸을 내보내면 프론트가
        // "제약을 받았는데 비어 있다" 를 따로 처리해야 한다.
        @Test
        @DisplayName("제약이 형식뿐인 칸은 목록에서 통째로 빠진다")
        void fieldsWithOnlyFormatRulesDisappear() {
            assertThat(names(reader.read(ConstraintForm.COMPANY_CREATE)))
                    .doesNotContain("businessNumber");

            // 수정 폼에서는 셋이 사라진다 - 우편번호·브랜드 컬러는 형식만, 설립일은 @PastOrPresent 만 걸려 있다.
            assertThat(names(reader.read(ConstraintForm.COMPANY_UPDATE)))
                    .doesNotContain("postalCode", "brandColor", "establishmentDate");
        }

        // 메시지를 생략한 제약은 애너테이션에 기본 템플릿 키가 들어 있다. 그대로 내보내면 프론트가
        // "{jakarta.validation.constraints.NotNull.message}" 를 화면에 띄운다.
        @Test
        @DisplayName("메시지는 템플릿이 아니라 해석된 문구로 나간다")
        void messagesAreInterpolated() {
            FormConstraintsResponse response = reader.read(ConstraintForm.MY_COMPANY_CREATE);

            assertThat(rule(field(response, "verificationId"), "required").message())
                    .doesNotContain("{")
                    .doesNotContain("}");
        }
    }

    @Nested
    @DisplayName("순서")
    class Ordering {

        // 프론트가 이 목록을 위에서 아래로 훑는 것만으로 폼 순서를 얻어야 한다. 순서가 없는 Set 을
        // 그대로 내보내면 요청마다 순서가 달라진다.
        @Test
        @DisplayName("필드는 DTO 선언 순서로 나온다")
        void fieldsFollowDeclarationOrder() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            List<String> declared = Arrays.stream(CompanyCreateRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            // 제약이 없는 필드(enum 3종)는 빠지므로 '같은 목록' 이 아니라 '선언 순서를 지키는 부분열' 이다.
            List<String> answered = response.fields().stream().map(FieldConstraints::field).toList();

            assertThat(answered).isEqualTo(declared.stream().filter(answered::contains).toList());
        }

        // 검증 실패 응답의 제약 정렬과 같은 순서다. 두 응답이 "먼저 지적할 규칙" 을 다르게 말하면
        // 프론트의 인라인 표시가 서버 응답과 어긋난다.
        @Test
        @DisplayName("한 필드 안의 규칙은 비어 있음 → 개수 → 길이 순이다")
        void rulesFollowConstraintOrder() {
            // "필수인데 비었다" 를 놔두고 "100자를 넘었다" 부터 지적하지 않는다.
            assertThat(types(field(reader.read(ConstraintForm.COMPANY_CREATE), "companyName")))
                    .containsExactly("required", "maxLength");

            assertThat(types(field(reader.read(ConstraintForm.COMPANY_UPDATE), "companyName")))
                    .containsExactly("minLength", "maxLength");
        }
    }

    @Nested
    @DisplayName("중첩")
    class Nesting {

        // tagNames 의 "20자 이내" 는 리스트가 아니라 원소의 제약이다. 리스트 쪽에 붙여 내보내면
        // 프론트가 태그 한 칸이 아니라 태그 목록 전체에 길이 제한을 건다.
        @Test
        @DisplayName("스칼라 리스트는 개수는 필드에, 원소 규칙은 items 에 담는다")
        void scalarListSplitsCountAndElement() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            FieldConstraints tagNames = field(response, "tagNames");

            assertThat(valueOf(response, "tagNames", "maxItems")).isEqualTo(CompanyConstraints.TAGS_MAX);
            assertThat(types(tagNames.items())).containsExactly("required", "maxLength");
            assertThat(rule(tagNames.items(), "maxLength").value())
                    .isEqualTo(CompanyConstraints.TAG_NAME_MAX);
            // 원소가 문자열이라 하위 입력칸이 없다.
            assertThat(tagNames.items().fields()).isEmpty();
        }

        // contacts[0].contactName 의 경로 모양이 그대로 응답 구조가 된다.
        @Test
        @DisplayName("객체 리스트는 원소의 하위 입력칸까지 items.fields 로 펼친다")
        void objectListExposesNestedFields() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            FieldConstraints contacts = field(response, "contacts");

            // 리스트 자체는 null 이어도 되지만 [null] 같은 깨진 원소는 막는다 - 그 제약이 원소에 붙어 있다.
            assertThat(types(contacts.items())).containsExactly("required");

            FieldConstraints contactName = contacts.items().fields().get(0);

            assertThat(contactName.field()).isEqualTo("contactName");
            assertThat(types(contactName)).containsExactly("required", "maxLength");
            assertThat(rule(contactName, "maxLength").value()).isEqualTo(CompanyConstraints.CONTACT_NAME_MAX);
        }

        // 레퍼런스 안의 이미지 URL 목록 - 리스트 안의 객체 안의 리스트다. 재귀가 두 단계 이상 내려가는지.
        @Test
        @DisplayName("리스트 안의 객체가 다시 리스트를 가져도 끝까지 내려간다")
        void recursesThroughTwoLevels() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            FieldConstraints imageUrls = field(field(response, "references").items(), "imageUrls");

            assertThat(rule(imageUrls, "maxItems").value())
                    .isEqualTo(CompanyConstraints.REFERENCE_IMAGES_MAX);
            assertThat(rule(imageUrls.items(), "maxLength").value()).isEqualTo(CompanyConstraints.URL_MAX);
        }

        // 선택지 목록은 제약이 아니라 옵션 조회 API 의 몫이다. 여기에 없다는 것 자체가 계약이다.
        @Test
        @DisplayName("제약이 없는 필드(enum 등)는 목록에 나오지 않는다")
        void unconstrainedFieldsAreAbsent() {
            FormConstraintsResponse response = reader.read(ConstraintForm.COMPANY_CREATE);

            assertThat(response.fields().stream().map(FieldConstraints::field))
                    .doesNotContain("trlLevel", "pricingType", "visibility");
        }
    }

    @Nested
    @DisplayName("폼 선택")
    class Forms {

        // 수정은 sparse PATCH 라 필수 규칙이 없다(null = 미변경). 프론트가 등록 폼의 제약을 수정 화면에
        // 재사용하면 필수가 아닌 칸을 필수로 막게 된다.
        @Test
        @DisplayName("수정 폼은 등록 폼과 다른 규칙을 준다")
        void updateFormDiffersFromCreate() {
            FormConstraintsResponse update = reader.read(ConstraintForm.COMPANY_UPDATE);
            FormConstraintsResponse create = reader.read(ConstraintForm.COMPANY_CREATE);

            // 안 보내면 미변경이므로 필수가 아니다. 대신 빈 문자열로 지울 수는 없다(min 1).
            assertThat(types(field(update, "companyName"))).containsExactly("minLength", "maxLength");
            assertThat(types(field(create, "companyName"))).containsExactly("required", "maxLength");

            // 수정 폼에는 필수 규칙이 하나도 없다.
            assertThat(allTypes(update)).doesNotContain("required");
        }

        // 자가등록은 신원 3종을 아예 받지 않는다(선행 인증이 채운다). 폼에 칸이 없으니 제약도 없어야 한다.
        @Test
        @DisplayName("자가등록 폼에는 신원 3종 칸이 없다")
        void selfRegistrationOmitsIdentityFields() {
            FormConstraintsResponse response = reader.read(ConstraintForm.MY_COMPANY_CREATE);

            assertThat(response.fields().stream().map(FieldConstraints::field))
                    .doesNotContain("businessNumber", "ceoName", "establishmentDate");
        }

        // 임의의 클래스를 리플렉션으로 열어보는 창구가 되지 않도록 화이트리스트만 연다.
        @Test
        @DisplayName("등록되지 않은 폼 이름은 404 다")
        void unknownFormIsNotFound() {
            assertThatThrownBy(() -> ConstraintForm.of("company-secret"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(CommonErrorCode.NOT_FOUND);
        }

        // 가입 화면은 "10자 이상, 특수문자 1개 포함" 안내 문구를 어차피 화면에 적어야 한다.
        // API 로 한 번 더 주면 같은 사실이 두 곳에 사는 것이라 일부러 열지 않았다.
        @Test
        @DisplayName("회원가입 폼은 일부러 열지 않는다")
        void signUpFormIsNotExposed() {
            assertThatThrownBy(() -> ConstraintForm.of("sign-up"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ===== 조회 도우미 =====

    private static FieldConstraints field(FormConstraintsResponse response, String name) {
        return field(response.fields(), name);
    }

    private static FieldConstraints field(FieldConstraints parent, String name) {
        return field(parent.fields(), name);
    }

    private static FieldConstraints field(List<FieldConstraints> fields, String name) {
        return fields.stream()
                .filter(field -> name.equals(field.field()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("응답에 " + name + " 필드가 없다"));
    }

    private static ConstraintRule rule(FieldConstraints field, String type) {
        return field.rules().stream()
                .filter(rule -> type.equals(rule.type()))
                .findFirst()
                .orElse(null);
    }

    private static Object valueOf(FormConstraintsResponse response, String name, String type) {
        return rule(field(response, name), type).value();
    }

    private static List<String> types(FieldConstraints field) {
        return field.rules().stream().map(ConstraintRule::type).toList();
    }

    private static List<String> names(FormConstraintsResponse response) {
        return response.fields().stream().map(FieldConstraints::field).toList();
    }

    // 중첩·원소까지 훑어 응답 전체에 실린 규칙 종류를 모은다. "이 종류는 어디에도 없다" 를 확인하려면
    // 맨 위만 봐서는 안 된다 - 형식 규칙은 contacts[].phone 같은 안쪽에 많다.
    private static List<String> allTypes(FormConstraintsResponse response) {
        return allTypes(response.fields());
    }

    private static List<String> allTypes(List<FieldConstraints> fields) {
        return fields.stream()
                .flatMap(field -> Stream.of(
                                types(field).stream(),
                                allTypes(field.fields()).stream(),
                                (field.items() != null)
                                        ? Stream.concat(types(field.items()).stream(),
                                                allTypes(field.items().fields()).stream())
                                        : Stream.<String>empty())
                        .flatMap(stream -> stream))
                .toList();
    }
}
