package project.plantly.domain.user.withdrawal;

import project.plantly.domain.auth.social.SocialProvider;

// 탈퇴 확인 화면(미리보기) 응답.
//  - ownedCompanyCount: 함께 삭제될 등록 기업 수. 이미 삭제된 기업은 세지 않는다(화면에 보이는 기업 기준).
//  - draftCount: 함께 삭제될 임시저장 초안 수.
//  - provider: 본인 확인(재인가)에 쓸 소셜 제공자. 화면이 "네이버로 본인 확인" 같은 버튼 문구를 고른다.
public record WithdrawalPreview(long ownedCompanyCount, long draftCount, SocialProvider provider) {
}
