package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * {@code reroute_proposal.origin} 컬럼에 담기는 모양. {@link RerouteCandidateEnvelope} 와 같은
 * 이유로 배열 대신 봉투를 쓴다 — {@link #version} 이 없으면 계약이 바뀔 때 읽는 쪽이 옛 행을
 * 가려낼 수 없다.
 *
 * @param version 계약 판. 모르는 값이면 읽는 쪽이 그 행을 쓰지 않는다
 * @param origin  null 이면 "아직 origin 이 안 채워진 행"(옛 transfer_index-only 행 또는 dual-write
 *                이전에 만들어진 행) — 컬럼 자체의 null 과 같은 뜻이라 이 필드가 null 인 봉투는
 *                만들지 않는다(컬럼을 null 로 두지, 빈 봉투를 저장하지 않는다).
 */
public record RerouteOriginEnvelope(int version, RerouteOrigin origin) {

    /** 현재 계약 판. origin 의 필드 뜻이 바뀌면 올린다. */
    public static final int CURRENT_VERSION = 1;

    public static RerouteOriginEnvelope of(RerouteOrigin origin) {
        return new RerouteOriginEnvelope(CURRENT_VERSION, origin);
    }

    /** {@code isX()} 는 Jackson 이 속성으로 오해한다. 봉투에 없는 칸이 생기면 왕복이 깨진다. */
    @JsonIgnore
    public boolean isCurrentVersion() {
        return version == CURRENT_VERSION;
    }
}
