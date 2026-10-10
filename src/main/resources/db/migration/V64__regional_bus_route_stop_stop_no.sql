-- #266 경기·지방 버스 정류장 번호(표지판 번호). 경기 GBIS mobileNo, TAGO nodeno. 없으면 null.
ALTER TABLE regional_bus_route_stop ADD COLUMN stop_no varchar(16);
