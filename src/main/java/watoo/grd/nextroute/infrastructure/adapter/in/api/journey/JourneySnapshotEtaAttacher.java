package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.JourneyEventResponse;

/**
 * 여정 스냅샷을 응답으로 내보내기 직전에 예측({@code eta})을 얹는 한 곳(#470).
 *
 * <p><b>앱은 스냅샷이 실린 응답을 받을 때마다 들고 있던 스냅샷을 통째로 갈아끼운다.</b> 예전에는 조회
 * ({@code GET /active}, {@code GET /{journeyId}})만 예측을 얹었다. 앱이 복귀하며 보낸 heartbeat 응답으로
 * 갈아끼우는 순간 도착 예정이 사라졌고, 이어진 조건부 조회는 번호가 같아 204 라 다음 번호가 오를 때까지
 * 돌아오지 않았다. 그래서 스냅샷을 돌려주는 응답은 모두 여기를 거쳐 같은 합성({@link JourneySnapshotEtaComposer})을
 * 탄다.
 *
 * <p><b>컨트롤러에서 부른다.</b> 합성은 트랜잭션 밖에서 해야 한다 — 평가기가 꺼져 있으면 안에서 실시간 도착을
 * 부른다. 유스케이스가 커밋하고 돌아온 뒤가 그 자리다.
 *
 * <p><b>{@code PUT /progress} 만 거치지 않는다.</b> 여정마다 15초(하차 임박 5초)에 한 번 오는 가장 잦은
 * 요청이다. 합성은 얼려 둔 경로를 다시 읽어 쪼개는 좌표 조립(여정·단계·후보·개정본·노선 정류장 쿼리와 경로 원본
 * 역직렬화·재컴파일)에 단계·환승 판정 읽기를 더하고, 평가기를 끄면(되돌리는 스위치) 실시간 도착 API 와 판정
 * 저장까지 붙는다. 그 요청의 응답 시간을 지키려고 좌표 재쪼개기를 일부러 뺀 경로이기도 하다
 * ({@code JourneyProgressService#requireLeg}).
 *
 * <h2>진행 중인 여정의 {@code eta} 가 null 이면</h2>
 * "이 응답은 예측을 계산하지 않았다"는 뜻이다 — 진행 보고 응답, 또는 쓰기 응답에서 합성이 실패한 경우.
 * 앱은 직전 값을 지우지 않는다. "모른다"는 {@code eta} 안의 칸이 null 인 것으로 나타낸다. 끝난 여정은 예측할
 * 것이 없어 늘 null 이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class JourneySnapshotEtaAttacher {

    private final JourneySnapshotEtaComposer etaComposer;

    /**
     * 조회 응답. <b>합성이 실패하면 요청도 실패한다.</b>
     *
     * <p>조회는 아무것도 바꾸지 않았으니 앱이 다시 물으면 된다. 예측 없이 200 을 주면 이어지는 조건부 조회가
     * 같은 번호로 204 를 받아, 예측이 다음 번호가 오를 때까지 돌아오지 않는다.
     */
    JourneySnapshot forQuery(Long userId, JourneySnapshot snapshot) {
        return etaComposer.compose(userId, snapshot);
    }

    /**
     * 쓰기(시작·heartbeat·재탐색 수락·이벤트) 응답. <b>합성이 실패해도 응답은 실패시키지 않는다.</b>
     *
     * <p>쓰기는 이미 커밋됐다. 여기서 5xx 를 주면 앱은 반영된 일을 실패로 읽는다 — 시작이면 다시 눌러
     * 409(진행 중인 여정 있음)를 만난다. 예측 없이 내보내고, 앱은 직전 값을 유지한다.
     */
    JourneySnapshot forWrite(Long userId, JourneySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return etaComposer.compose(userId, snapshot);
        } catch (RuntimeException e) {
            log.warn("[Journey] 쓰기 응답의 예측 합성 실패 — 예측 없이 내보낸다. journeyId={}",
                    snapshot.journeyId(), e);
            return snapshot;
        }
    }

    /** 이벤트 결과. 적용·재전송·충돌·거절 모두 앱이 화면을 맞추는 스냅샷이라 똑같이 얹는다. */
    JourneyEventResponse forEvent(Long userId, JourneyEventOutcome outcome) {
        return JourneyEventResponse.from(new JourneyEventOutcome(
                outcome.result(), outcome.rejectionReason(), forWrite(userId, outcome.journey())));
    }
}
