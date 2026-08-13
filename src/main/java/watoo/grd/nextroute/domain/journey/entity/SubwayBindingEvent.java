package watoo.grd.nextroute.domain.journey.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 탑승 열차 판정이 무엇을 보고 무엇을 정했는지 한 줄.
 *
 * <p>하는 일이 둘이다.
 * <ol>
 *   <li><b>근거를 남긴다.</b> 판정이 틀렸을 때 관문 값을 조정하려면 "왜 그 열차를 골랐나"가
 *       있어야 한다. 숫자만 세면 몇 번 틀렸는지는 알아도 왜 틀렸는지는 영영 모른다</li>
 *   <li><b>후보 목록의 원본이다.</b> 잠정 판정의 후보들이 여기 남아야, 캐시가 날아간 뒤에도
 *       "그 후보가 아니라 이 후보였다"는 교정이 가능하다</li>
 * </ol>
 *
 * <p>여정 계측 표({@code journey_telemetry_event})를 쓰지 않는다. 그 표는 스스로를 "여정 상태
 * 전이의 근거로 쓰지 않는다"고 선언하고, 멱등 키가 앱 소유라 서버 판단을 끼워 넣을 자리가 없다.
 */
@Entity
@Getter
@Table(name = "subway_binding_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayBindingEvent extends BaseEntity {

    @Column(name = "journey_leg_id", nullable = false)
    private Long journeyLegId;

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    @Column(name = "route_revision", nullable = false)
    private int routeRevision;

    @Column(name = "leg_index", nullable = false)
    private int legIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SubwayBindingEventKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private SubwayBindingEventType eventType;

    /** 앱이 붙인 순번. 판단 행에는 없다. */
    @Column(name = "evidence_sequence")
    private Integer evidenceSequence;

    /** 판정 규칙 판. 규칙을 바꾼 뒤 과거 판단과 섞이지 않게 한다. */
    @Column(name = "algorithm_version", length = 20)
    private String algorithmVersion;

    @Column(name = "binding_version")
    private Integer bindingVersion;

    /** 어느 보정 세대의 판단인가. 후보 복구가 세대를 건너뛰면 거부된 열차가 되살아난다. */
    @Column(name = "reset_generation", nullable = false)
    private int resetGeneration;

    /** 기기·원천이 말한 시각. <b>믿지 않는다</b> — 순서와 보관의 기준은 {@link #receivedAt} 이다. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "chosen_train_no", length = 32)
    private String chosenTrainNo;

    @Column(name = "chosen_service_date")
    private LocalDate chosenServiceDate;

    @Column(name = "chosen_line_id", length = 16)
    private String chosenLineId;

    @Column(name = "chosen_direction", length = 10)
    private String chosenDirection;

    /** 후보 목록과 판단 근거. 모양이 계속 바뀌므로 칼럼으로 박지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode detail;

    @Builder
    private SubwayBindingEvent(Long journeyLegId, Long journeyId, int routeRevision, int legIndex,
                               SubwayBindingEventType eventType, Integer evidenceSequence,
                               String algorithmVersion, Integer bindingVersion, int resetGeneration,
                               Instant occurredAt, Instant receivedAt,
                               String chosenTrainNo, LocalDate chosenServiceDate,
                               String chosenLineId, String chosenDirection, JsonNode detail) {
        this.journeyLegId = journeyLegId;
        this.journeyId = journeyId;
        this.routeRevision = routeRevision;
        this.legIndex = legIndex;
        this.kind = eventType.kind();
        this.eventType = eventType;
        this.evidenceSequence = evidenceSequence;
        this.algorithmVersion = algorithmVersion;
        this.bindingVersion = bindingVersion;
        this.resetGeneration = resetGeneration;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.chosenTrainNo = chosenTrainNo;
        this.chosenServiceDate = chosenServiceDate;
        this.chosenLineId = chosenLineId;
        this.chosenDirection = chosenDirection;
        this.detail = detail;
    }
}
