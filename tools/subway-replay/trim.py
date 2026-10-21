"""녹화 조각 만들기(subway-express S4): 시간 창(시작 10분 전부터) 안에서 그 노선·방향의 지정 역 도착 행만, 위치 응답은 그 노선·방향 행만 남긴다(건수 칸도 맞춘다).

사용: python3 -I trim.py <녹화 폴더> <출력 폴더> <lineId> <노선명> <FROM ISO> <TO ISO> <역id,…> <도착 방향(상행·하행)> <위치 updnLine(0·1)>
"""
import json, sys, os
from datetime import datetime, timedelta
src, out, line_id, line_name, frm, to, stations, arr_dir, pos_dir = sys.argv[1:10]
ids = set(stations.split(','))
f0 = datetime.fromisoformat(frm.replace('Z', '+00:00')) - timedelta(seconds=600)
t1 = datetime.fromisoformat(to.replace('Z', '+00:00'))
os.makedirs(out, exist_ok=True)
def inwin(j):
    at = datetime.fromisoformat(j['fetchedAt'])
    return f0 <= at <= t1
n = 0
with open(f'{out}/arrival.jsonl', 'w') as w:
    for l in open(f'{src}/arrival.jsonl'):
        j = json.loads(l)
        if not inwin(j):
            continue
        b = j['body']
        if isinstance(b.get('realtimeArrivalList'), list):
            b['realtimeArrivalList'] = [r for r in b['realtimeArrivalList']
                                        if str(r.get('subwayId')) == line_id and r.get('statnId') in ids and r.get('updnLine') == arr_dir]
            if 'errorMessage' in b and isinstance(b['errorMessage'], dict):
                b['errorMessage']['total'] = len(b['realtimeArrivalList'])
        w.write(json.dumps(j, ensure_ascii=False, separators=(',', ':')) + '\n'); n += 1
with open(f'{out}/position-{line_name}.jsonl', 'w') as w:
    for l in open(f'{src}/position-{line_name}.jsonl'):
        j = json.loads(l)
        if inwin(j):
            b = j['body']
            if isinstance(b.get('realtimePositionList'), list):
                b['realtimePositionList'] = [r for r in b['realtimePositionList'] if r.get('updnLine') == pos_dir]
                for r in b['realtimePositionList']:
                    r['totalCount'] = r['selectedCount'] = len(b['realtimePositionList'])
                if isinstance(b.get('errorMessage'), dict):
                    b['errorMessage']['total'] = len(b['realtimePositionList'])
            w.write(json.dumps(j, ensure_ascii=False, separators=(',', ':')) + '\n')
print('frames', n)
