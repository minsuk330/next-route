-- subway-express PR-S5: 여정을 시작한 검색의 급행 끄기 선호(원래 요청값). 재탐색이 이 값을 보고 같은 급행 정책으로 찾는다.
-- 검색이 D10(일반 경로 0건 → 필터 해제)으로 급행 포함 결과를 냈어도 사용자가 고른 값 그대로다 — 해제는 그 검색 한 번의 일이다.
-- 기존 행(배포 전 여정)·단독 추적은 false(급행 포함 = 옛 동작). 엔티티는 시작 때만 쓰고 바꾸지 않는다(updatable = false).
ALTER TABLE journey_session
    ADD COLUMN exclude_express boolean NOT NULL DEFAULT false;
