package project.plantly.companyTest.companyControllerTest;

import io.restassured.filter.cookie.CookieFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.plantly.AcceptanceTest;
import project.plantly.companyTest.support.CompanyAggregateSeeder;
import project.plantly.domain.user.repository.UserRepository;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

// 임시저장(초안) 인수 테스트 — 실제 서버(RANDOM_PORT) + 실 DB(H2) + 실 필터 체인.
// 초안의 본질인 "직렬화 왕복 / 통째 교체(upsert) / 느슨한 저장 / 멱등 폐기" 를 실제로 저장·복원해 검증하고,
// 접근 제어(401/409/남의 인증 400)와 상태 매핑을 실 응답으로 못 박는다.
//
// 초안 키가 (userId, 사업자번호) 라는 사실도 여기서 실 DB 로 확인된다 — 시더가 발급하는 인증은 모두 같은 번호라
// 같은 사용자가 인증을 다시 받아도 같은 초안 행에 이어 쓰게 된다.
//
// 발행(POST /companies, 201) 성공 후 초안 자동 삭제 seam 은 여기서 다루지 않는다 — 등록 경로가 검색 도큐먼트
// 동기화(Postgres 전용 SQL)를 거쳐 H2 인수 프로파일에서 뜨지 않기 때문. 그 seam 은 CompanyServiceTest 가
// draftRepository.deleteByVerificationId 호출로 단위 검증한다. (반대로 @Valid 400 은 서비스 진입 전이라 H2 에서도 안전 → 아래 대비 테스트로 확인)
class CompanyDraftAcceptanceTest extends AcceptanceTest {

    @Autowired
    private CompanyAggregateSeeder seeder;

    @Autowired
    private UserRepository userRepository;

    // 경로 키는 인증 식별자가 아니라 사업자등록번호다 — 인증은 만료·재발급되며 id 가 바뀌지만 작성분은
    // 그 수명에 묶이면 안 되기 때문이다(CompanyDraft 주석). 시더의 인증은 모두 이 번호로 발급된다.
    private static final String DRAFT_PATH = "/api/v1/companies/drafts/{businessNumber}";
    private static final String DRAFTS_PATH = "/api/v1/companies/drafts";
    private static final String BUSINESS_NUMBER = CompanyAggregateSeeder.BUSINESS_NUMBER;
    // seedDeletedCompany 가 쓰는 번호. 삭제 후 재등록 케이스에서 경로 키가 된다.
    private static final String DELETED_BUSINESS_NUMBER = "9998887776";

    @Nested
    @DisplayName("저장·조회 왕복 PUT/GET /api/v1/companies/drafts/{businessNumber}")
    class SaveAndGet {

        @Test
        @DisplayName("저장한 폼 상태(부분 입력)를 그대로 복원하고 마지막 저장 시각을 함께 준다")
        void save_thenGet_restoresPayload() {
            Session owner = signUpMember("draft-owner@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());

            String body = """
                    {"verificationId":%d,"companyName":"작성중회사","introTitle":"한 줄 요약 초안","tagNames":["친환경","B2B"]}
                    """.formatted(vid);
            putDraft(owner, body).then().statusCode(200).body("success", equalTo(true));

            given()
                    .filter(owner.cookies())
                    .when()
                    .get(DRAFT_PATH, BUSINESS_NUMBER)
                    .then()
                    .statusCode(200)
                    .body("success", equalTo(true))
                    .body("data.payload.companyName", equalTo("작성중회사"))
                    .body("data.payload.introTitle", equalTo("한 줄 요약 초안"))
                    .body("data.payload.tagNames[0]", equalTo("친환경"))
                    .body("data.payload.tagNames[1]", equalTo("B2B"))
                    .body("data.updatedAt", notNullValue());
        }

        @Test
        @DisplayName("조회는 이어쓸 인증(식별자·신원·등급·한도)을 함께 준다 — 인증 식별자를 잃은 기기도 재인증 없이 이어 쓴다")
        void get_includesResumableVerification() {
            Session owner = signUpMember("draft-resume@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());
            // payload 에는 낡은 식별자가 들어 있다고 가정한다 — 응답의 verification 이 지금 쓸 값이다
            putDraft(owner, """
                    {"verificationId":%d,"companyName":"이어쓰기"}
                    """.formatted(vid + 1000)).then().statusCode(200);

            given()
                    .filter(owner.cookies())
                    .when()
                    .get(DRAFT_PATH, BUSINESS_NUMBER)
                    .then()
                    .statusCode(200)
                    .body("data.verification.verificationId", equalTo((int) vid))
                    .body("data.verification.businessNumber", equalTo(BUSINESS_NUMBER))
                    .body("data.verification.ceoName", equalTo(CompanyAggregateSeeder.CEO_NAME));
        }

        @Test
        @DisplayName("재저장은 payload 를 통째 교체한다 — 인증당 1개만 유지하며 이전 입력은 남지 않는다(upsert)")
        void save_isFullReplace_notMerge() {
            Session owner = signUpMember("draft-replace@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());

            // 1차: companyName + introTitle
            putDraft(owner, """
                    {"verificationId":%d,"companyName":"1차회사","introTitle":"지워질 요약"}
                    """.formatted(vid)).then().statusCode(200);
            // 2차: companyName 만 (introTitle 없음) → 부분 병합이 아니라 통째 교체여야 한다
            putDraft(owner, """
                    {"verificationId":%d,"companyName":"2차회사"}
                    """.formatted(vid)).then().statusCode(200);

            given()
                    .filter(owner.cookies())
                    .when()
                    .get(DRAFT_PATH, BUSINESS_NUMBER)
                    .then()
                    .statusCode(200)
                    .body("data.payload.companyName", equalTo("2차회사"))
                    .body("data.payload.introTitle", nullValue()); // 1차의 값이 병합되어 남지 않는다
        }

        @Test
        @DisplayName("저장된 초안이 없으면 조회는 404(DRAFT_NOT_FOUND)")
        void get_withoutDraft_isNotFound() {
            Session owner = signUpMember("draft-none@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());

            given()
                    .filter(owner.cookies())
                    .when()
                    .get(DRAFT_PATH, BUSINESS_NUMBER)
                    .then()
                    .statusCode(404)
                    .body("success", equalTo(false));
        }
    }

    @Nested
    @DisplayName("목록 GET /api/v1/companies/drafts")
    class ListDrafts {

        @Test
        @DisplayName("내 초안을 사업자번호·회사명·저장 시각으로 돌려준다 — 남의 초안은 섞이지 않는다")
        void list_returnsOnlyMyDrafts() {
            Session owner = signUpMember("draft-list@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());
            putDraft(owner, "{\"verificationId\":%d,\"companyName\":\"목록회사\"}".formatted(vid))
                    .then().statusCode(200);
            Session other = signUpMember("draft-list-other@example.com");

            given()
                    .filter(owner.cookies())
                    .when()
                    .get(DRAFTS_PATH)
                    .then()
                    .statusCode(200)
                    .body("data.size()", equalTo(1))
                    .body("data[0].businessNumber", equalTo(BUSINESS_NUMBER))
                    .body("data[0].companyName", equalTo("목록회사"))
                    .body("data[0].updatedAt", notNullValue())
                    // 목록은 고르는 화면이라 폼 전체를 싣지 않는다(복원은 단건 조회가 한다).
                    .body("data[0].payload", nullValue());

            given()
                    .filter(other.cookies())
                    .when()
                    .get(DRAFTS_PATH)
                    .then()
                    .statusCode(200)
                    .body("data.size()", equalTo(0));
        }
    }

    @Nested
    @DisplayName("느슨한 저장 vs 엄격한 발행")
    class LooseSaveVsStrictPublish {

        // 같은 '부분 입력' 본문(필수값 companyName 누락)을 두 경로에 보낸다.
        // 초안 저장은 @Valid 가 없어 통과(200)하고, 발행(POST /companies)은 @Valid 로 400 — 계약이 갈리는 지점이다.
        @Test
        @DisplayName("companyName 이 빠진 부분 입력을 초안은 200 으로 저장하고, 발행은 400 으로 거절한다")
        void partialInput_savedAsDraft_butRejectedOnPublish() {
            Session owner = signUpMember("draft-loose@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());

            String partial = """
                    {"verificationId":%d,"introTitle":"아직 회사명 미정","tagNames":["초안"]}
                    """.formatted(vid);

            // 느슨한 저장: 필수값이 없어도 그대로 보관된다
            putDraft(owner, partial).then().statusCode(200).body("success", equalTo(true));

            // 엄격한 발행: 같은 본문을 정식 등록으로 보내면 @NotBlank(companyName) 위반으로 400 (서비스 진입 전이라 H2 에서도 안전)
            given()
                    .filter(owner.cookies())
                    .header("X-XSRF-TOKEN", owner.csrf())
                    .contentType(ContentType.JSON)
                    .body(partial)
                    .when()
                    .post("/api/v1/companies")
                    .then()
                    .statusCode(400)
                    .body("success", equalTo(false));
        }
    }

    @Nested
    @DisplayName("폐기 DELETE /api/v1/companies/drafts/{businessNumber}")
    class Delete {

        @Test
        @DisplayName("폐기하면 이후 조회는 404 가 된다")
        void delete_thenGet_isNotFound() {
            Session owner = signUpMember("draft-del@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());
            putDraft(owner, """
                    {"verificationId":%d,"companyName":"버릴회사"}
                    """.formatted(vid)).then().statusCode(200);

            deleteDraft(owner).then().statusCode(200).body("success", equalTo(true));

            given()
                    .filter(owner.cookies())
                    .when()
                    .get(DRAFT_PATH, BUSINESS_NUMBER)
                    .then()
                    .statusCode(404);
        }

        @Test
        @DisplayName("저장한 적 없는 초안을 폐기해도 멱등하게 200")
        void delete_withoutDraft_isIdempotent() {
            Session owner = signUpMember("draft-del-idem@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());

            deleteDraft(owner).then().statusCode(200).body("success", equalTo(true));
        }
    }

    @Nested
    @DisplayName("접근 제어")
    class AccessControl {

        // 경로가 2세그먼트('drafts/{businessNumber}')라 공개 상세(/{id}, permitAll)로 새지 않고 인증 규칙에 걸려야 한다.
        // 여기서 401 이 아니라 404/500 이 나오면 요청이 /{id} 로 샜다는 뜻이다.
        @Test
        @DisplayName("미인증 조회는 401 (공개 상세 /{id} 로 새지 않는다)")
        void get_unauthenticated_isUnauthorized() {
            given() // 세션 없음 = 익명
                    .when()
                    .get(DRAFT_PATH, BUSINESS_NUMBER)
                    .then()
                    .statusCode(401);
        }

        // 목록 경로는 단일 세그먼트('drafts')라 공개 상세(/{id}, permitAll)가 잡는다 — 그보다 앞선 인증 규칙이
        // 실제 필터 체인에서 먹는지 본다. 401 이 아니라 404/500 이면 /{id} 로 샜다는 뜻이다(principal=null → NPE).
        @Test
        @DisplayName("미인증 목록 조회는 401 (공개 상세 /{id} 로 새지 않는다)")
        void list_unauthenticated_isUnauthorized() {
            given() // 세션 없음 = 익명
                    .when()
                    .get(DRAFTS_PATH)
                    .then()
                    .statusCode(401);
        }

        @Test
        @DisplayName("미인증 저장은 401 (CSRF 는 통과시키고 인증에서 막힘)")
        void put_unauthenticated_isUnauthorized() {
            // 익명이라도 CSRF 토큰은 발급받아 실어 보낸다 → CSRF(403)가 아니라 인증(401)에서 막히는 것을 본다.
            CookieFilter anonymous = new CookieFilter();
            String csrf = issueCsrfToken(anonymous);

            given()
                    .filter(anonymous)
                    .header("X-XSRF-TOKEN", csrf)
                    .contentType(ContentType.JSON)
                    .body("{\"verificationId\":1,\"companyName\":\"익명\"}")
                    .when()
                    .put(DRAFT_PATH, BUSINESS_NUMBER)
                    .then()
                    .statusCode(401);
        }

        @Test
        @DisplayName("남의 사업자번호로는 저장·조회·폐기 모두 400(VERIFICATION_NOT_FOUND) — 크로스키 방어")
        void foreignVerification_isRejected() {
            Session owner = signUpMember("draft-owner2@example.com");
            long vid = seeder.seedUsableVerification(owner.userId());
            Session intruder = signUpMember("draft-intruder@example.com");

            // 침입자가 소유자의 사업자번호로 조회 — 그 번호로 국세청을 통과한 이력이 없으므로 초안에 닿지 못한다
            given().filter(intruder.cookies())
                    .when().get(DRAFT_PATH, BUSINESS_NUMBER)
                    .then().statusCode(400).body("success", equalTo(false));

            // 저장
            given().filter(intruder.cookies())
                    .header("X-XSRF-TOKEN", intruder.csrf())
                    .contentType(ContentType.JSON)
                    .body("{\"verificationId\":%d,\"companyName\":\"가로채기\"}".formatted(vid))
                    .when().put(DRAFT_PATH, BUSINESS_NUMBER)
                    .then().statusCode(400);

            // 폐기
            given().filter(intruder.cookies())
                    .header("X-XSRF-TOKEN", intruder.csrf())
                    .when().delete(DRAFT_PATH, BUSINESS_NUMBER)
                    .then().statusCode(400);
        }

        @Test
        @DisplayName("이 번호로 만든 회사가 살아 있으면 저장은 409(VERIFICATION_ALREADY_USED) — 초안이 아니라 수정 대상")
        void consumedVerification_saveRejected() {
            Session owner = signUpMember("draft-consumed@example.com");
            Long companyId = seeder.seedPublishedCompany(owner.userId());
            long vid = seeder.seedConsumedVerification(owner.userId(), companyId);

            putDraft(owner, """
                    {"verificationId":%d,"companyName":"이미소비된인증"}
                    """.formatted(vid))
                    .then()
                    .statusCode(409)
                    .body("success", equalTo(false));
        }

        @Test
        @DisplayName("소비한 회사가 삭제됐으면 같은 번호로 재등록 초안을 저장할 수 있다")
        void consumedByDeletedCompany_saveAllowed() {
            Session owner = signUpMember("draft-reregister@example.com");
            Long deletedId = seeder.seedDeletedCompany(owner.userId());
            long vid = seeder.seedConsumedVerification(owner.userId(), deletedId);

            putDraft(owner, DELETED_BUSINESS_NUMBER, """
                    {"verificationId":%d,"companyName":"재등록"}
                    """.formatted(vid))
                    .then()
                    .statusCode(200);

            given().filter(owner.cookies())
                    .when().get(DRAFT_PATH, DELETED_BUSINESS_NUMBER)
                    .then()
                    .statusCode(200)
                    .body("data.payload.companyName", equalTo("재등록"));
        }
    }

    // ---- helpers ----

    // 세션 쿠키 + 변경요청용 CSRF 토큰 + 유저 id 를 한 묶음으로 들고 다닌다.
    private record Session(CookieFilter cookies, String csrf, long userId) {}

    // 회원가입 + 로그인까지 마친 멤버 세션을 만든다. 로그인이 CSRF 토큰을 회전시키면 새 값을 쓴다(변경요청에 필요).
    private Session signUpMember(String email) {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, email).then().statusCode(201);
        Response loginResponse = login(cookies, csrf, email, VALID_PASSWORD, false);
        loginResponse.then().statusCode(200);
        String rotated = loginResponse.cookie("XSRF-TOKEN");
        long userId = userRepository.findByEmail(email).orElseThrow().getId();
        return new Session(cookies, rotated != null ? rotated : csrf, userId);
    }

    private Response putDraft(Session session, String jsonBody) {
        return putDraft(session, BUSINESS_NUMBER, jsonBody);
    }

    private Response putDraft(Session session, String businessNumber, String jsonBody) {
        return given()
                .filter(session.cookies())
                .header("X-XSRF-TOKEN", session.csrf())
                .contentType(ContentType.JSON)
                .body(jsonBody)
                .when()
                .put(DRAFT_PATH, businessNumber);
    }

    private Response deleteDraft(Session session) {
        return given()
                .filter(session.cookies())
                .header("X-XSRF-TOKEN", session.csrf())
                .when()
                .delete(DRAFT_PATH, BUSINESS_NUMBER);
    }
}
