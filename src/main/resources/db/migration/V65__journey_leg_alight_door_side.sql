-- #335 지하철 내리는 문 방향(LEFT/RIGHT). 여정을 만들 때 한 번 채운다. null = 모름 → 앱이 줄을 숨긴다.
ALTER TABLE journey_leg
    ADD COLUMN alight_door_side varchar(8);
