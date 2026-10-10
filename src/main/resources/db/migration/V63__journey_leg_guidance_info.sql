-- #266 안내 시트 지점 줄 값. 여정을 만들 때 한 번 채운다. 전부 null 허용 — 없으면 앱이 줄을 숨긴다.
ALTER TABLE journey_leg
    ADD COLUMN start_stop_no  varchar(16),
    ADD COLUMN end_stop_no    varchar(16),
    ADD COLUMN direction_name varchar(64),
    ADD COLUMN start_exit_no  varchar(8),
    ADD COLUMN end_exit_no    varchar(8),
    ADD COLUMN fast_alight    jsonb;
