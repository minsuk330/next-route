package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPushReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 이번 링 갱신을 <b>원격으로도</b> 보낼지 정한다. 순수 함수다.
 *
 * <h2>보내는 기준이 링 계산과 다르다</h2>
 *
 * <p>링은 5초마다 다시 계산되지만 대부분의 tick 은 화면을 바꾸지 않는다. 서버가 앵커만 보내고
 * 앱이 보간하는 구조를 골랐으므로, <b>앵커가 그대로면 보낼 이유가 없다</b>.
 *
 * <h2>signature 가 무엇을 같다고 보는가</h2>
 *
 * <p>정확한 시각까지 넣으면 재앵커 때마다 값이 바뀌어 결국 15초마다 push 하게 된다. 그래서
 * 시각은 <b>구간으로 뭉개고</b> 진행률도 눈금으로 끊는다 — 그 정도 차이는 앱이 그리는 직선을
 * 사실상 바꾸지 않는다.
 *
 * <p>반대로 <b>중요 이벤트는 뭉개지 않는다</b>. 다음 역·국면·신선도는 값 그대로 들어간다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRingPushDecider {

    /** 도착 예정 시각을 이 초 단위로 뭉갠다. 이보다 작은 변화는 화면에서 구분되지 않는다. */
    private static final long ETA_BUCKET_SECONDS = 30;

    /** 진행률 눈금. 0.05 = 링 둘레의 5%. */
    private static final BigDecimal PROGRESS_BUCKET = new BigDecimal("0.05");

    private final JourneySubwayRingProperties properties;

    /**
     * @param previous 직전에 저장돼 있던 링. 없으면 이번이 첫 화면이다
     * @param current  방금 계산한 링
     * @return 보낼 이유. 보낼 것이 없으면 비어 있다
     */
    public Optional<Decision> decide(SubwayRingState previous, SubwayRingState current) {
        if (current == null) {
            return Optional.empty();
        }
        SubwayRingPushReason reason = reasonOf(previous, current);
        if (reason == null) {
            return Optional.empty();
        }
        return Optional.of(new Decision(reason, signatureOf(current), current.updatedAt()));
    }

    /**
     * 종료는 링과 무관하게 언제나 보낸다.
     *
     * <p>여기까지 왔다는 것은 여정이 끝났다는 뜻이고, 안 보내면 잠금화면에 죽은 안내가 남는다.
     */
    public Decision terminal(Instant now) {
        return new Decision(SubwayRingPushReason.END, "end:" + now.toEpochMilli(), now);
    }

    private SubwayRingPushReason reasonOf(SubwayRingState previous, SubwayRingState current) {
        if (previous == null) {
            // 첫 화면. 이것을 놓치면 위젯이 "확인 중"에서 영영 안 벗어난다.
            return SubwayRingPushReason.NEXT_STATION;
        }
        if (!Objects.equals(previous.canonicalNextStationKey(), current.canonicalNextStationKey())
                || previous.nextStationSequence() != current.nextStationSequence()) {
            return SubwayRingPushReason.NEXT_STATION;
        }
        if (previous.phase() != current.phase()) {
            return SubwayRingPushReason.PHASE;
        }
        if (previous.quality() != current.quality()) {
            return SubwayRingPushReason.QUALITY;
        }
        if (!previous.arrivedAtNextStation() && current.arrivedAtNextStation()) {
            return SubwayRingPushReason.ARRIVED;
        }
        return signatureOf(previous).equals(signatureOf(current))
                ? null
                : SubwayRingPushReason.ANCHOR;
    }

    /**
     * 같은 화면인지를 가리는 짧은 지문.
     *
     * <p>{@code updatedAt} 을 넣지 않는다 — 그 값은 매 tick 바뀌므로 넣는 순간 이 지문이 아무것도
     * 걸러 내지 못한다.
     */
    private String signatureOf(SubwayRingState ring) {
        String material = String.join("|",
                ring.phase().name(),
                ring.quality().name(),
                String.valueOf(ring.nextStationSequence()),
                String.valueOf(ring.canonicalNextStationKey()),
                String.valueOf(ring.remainingStations()),
                bucketedProgress(ring.segmentProgress()),
                bucketedEta(ring.estimatedNextStationAt()));
        return hash(material);
    }

    private String bucketedProgress(BigDecimal progress) {
        if (progress == null) {
            return "-";
        }
        return progress.divide(PROGRESS_BUCKET, 0, RoundingMode.FLOOR).toPlainString();
    }

    private String bucketedEta(Instant estimatedAt) {
        if (estimatedAt == null) {
            return "-";
        }
        return String.valueOf(estimatedAt.getEpochSecond() / ETA_BUCKET_SECONDS);
    }

    /** 칼럼이 64자다. SHA-256 을 16바이트만 쓰면 32자로 들어가고 충돌은 실질적으로 없다. */
    private String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("signature 계산 실패", e);
        }
    }

    /** push 유효기간. 이 시각을 넘긴 진행률은 보내지 않는 편이 낫다. */
    public Instant expiresAt(Instant now) {
        return now.plus(properties.getPush().getTtl());
    }

    /**
     * 보낼 이유 한 건.
     *
     * @param signature 같은 화면을 두 번 만들지 않기 위한 지문
     */
    public record Decision(SubwayRingPushReason reason, String signature, Instant updatedAt) {

        public boolean critical() {
            return reason.isCritical();
        }
    }
}
