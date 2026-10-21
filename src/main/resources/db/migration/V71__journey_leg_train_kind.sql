-- subway-express PR-S2: 지하철 단계의 열차 종류(NORMAL/EXPRESS/DIRECT/ITX). 원천은 개정본 스냅샷의 subPath.trainKind 이고
-- 이 칸은 단계 조회용 비정규화 사본이다(재탐색 개정본은 새 subPath 에서 다시 채운다). null = 모름(배포 전 여정·버스·도보).
ALTER TABLE journey_leg
    ADD COLUMN train_kind varchar(16);
