-- reroute-periodic-board PR-2 (#264). 보드 갱신 선점 표시.
--
-- V57 의 reroute_refresh_at 하나로는 "선점 중인 lease 시각"과 "다음 예정 시각"을 가를 수 없다(둘 다
-- 미래 시각이다). 가르지 못하면 선점 중에 온 정류장 통과 신호가 예정을 앞당겨, 다른 워커가 같은 여정을
-- 다시 선점해 경로 검색을 두 번 돈다. 선점은 이 칸으로만 표시하고, 선점 중 신호는 dirty 로만 남긴다.
ALTER TABLE journey_session
    ADD COLUMN reroute_refresh_claimed_until timestamptz;
