package project.plantly.globalTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import project.plantly.global.meta.MetaOptionsController;

import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 선택지 카탈로그 엔드포인트의 계약.
 *
 * <p>무엇이 실리고 어떤 순서인지는 {@link OptionCatalogTest} 가 카탈로그 쪽에서 잠근다. 여기서는 그것이
 * 어떤 봉투에 담겨 나가는지만 본다 — 그래서 대역을 두지 않고 실제 카탈로그를 그대로 쓴다(값이 코드 상수라
 * 대역을 세울 이유가 없고, 문서 스니펫도 실제 선택지가 찍혀야 읽힌다).
 */
@ActiveProfiles("test")
@WebMvcTest(controllers = MetaOptionsController.class)
@ExtendWith(RestDocumentationExtension.class)
@DisplayName("선택지 카탈로그 API")
class MetaOptionsControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc(RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withResponseDefaults(prettyPrint()))
                .build();
    }

    @Test
    @DisplayName("고정 선택지를 enum 별로, 선언 순서 그대로 값과 라벨 쌍으로 반환한다")
    void getOptions_success() throws Exception {
        mockMvc.perform(get("/api/v1/meta/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 키는 요청 DTO 의 필드 이름과 같다 — 프론트가 폼의 칸 이름으로 선택지를 찾는다.
                .andExpect(jsonPath("$.data.trlLevel").isArray())
                .andExpect(jsonPath("$.data.pricingType").isArray())
                // 값은 요청·응답에 오가는 enum 이름, 라벨은 화면 문구다.
                .andExpect(jsonPath("$.data.trlLevel[0].value").value("PROTOTYPE"))
                .andExpect(jsonPath("$.data.trlLevel[0].label").value("프로토타입"))
                // 순서는 enum 선언 순서 = 드롭다운 순서(TRL 은 성숙도 오름차순).
                .andExpect(jsonPath("$.data.trlLevel[2].value").value("GLOBAL_STANDARD"))
                .andExpect(jsonPath("$.data.pricingType[1].label").value("상담 후 결정"))
                // 카탈로그에 없는 enum 은 나오지 않는다 — 라벨의 주인이 프론트인 값들(OptionCatalog 주석).
                .andExpect(jsonPath("$.data.certificationType").doesNotExist())
                .andDo(document("meta-options",
                        responseFields(
                                fieldWithPath("success").type(JsonFieldType.BOOLEAN)
                                        .description("요청 성공 여부"),
                                fieldWithPath("data").type(JsonFieldType.OBJECT)
                                        .description("선택지 카탈로그. 키는 요청 DTO 의 필드 이름과 같아서, 제약 조회의 "
                                                + "fields[].field · 검증 실패의 errors[].field 와 같은 어휘로 이어 붙일 수 있다. "
                                                + "사용자가 고르지 않는 값(인증그룹·대륙)은 여기 실리지 않는다"),
                                fieldWithPath("data.trlLevel").type(JsonFieldType.ARRAY)
                                        .description("기술성숙도 선택지. 성숙도 오름차순이라 이 순서가 곧 노출 순서다"),
                                fieldWithPath("data.trlLevel[].value").type(JsonFieldType.STRING)
                                        .description("요청·응답에 오가는 값. 등록/수정 요청의 trlLevel 에 이 값을 담는다"),
                                fieldWithPath("data.trlLevel[].label").type(JsonFieldType.STRING)
                                        .description("화면에 그대로 노출하는 문구. 프론트가 따로 지어내지 않는다"),
                                fieldWithPath("data.pricingType").type(JsonFieldType.ARRAY)
                                        .description("견적 산출방식 선택지"),
                                fieldWithPath("data.pricingType[].value").type(JsonFieldType.STRING)
                                        .description("요청·응답에 오가는 값"),
                                fieldWithPath("data.pricingType[].label").type(JsonFieldType.STRING)
                                        .description("화면에 그대로 노출하는 문구")
                        )));
    }
}
