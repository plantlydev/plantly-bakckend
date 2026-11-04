package project.plantly.companyTest.companyControllerTest;

import io.restassured.filter.cookie.CookieFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import project.plantly.AcceptanceTest;
import project.plantly.companyTest.support.CompanyAggregateSeeder;
import project.plantly.domain.user.User;
import project.plantly.domain.user.enums.UserRole;
import project.plantly.domain.user.enums.UserStatus;
import project.plantly.domain.user.repository.UserRepository;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

// 회사 조회 API 인수 테스트 — 실제 서버(RANDOM_PORT) + 실 DB + 실 필터 체인.
// CompanyAggregateLoader 가 조립한 '진짜' Response 내용, 접근 제어(공개/소유자/관리자), 상태 매핑(404/403/401)을 검증한다.
// 응답 필드의 값 단언은 이 인수 레벨에서 한 번만 한다(슬라이스는 REST Docs 문서화 + 보안 게이트 담당).
class CompanyQueryAcceptanceTest extends AcceptanceTest {

    @Autowired
    private CompanyAggregateSeeder seeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Nested
    @DisplayName("공개 조회 GET /api/v1/companies/{id}")
    class PublicView {

        @Test
        @DisplayName("익명 사용자도 200 으로 조립된 공개 프로필을 받는다 (내부·운영 정보는 노출되지 않는다)")
        void anonymous_returnsAssembledPublicProfile() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner@example.com");
            long companyId = seeder.seedPublishedCompany(ownerId);

            given() // 세션 없음 = 익명
                    .when()
                    .get("/api/v1/companies/{id}", companyId)
                    .then()
                    .statusCode(200)
                    .body("success", equalTo(true))
                    .body("data.id", equalTo((int) companyId))
                    .body("data.companyName", equalTo(CompanyAggregateSeeder.COMPANY_NAME))
                    .body("data.ceoName", equalTo(CompanyAggregateSeeder.CEO_NAME))
                    .body("data.verified", equalTo(true))
                    .body("data.featured", equalTo(true))
                    // 부속: 대표 연락처 / 대표 레퍼런스 + 표지 썸네일 1장 / 갤러리 2장 / 소재·링크 마스터
                    .body("data.representativeContact.contactName", equalTo(CompanyAggregateSeeder.REP_CONTACT_NAME))
                    .body("data.representativeReference.projectTitle", equalTo(CompanyAggregateSeeder.REP_REFERENCE_TITLE))
                    .body("data.representativeReference.thumbnailUrl", equalTo(CompanyAggregateSeeder.REP_THUMBNAIL_URL))
                    .body("data.galleryImages.size()", equalTo(2))
                    .body("data.materialNames", hasItem(CompanyAggregateSeeder.MATERIAL_NAME))
                    .body("data.categories[0].categoryName", equalTo(CompanyAggregateSeeder.CATEGORY_NAME))
                    .body("data.industries[0].industryName", equalTo(CompanyAggregateSeeder.INDUSTRY_NAME))
                    // 공개 경계: 내부·운영 필드는 공개 응답에 존재하지 않는다
                    .body("data.businessNumber", nullValue())
                    .body("data.meta", nullValue());
        }

        @Test
        @DisplayName("소프트 삭제된 회사는 공개 조회에서 404 로 미존재 취급한다")
        void deletedCompany_isNotFound() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner-del@example.com");
            long deletedId = seeder.seedDeletedCompany(ownerId);

            given()
                    .when()
                    .get("/api/v1/companies/{id}", deletedId)
                    .then()
                    .statusCode(404)
                    .body("success", equalTo(false))
                    .body("error", notNullValue());
        }

        @Test
        @DisplayName("존재하지 않는 회사 id 는 404 를 반환한다")
        void unknownId_isNotFound() {
            given()
                    .when()
                    .get("/api/v1/companies/{id}", 999999)
                    .then()
                    .statusCode(404)
                    .body("success", equalTo(false));
        }
    }

    // 동영상에는 노출 게이트가 없다. 저장된 값이 공개 조회에 그대로 나와야 한다
    // (예전에는 등급에 따라 가렸으나 등급 제도가 폐지되었다).
    @Test
    @DisplayName("공개 조회는 저장된 videoUrl 을 그대로 내려준다")
    void publicView_showsStoredVideo() {
        CookieFilter owner = new CookieFilter();
        long ownerId = signUpMember(owner, "owner-video-free@example.com");
        long companyId = seeder.seedPublishedCompany(ownerId);

        given()
                .when()
                .get("/api/v1/companies/{id}", companyId)
                .then()
                .statusCode(200)
                .body("data.videoUrl", equalTo(CompanyAggregateSeeder.VIDEO_URL));
    }

    @Nested
    @DisplayName("소유자 전용 조회 GET /api/v1/companies/{id}/private")
    class OwnerView {

        @Test
        @DisplayName("회사 멤버 본인은 profile + 내부·운영 meta 를 받는다")
        void member_receivesProfileAndMeta() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner-priv@example.com");
            long companyId = seeder.seedPublishedCompany(ownerId);

            given()
                    .filter(owner)
                    .when()
                    .get("/api/v1/companies/{id}/private", companyId)
                    .then()
                    .statusCode(200)
                    .body("success", equalTo(true))
                    .body("data.profile.companyName", equalTo(CompanyAggregateSeeder.COMPANY_NAME))
                    .body("data.meta.businessNumber", equalTo(CompanyAggregateSeeder.BUSINESS_NUMBER))
                    .body("data.meta.registrationSource", equalTo("USER"))
                    .body("data.meta.ownerUserId", equalTo((int) ownerId))
                    .body("data.meta.claimed", equalTo(true))
                    .body("data.meta.deleted", equalTo(false));
        }

        @Test
        @DisplayName("수정 화면 재료: 우편번호와 대표 레퍼런스 이미지 전부를 meta 로 받는다(profile 은 표지 한 장)")
        void member_receivesEditOnlyFields() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner-edit-fields@example.com");
            long companyId = seeder.seedPublishedCompany(ownerId);

            given()
                    .filter(owner)
                    .when()
                    .get("/api/v1/companies/{id}/private", companyId)
                    .then()
                    .statusCode(200)
                    .body("data.meta.postalCode", equalTo("06236"))
                    // 레퍼런스 수정은 전체 교체라, 여기 두 장을 다 받아야 저장할 때 두 번째 장이 지워지지 않는다.
                    .body("data.meta.representativeReferenceImages.size()", equalTo(2))
                    .body("data.meta.representativeReferenceImages[0].imageUrl",
                            equalTo(CompanyAggregateSeeder.REP_THUMBNAIL_URL))
                    .body("data.meta.representativeReferenceImages[1].imageUrl",
                            equalTo("https://cdn.plantly.test/ref/2.png"))
                    .body("data.meta.representativeReferenceImages[1].imageType", equalTo("PROJECT"))
                    .body("data.profile.representativeReference.thumbnailUrl",
                            equalTo(CompanyAggregateSeeder.REP_THUMBNAIL_URL));
        }

        @Test
        @DisplayName("공개 조회에는 우편번호·레퍼런스 이미지 목록이 나가지 않는다")
        void publicView_hasNoEditOnlyFields() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner-edit-fields-pub@example.com");
            long companyId = seeder.seedPublishedCompany(ownerId);

            given()
                    .when()
                    .get("/api/v1/companies/{id}", companyId)
                    .then()
                    .statusCode(200)
                    .body("data", not(hasKey("postalCode")))
                    .body("data", not(hasKey("meta")))
                    .body("data.representativeReference", not(hasKey("imageUrls")));
        }

        @Test
        @DisplayName("회사 멤버가 아닌 다른 유저가 호출하면 403(COMPANY_ACCESS_DENIED)")
        void nonMember_isForbidden() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner-x@example.com");
            long companyId = seeder.seedPublishedCompany(ownerId);

            CookieFilter intruder = new CookieFilter();
            signUpMember(intruder, "intruder@example.com");

            given()
                    .filter(intruder)
                    .when()
                    .get("/api/v1/companies/{id}/private", companyId)
                    .then()
                    .statusCode(403)
                    .body("success", equalTo(false));
        }

        @Test
        @DisplayName("미인증 상태로 호출하면 401")
        void unauthenticated_isUnauthorized() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner-unauth@example.com");
            long companyId = seeder.seedPublishedCompany(ownerId);

            given() // 세션 없음
                    .when()
                    .get("/api/v1/companies/{id}/private", companyId)
                    .then()
                    .statusCode(401);
        }

        @Test
        @DisplayName("소프트 삭제된 회사도 소유자에게는 200 으로 보인다")
        void deletedCompany_stillVisibleToOwner() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "owner-deleted@example.com");
            long deletedId = seeder.seedDeletedCompany(ownerId);

            given()
                    .filter(owner)
                    .when()
                    .get("/api/v1/companies/{id}/private", deletedId)
                    .then()
                    .statusCode(200)
                    .body("data.meta.deleted", equalTo(true));
        }
    }

    @Nested
    @DisplayName("관리자 조회 GET /api/v1/admin/companies/{id}")
    class AdminView {

        @Test
        @DisplayName("관리자는 소유자 미연동(unclaimed) 회사도 profile + meta 전체를 받는다")
        void admin_receivesUnclaimedCompany() {
            long adminId = createAdminUser("admin@example.com");
            CookieFilter admin = loginExisting("admin@example.com");
            long companyId = seeder.seedAdminRegisteredCompany(adminId);

            given()
                    .filter(admin)
                    .when()
                    .get("/api/v1/admin/companies/{id}", companyId)
                    .then()
                    .statusCode(200)
                    .body("success", equalTo(true))
                    .body("data.profile.companyName", equalTo("관리자등록회사"))
                    .body("data.meta.registrationSource", equalTo("ADMIN"))
                    .body("data.meta.claimed", equalTo(false))
                    .body("data.meta.ownerUserId", nullValue())
                    .body("data.meta.registeredBy", equalTo((int) adminId));
        }

        @Test
        @DisplayName("관리자가 아닌 유저가 호출하면 @PreAuthorize 가 막아 403")
        void nonAdmin_isForbidden() {
            CookieFilter owner = new CookieFilter();
            long ownerId = signUpMember(owner, "member@example.com");
            long companyId = seeder.seedPublishedCompany(ownerId);

            given()
                    .filter(owner) // 일반 멤버 세션
                    .when()
                    .get("/api/v1/admin/companies/{id}", companyId)
                    .then()
                    .statusCode(403)
                    .body("success", equalTo(false));
        }

        @Test
        @DisplayName("미인증 상태로 호출하면 401")
        void unauthenticated_isUnauthorized() {
            given()
                    .when()
                    .get("/api/v1/admin/companies/{id}", 1)
                    .then()
                    .statusCode(401);
        }

        // 관리자 목록의 권한 게이트(@PreAuthorize)는 컨트롤러 진입 전에 막으므로 H2 에서도 안전하다.
        // (실제 목록 집계·필터링은 Postgres 전용 SQL 이라 AdminCompanyCardRepositoryTest 가 담당한다.)
        @Test
        @DisplayName("목록: 관리자가 아닌 유저가 호출하면 403")
        void list_nonAdmin_isForbidden() {
            CookieFilter owner = new CookieFilter();
            signUpMember(owner, "list-member@example.com");

            given()
                    .filter(owner) // 일반 멤버 세션
                    .when()
                    .get("/api/v1/admin/companies")
                    .then()
                    .statusCode(403)
                    .body("success", equalTo(false));
        }

        @Test
        @DisplayName("목록: 미인증 상태로 호출하면 401")
        void list_unauthenticated_isUnauthorized() {
            given()
                    .when()
                    .get("/api/v1/admin/companies")
                    .then()
                    .statusCode(401);
        }
    }

    @Nested
    @DisplayName("내 회사 목록 GET /api/v1/companies/my")
    class MyCompanies {

        // 공개 상세(/{id} permitAll)가 단일 세그먼트 'my' 도 잡으므로, 그보다 앞선 인증 규칙이 실제로 먹는지 검증한다.
        // (인증된 본인 소유 회사 집계·필터는 Postgres 전용 SQL 이라 OwnedCompanyCardRepositoryTest 가 담당한다.)
        @Test
        @DisplayName("미인증 상태로 호출하면 401 (공개 상세 /{id} 로 새지 않는다)")
        void unauthenticated_isUnauthorized() {
            given() // 세션 없음 = 익명
                    .when()
                    .get("/api/v1/companies/my")
                    .then()
                    .statusCode(401);
        }
    }

    @Nested
    @DisplayName("내 즐겨찾기 목록 GET /api/v1/companies/favorites")
    class MyFavorites {

        // 'my' 와 같은 이유 — 공개 상세(/{id} permitAll)가 단일 세그먼트 'favorites' 도 잡으므로,
        // 그보다 앞선 인증 규칙이 실제 필터 체인에서 먹는지 검증한다. 여기서 401 이 아니라 404/500 이 나오면
        // 요청이 /{id} 로 샜다는 뜻이다(principal=null → NPE).
        // (즐겨찾기 필터·정렬·집계는 Postgres 전용 SQL 이라 FavoriteCompanyCardRepositoryTest 가 담당한다.)
        @Test
        @DisplayName("미인증 상태로 호출하면 401 (공개 상세 /{id} 로 새지 않는다)")
        void unauthenticated_isUnauthorized() {
            given() // 세션 없음 = 익명
                    .when()
                    .get("/api/v1/companies/favorites")
                    .then()
                    .statusCode(401);
        }
    }

    // 메인 화면 노출 영역(GET /companies/showcase)은 여기서 다루지 않는다 — 카드 프로젝션이
    // Postgres 전용 SQL(array_agg)이라 H2 인수 환경에서 실행되지 않는다. 'my'/'favorites' 와 같은 분업으로,
    // 노출 자격 판정과 최근 등록 레일의 정렬·가시성은 ShowcaseCardRepositoryTest(Testcontainers PG)가,
    // 세 레일의 조립(자리 수·경계 가르기·개인화)은 CompanyShowcaseQueryTest 가, HTTP 계약은 슬라이스 테스트가 맡는다.
    // 익명 허용은 permitAll 이라 여기서 검증할 상태 차이(401)가 없다.

    // ---- helpers ----

    // 회원가입 + 로그인까지 마친 멤버 세션을 cookies 에 채우고, 그 유저의 id 를 반환한다.
    private long signUpMember(CookieFilter cookies, String email) {
        String csrf = issueCsrfToken(cookies);
        signUp(cookies, csrf, email).then().statusCode(201);
        login(cookies, csrf, email, VALID_PASSWORD, false).then().statusCode(200);
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    // ADMIN 권한 유저를 DB 에 직접 저장하고 id 를 반환한다(가입 플로우는 MEMBER 만 만들기 때문).
    private long createAdminUser(String email) {
        User admin = User.builder()
                .email(email)
                .password(passwordEncoder.encode(VALID_PASSWORD))
                .name("관리자")
                .phone("01000000000")
                .userStatus(UserStatus.ACTIVE)
                .userRole(UserRole.ADMIN)
                .build();
        return userRepository.save(admin).getId();
    }

    // 이미 저장된 유저로 로그인해 세션 쿠키를 반환한다.
    private CookieFilter loginExisting(String email) {
        CookieFilter cookies = new CookieFilter();
        String csrf = issueCsrfToken(cookies);
        login(cookies, csrf, email, VALID_PASSWORD, false).then().statusCode(200);
        return cookies;
    }
}
