package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

import java.time.Instant;

/**
 * Live Activity 가 받는 {@code content-state} 를 만든다.
 *
 * <h2>두 겹이다</h2>
 *
 * <p>Expo Widgets 의 실제 ActivityKit 상태는 지하철 payload 가 아니라 <b>봉투</b>다.
 *
 * <pre>
 * ContentState { name: String, props: String }   ← props 는 JSON 문자열
 * </pre>
 *
 * <p>한 겹으로 보내면 앱 디코딩이 실패하고 갱신이 <b>조용히 무시</b>된다. 실패 응답도 오지
 * 않으므로 서버 쪽에서는 정상으로 보인다 — 그래서 이 모양을 시험으로 고정한다.
 *
 * <h2>단위가 섞여 있다</h2>
 *
 * <p>여기서 만드는 {@code props} 의 시각은 전부 epoch <b>밀리초</b>다. 반면 봉투 밖의
 * {@code aps.timestamp} 는 epoch <b>초</b>다. 한쪽을 다른 쪽에 넣으면 1000배 어긋나는데,
 * 오류가 아니라 <b>그럴듯하게 틀린 화면</b>으로 나타난다.
 *
 * <h2>{@code null} 을 넣지 않는다</h2>
 *
 * <p>{@code props} 의 필드 상당수가 앱 쪽에서 non-optional 이다({@code routeName}·
 * {@code nextStationName}·{@code segmentProgress}). 하나라도 비면 디코딩이 통째로 실패한다.
 * 그래서 push 는 <b>열차가 정해져 있고({@code PROVISIONAL}·{@code CONFIRMED}) 링이 있을 때만</b> 나간다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRingPushPayloadFactory {

    /** 앱 위젯이 자기 상태를 알아보는 이름. 바꾸면 그 순간부터 모든 갱신이 무시된다. */
    static final String ACTIVITY_NAME = "NavigationLiveActivity";

    static final String FIELD_NAME = "name";
    static final String FIELD_PROPS = "props";

    private final ObjectMapper objectMapper;

    /**
     * 봉투 한 벌.
     *
     * @param lineName  표시용 노선 이름. 비어 있으면 안 된다
     * @param updatedAt 이 화면을 만든 시각. 앱이 순서를 가리는 보조 축이다
     */
    public ObjectNode contentState(Long journeyId, String lineName, SubwayRingState ring,
                                   Instant updatedAt) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put(FIELD_NAME, ACTIVITY_NAME);
        // props 는 객체가 아니라 문자열이다. 객체로 넣으면 앱이 못 읽는다.
        envelope.put(FIELD_PROPS, toJson(props(journeyId, lineName, ring, updatedAt)));
        return envelope;
    }

    /*
     * 종료 프레임은 여기서 만들지 않는다.
     *
     * 종료는 "마지막으로 보여 준 화면에 시각만 새로 찍은 것"인데, 그 화면은 이미 대기열 행에
     * 들어 있다. 자바로 다시 만들려면 여정마다 그 행을 읽어야 하고, 부르는 자리 중 하나가
     * lease 만료 배치라 여정 수만큼 왕복이 붙는다 — 그 트랜잭션은 문장 하나만 감싸도록
     * 설계된 자리다. 그래서 같은 변환을 SQL 한 문장으로 한다
     * (SubwayRingPushOutboxRepository.enqueueTerminalByJourneyIn).
     *
     * 같은 변환을 두 곳에 두지 않는 것이 요점이다. 두 벌이 되면 한쪽만 고쳐지는 순간부터
     * 종료 프레임과 진행 프레임이 다른 모양으로 나간다.
     */

    private ObjectNode props(Long journeyId, String lineName, SubwayRingState ring,
                             Instant updatedAt) {
        ObjectNode props = objectMapper.createObjectNode();
        props.put("journeyId", journeyId);
        props.put("phase", ring.phase().name());
        props.put("routeName", lineName);
        props.put("nextStationName", ring.nextStationName());
        props.put("alightingStationName", ring.alightingStationName());
        props.put("remainingStations", ring.remainingStations());
        props.put("segmentProgress", ring.segmentProgress());
        props.put("progressBaseAt", millis(ring.progressBaseAt()));
        props.put("progressTarget", ring.progressTarget());
        // 없으면 null 이다. 앱은 그때 보간을 멈춘다 — 만들어 내면 죽은 화면이 살아 있는 척한다.
        props.put("estimatedNextStationAt", millis(ring.estimatedNextStationAt()));
        props.put("progressValidUntil", millis(ring.progressValidUntil()));
        props.put("quality", ring.quality().name());
        props.put("updatedAt", updatedAt.toEpochMilli());
        return props;
    }

    /** epoch <b>밀리초</b>. 봉투 밖 {@code aps.timestamp} 는 초라는 것을 잊지 말 것. */
    private Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private String toJson(ObjectNode props) {
        try {
            return objectMapper.writeValueAsString(props);
        } catch (Exception e) {
            throw new IllegalStateException("Live Activity props 직렬화 실패", e);
        }
    }
}
