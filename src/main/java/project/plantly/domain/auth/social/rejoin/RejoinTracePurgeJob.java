package project.plantly.domain.auth.social.rejoin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 제한 기간이 지난 재가입 흔적을 지운다. 처리방침의 "탈퇴 후 24시간 보관 후 파기"를 지키는 장치다.
// 10분마다 돌아서 흔적은 길어야 기간 + 10분 남는다. 삭제는 멱등이라 여러 번 돌아도, 서버가 여럿이어도 문제없다.
@Slf4j
@Component
@RequiredArgsConstructor
public class RejoinTracePurgeJob {

    private final RejoinRestriction rejoinRestriction;

    @Scheduled(cron = "${app.rejoin.purge-cron:0 */10 * * * *}")
    public void purge() {
        int deleted = rejoinRestriction.purgeExpired();
        if (deleted > 0) {
            log.info("기간이 지난 재가입 흔적 {}건을 지웠다", deleted);
        }
    }
}
