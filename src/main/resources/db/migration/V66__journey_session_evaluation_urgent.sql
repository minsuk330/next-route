-- 평가기 선점 순서(급한 환승 먼저). 마지막 평가가 급하다고 본 여정(환승 위험·임박)과 시작·단계 전이·
-- 경로 교체 직후의 여정이 true. 평가기가 밀릴 때 이 여정을 먼저 선점한다.
ALTER TABLE journey_session
    ADD COLUMN evaluation_urgent boolean NOT NULL DEFAULT false;
