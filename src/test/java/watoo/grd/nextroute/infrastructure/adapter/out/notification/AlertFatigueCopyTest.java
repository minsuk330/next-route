package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 악화 알림 피로 정책(#428 PR-2)의 문구 — "상태"가 아니라 "할 일"을 말한다.
 *
 * <p>조용히 망가지는 것이 셋이다. 정책을 껐는데 문구가 바뀌면 운영 알림이 예고 없이 달라지고, 분을 적재 때
 * 박으면 합치기 창만큼 낡은 숫자가 나가고, 놓친 차의 시각을 "다음 차"로 적으면 떠난 차를 기다리게 한다.
 */
@DisplayName("악화 알림 피로 정책 — 할 일 문구")
class AlertFatigueCopyTest {

    private static final Instant NOW = Instant.parse("2026-10-15T05:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JourneyNotificationProperties properties = new JourneyNotificationProperties();
    private final FcmMessageFactory factory = new FcmMessageFactory(properties);

    private static NotificationSendCommand command(JourneyCheckpoint checkpoint, ObjectNode payload) {
        JourneyNotificationScope scope = checkpoint == JourneyCheckpoint.REROUTE_SUGGESTED
                ? JourneyNotificationScope.JOURNEY : JourneyNotificationScope.TRANSFER;
        return new NotificationSendCommand(7L, 42L, 11L, 3, scope, 0, checkpoint,
                NotificationChannel.PUSH, 5L, 9L, payload, NOW.plusSeconds(600));
    }

    /** 문구 재료를 전부 실은 payload. 정책이 꺼지면 이 재료가 있어도 문구가 그대로여야 한다. */
    private static ObjectNode richPayload() {
        return MAPPER.createObjectNode()
                .put("status", "AT_RISK")
                .put("routeName", "2호선")
                .put("nextBusArrivalAt", NOW.plusSeconds(180).toString())
                .put("replacedRisk", "MISSED")
                .put("delivery", "ALERTING");
    }

    private PushMessage send(JourneyCheckpoint checkpoint, ObjectNode payload, Instant now) {
        return factory.from(command(checkpoint, payload), now);
    }

    @Nested
    @DisplayName("정책 끔")
    class Off {

        /** PR-2 이전의 확정 문구. 여기 값이 바뀌면 이 PR 이 운영 문구를 건드린 것이다. */
        private static final Map<JourneyCheckpoint, String[]> TODAY = Map.of(
                JourneyCheckpoint.TRANSFER_RISK_AT_RISK, new String[]{"환승 시간이 빠듯해요", "서두르면 다음 차를 탈 수 있어요."},
                JourneyCheckpoint.TRANSFER_RISK_MISSED, new String[]{"환승을 놓쳤어요", "앱에서 새 안내를 확인하세요."},
                JourneyCheckpoint.TRANSFER_TARGET_APPROACHING, new String[]{"갈아탈 차가 곧 도착해요", "탑승 준비를 하세요."},
                JourneyCheckpoint.REROUTE_SUGGESTED, new String[]{"더 빠른 길이 있어요", "앱에서 새 경로를 확인하세요."});

        @ParameterizedTest
        @EnumSource(JourneyCheckpoint.class)
        @DisplayName("재료가 다 있어도 모든 체크포인트의 제목·본문이 지금과 바이트까지 같다")
        void identicalForEveryCheckpoint(JourneyCheckpoint checkpoint) {
            PushMessage message = send(checkpoint, richPayload(), NOW);

            assertThat(message.title()).isEqualTo(TODAY.get(checkpoint)[0]);
            assertThat(message.body()).isEqualTo(TODAY.get(checkpoint)[1]);
        }

        @Test
        @DisplayName("보드 push 의 행동 문구도 그대로다 — replacedRisk 가 있어도 제목은 '더 빠른 길이 있어요'")
        void boardPushUnchanged() {
            ObjectNode payload = richPayload().put("stopName", "강남").put("etaGainSec", 240)
                    .put("originType", "RIDE_STOP");

            PushMessage message = send(JourneyCheckpoint.REROUTE_SUGGESTED, payload, NOW);

            assertThat(message.title()).isEqualTo("더 빠른 길이 있어요");
            assertThat(message.body()).isEqualTo("강남에서 내려 2호선 타면 4분 빨라요");
        }
    }

    @Nested
    @DisplayName("정책 켬")
    class On {

        private void enable() {
            properties.getFatigue().setEnabled(true);
        }

        @Test
        @DisplayName("빠듯: '2호선이 3분 뒤 와요 — 서두르면 탈 수 있어요'")
        void atRisk() {
            enable();
            PushMessage message = send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload(), NOW);

            assertThat(message.title()).isEqualTo("환승 시간이 빠듯해요");
            assertThat(message.body()).isEqualTo("2호선이 3분 뒤 와요 — 서두르면 탈 수 있어요");
        }

        @Test
        @DisplayName("빠듯: 분은 보낸 순간 기준이다 — 같은 행이 2분 늦게 나가면 '1분 뒤'")
        void atRiskMinutesAtSendTime() {
            enable();
            PushMessage message = send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload(), NOW.plusSeconds(120));

            assertThat(message.body()).isEqualTo("2호선이 1분 뒤 와요 — 서두르면 탈 수 있어요");
        }

        @Test
        @DisplayName("빠듯: 30초 이하·이미 지남은 '곧 와요'")
        void atRiskSoon() {
            enable();
            PushMessage soon = send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload(), NOW.plusSeconds(150));
            PushMessage past = send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload(), NOW.plusSeconds(400));

            assertThat(soon.body()).isEqualTo("2호선이 곧 와요 — 서두르면 탈 수 있어요");
            assertThat(past.body()).isEqualTo("2호선이 곧 와요 — 서두르면 탈 수 있어요");
        }

        @Test
        @DisplayName("빠듯: 조사는 노선 이름 끝 받침을 따른다 — '146번이'·'M4403이'·'7724가'")
        void atRiskParticle() {
            enable();
            assertThat(send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload().put("routeName", "146번"), NOW).body())
                    .startsWith("146번이 3분 뒤");
            assertThat(send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload().put("routeName", "M4403"), NOW).body())
                    .startsWith("M4403이 3분 뒤");
            assertThat(send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload().put("routeName", "7724"), NOW).body())
                    .startsWith("7724가 3분 뒤");
        }

        @Test
        @DisplayName("빠듯: 노선 이름이나 도착 시각이 없으면 기존 문구")
        void atRiskFallback() {
            enable();
            ObjectNode noRoute = richPayload();
            noRoute.remove("routeName");
            ObjectNode noArrival = richPayload();
            noArrival.remove("nextBusArrivalAt");
            ObjectNode badArrival = richPayload().put("nextBusArrivalAt", "not-a-time");

            for (ObjectNode payload : new ObjectNode[]{noRoute, noArrival, badArrival}) {
                PushMessage message = send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, payload, NOW);
                assertThat(message.title()).isEqualTo("환승 시간이 빠듯해요");
                assertThat(message.body()).isEqualTo("서두르면 다음 차를 탈 수 있어요.");
            }
            NotificationSendCommand nullPayload = new NotificationSendCommand(7L, 42L, 11L, 3,
                    JourneyNotificationScope.TRANSFER, 0, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                    NotificationChannel.PUSH, 5L, 9L, null, NOW.plusSeconds(600));
            assertThat(factory.from(nullPayload, NOW).body()).isEqualTo("서두르면 다음 차를 탈 수 있어요.");
        }

        @Test
        @DisplayName("놓침(대안 없음): '2호선을 놓쳤어요 — …' 이고 놓친 차의 시각은 적지 않는다")
        void missed() {
            enable();
            PushMessage message = send(JourneyCheckpoint.TRANSFER_RISK_MISSED, richPayload(), NOW);

            assertThat(message.title()).isEqualTo("환승을 놓쳤어요");
            assertThat(message.body()).isEqualTo("2호선을 놓쳤어요 — 눌러서 다음 차와 다른 길을 확인하세요");
            assertThat(message.body()).doesNotContain("분", ":");
        }

        @Test
        @DisplayName("놓침: 받침 없는 노선은 '를' — '7724를 놓쳤어요'")
        void missedParticle() {
            enable();
            assertThat(send(JourneyCheckpoint.TRANSFER_RISK_MISSED, richPayload().put("routeName", "7724"), NOW).body())
                    .startsWith("7724를 놓쳤어요");
        }

        @Test
        @DisplayName("놓침: 노선 이름이 없으면 기존 문구")
        void missedFallback() {
            enable();
            ObjectNode payload = richPayload();
            payload.remove("routeName");

            PushMessage message = send(JourneyCheckpoint.TRANSFER_RISK_MISSED, payload, NOW);

            assertThat(message.title()).isEqualTo("환승을 놓쳤어요");
            assertThat(message.body()).isEqualTo("앱에서 새 안내를 확인하세요.");
        }

        @Test
        @DisplayName("위험을 대신한 보드 push: 제목이 대체된 위험을 전하고 본문은 대안 행동")
        void boardReplacingRisk() {
            enable();
            ObjectNode missed = MAPPER.createObjectNode().put("routeName", "7호선").put("stopName", "고속터미널")
                    .put("etaGainSec", 240).put("originType", "RIDE_STOP").put("replacedRisk", "MISSED");
            ObjectNode atRisk = missed.deepCopy().put("replacedRisk", "AT_RISK");

            PushMessage m = send(JourneyCheckpoint.REROUTE_SUGGESTED, missed, NOW);
            PushMessage a = send(JourneyCheckpoint.REROUTE_SUGGESTED, atRisk, NOW);

            assertThat(m.title()).isEqualTo("환승을 놓쳤어요");
            assertThat(m.body()).isEqualTo("고속터미널에서 내려 7호선 타면 4분 빨라요");
            assertThat(a.title()).isEqualTo("환승 시간이 빠듯해요");
            assertThat(a.body()).isEqualTo("고속터미널에서 내려 7호선 타면 4분 빨라요");
        }

        @Test
        @DisplayName("대신한 위험이 없는 보드 push 는 지금 제목 '더 빠른 길이 있어요'")
        void boardWithoutReplacedRisk() {
            enable();
            ObjectNode payload = MAPPER.createObjectNode().put("routeName", "7호선").put("etaGainSec", 240);

            PushMessage message = send(JourneyCheckpoint.REROUTE_SUGGESTED, payload, NOW);

            assertThat(message.title()).isEqualTo("더 빠른 길이 있어요");
            assertThat(message.body()).isEqualTo("7호선(으)로 가면 4분 빨라요");
        }

        @Test
        @DisplayName("접근 알림은 정책 대상이 아니라 재료가 있어도 그대로")
        void approachingUnchanged() {
            enable();
            PushMessage message = send(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING, richPayload(), NOW);

            assertThat(message.title()).isEqualTo("갈아탈 차가 곧 도착해요");
            assertThat(message.body()).isEqualTo("탑승 준비를 하세요.");
        }

        @Test
        @DisplayName("숫자·노선 이름은 문구에만 — push data 는 식별자뿐")
        void dataStaysIdentifiersOnly() {
            enable();
            PushMessage message = send(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, richPayload(), NOW);

            assertThat(message.data()).doesNotContainKeys("routeName", "nextBusArrivalAt", "replacedRisk", "status");
        }
    }

    @ParameterizedTest(name = "{0}초 → {1}")
    @CsvSource({
            "-60, 곧 와요",
            "0, 곧 와요",
            "29, 곧 와요",
            "30, 곧 와요",
            "31, 1분 뒤 와요",
            "89, 1분 뒤 와요",
            "90, 2분 뒤 와요",
            "180, 3분 뒤 와요",
            "209, 3분 뒤 와요",
            "210, 4분 뒤 와요"
    })
    @DisplayName("분 반올림: 30초 이하 '곧', 그 위는 반올림·최소 1분")
    void minuteRounding(long seconds, String expected) {
        assertThat(FcmMessageFactory.untilArrival(NOW, NOW.plusSeconds(seconds))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}/{2}")
    @CsvSource({
            "2호선, 이, 을",
            "146번, 이, 을",
            "버스, 가, 를",
            "M4403, 이, 을",
            "7724, 가, 를",
            "100, 이, 을",
            "6001, 이, 을",
            "N26, 이, 을",
            "M, 이, 을",
            "L, 이, 을",
            "R, 이, 을",
            "n, 이, 을",
            "A, 가, 를",
            "G, 가, 를",
            "x, 가, 를",
            "'서울2호선(본선)', 이, 을",
            "'오이도 ', 가, 를",
            "1, 이, 을",
            "3, 이, 을",
            "6, 이, 을",
            "7, 이, 을",
            "8, 이, 을",
            "2, 가, 를",
            "4, 가, 를",
            "5, 가, 를",
            "9, 가, 를"
    })
    @DisplayName("조사: 마지막으로 읽히는 글자의 받침")
    void particleTable(String word, String subject, String object) {
        assertThat(KoreanParticles.subject(word)).isEqualTo(subject);
        assertThat(KoreanParticles.object(word)).isEqualTo(object);
    }

    @Test
    @DisplayName("조사: 읽을 글자가 없으면 둘 다 적는다")
    void particleUnknown() {
        assertThat(KoreanParticles.subject("()")).isEqualTo("이(가)");
        assertThat(KoreanParticles.object(null)).isEqualTo("을(를)");
    }
}
