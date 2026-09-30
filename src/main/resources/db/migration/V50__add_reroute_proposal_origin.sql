-- journey-reroute-nextstop PR-B stage1(확장). transfer_index(leg 번호만 가리킴) 를
-- RerouteOrigin(leg 안 임의 정류장까지 가리킴)으로 일반화하는 첫 단계 — expand→backfill→
-- dual-read/write→cutover 중 expand 만. transfer_index 는 그대로 NOT NULL + 기존 unique 유지
-- (JPA 필드가 primitive int 라 지금 nullable 로 못 바꾼다. 컷오버 단계에서 함께 정리).

ALTER TABLE reroute_proposal ADD COLUMN origin jsonb;

COMMENT ON COLUMN reroute_proposal.origin IS
    'RerouteOriginEnvelope{version,origin}. null = 아직 origin 이 안 채워진 행(옛 행 또는 backfill/dual-write 이전)';
