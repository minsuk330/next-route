버스 탑승시간 OTP vs ODsay 보정 표본 (2026-10-13 수집)
- ods.txt            : OD 58개 (id 지역 이름 SX SY EX EY; X=lon)
- ods_pending_quota.txt : ODsay 일일쿼터(429)로 못 받은 28개 OD (쿼터 리셋 후 재실행용)
- odsay_fetch_remote.sh : VPS 에서 실행(ssh vps 'bash -s'), 키는 VPS 쉘 변수에만, 응답에 키 포함 시 차단. 사용: { sed '$d' odsay_fetch_remote.sh; echo "done <<'OD'"; cat ODLIST; echo OD; } | ssh vps 'bash -s' > out.tsv
- raw_odsay/*.json   : ODsay searchPubTransPathT(OPT=0) 원응답(키 없음). 30개 성공, 28개 429
- otpq2.py, otp_run.sh : OTP planConnection BUS-only, first 20, window 60m, BUS reluctance 1.3 (앱과 동일)
- raw_otp/{OD}_{08|14|18}.json : OD 단위 질의 / raw_otp/{OD}_segNN_14.json : ODsay 승하차 정류장 좌표 간 구간 질의
- analyze.py(매칭: 노선번호 동일 + 승/하차 정류장 각 300m 이내), build.py(pairs.json/csv 생성, 품질필터 거리비 0.85~1.15·정류장수차<=2·심야제외), stats.py(통계) -> stats_out.txt
- 호출 수: OTP 133회(otp_calls.txt), ODsay 58회 시도(30 성공)
