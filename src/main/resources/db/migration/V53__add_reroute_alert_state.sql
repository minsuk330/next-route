-- journey-reroute-nextstop PR-3 — 알림 계약(서버가 먼저 알리고, 바꾸는 것은 사용자만).
--
-- 지금까지 재탐색 제안은 사용자가 "다시 찾아줘"를 눌러야만 생겼다. 이 배포부터 서버가 환승
-- 위험을 감지하면 스스로 제안을 만들어 push 한 줄로 알린다. 그러면 사용자가 그 알림에 어떻게
-- 반응했는지를 여정이 기억해야 한다 — 수락/거절/무시가 다음 알림을 보낼지 말지를 정한다.
--
-- 왜 Redis 가 아니라 표인가. 무응답 집계는 "이 여정에서 몇 번 알렸는데 답이 없었나"이고,
-- 그 값은 TTL 로 사라지면 안 된다(사라지면 상한이 무의미해져 30분짜리 여정이 계속 알림을
-- 받는다). cooldown 처럼 "잠깐 참기"는 Redis 가 맞지만 이건 여정의 상태다.

ALTER TABLE journey_session
    ADD COLUMN reroute_terminated boolean not null default false,
    ADD COLUMN reroute_alert_opt_in boolean not null default false,
    ADD COLUMN reroute_ignored_count integer not null default 0;

COMMENT ON COLUMN journey_session.reroute_terminated IS
    '이 여정의 재탐색 알림이 끝났다. 수락(1회 한정) 또는 거절+계속받기 미체크가 세운다';
COMMENT ON COLUMN journey_session.reroute_alert_opt_in IS
    '거절하면서 "계속 알림 받기"를 켰다. 무응답 상한을 넘겨도 계속 알린다';
COMMENT ON COLUMN journey_session.reroute_ignored_count IS
    '알림을 보냈지만 답이 없었던 횟수(TTL 만료 + 명시적 무시). 상한을 넘으면 더 알리지 않는다';
