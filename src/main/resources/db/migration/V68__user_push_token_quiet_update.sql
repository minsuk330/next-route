-- #428 PR-1a: 기기가 "조용한 갱신"을 받을 수 있는지.
--
-- Android 는 앱이 만든 저중요도 채널(journey_quiet)로 보내야 소리·진동 없이 덮인다. 그 채널이 없는 앱
-- (구버전)에 채널 이름을 지정해 보내면 OS 가 소리 나는 기본 채널로 떨어뜨린다 — 조용하라고 보낸 알림이
-- 울린다. 그래서 앱이 토큰을 등록할 때 능력을 알려 주고, 서버는 그런 기기에만 조용한 갱신을 보낸다.
-- 기본 false = 지금 등록된 모든 기기는 조용한 갱신을 받지 않는다(안전 쪽).
ALTER TABLE user_push_token
    ADD COLUMN quiet_update_supported boolean NOT NULL DEFAULT false;
