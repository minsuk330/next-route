"""녹화에서 재생할 열차 후보를 고른다: 위치 로그상 역을 가장 많이 지난 열차, 그 역 순서·시각."""
import json, sys
from collections import defaultdict

def main(dir_, line_name, top=5):
    seen = defaultdict(list)  # trainNo -> [(fetchedAt, statnId, statnNm, sttus, updn)]
    for l in open(f'{dir_}/position-{line_name}.jsonl'):
        d = json.loads(l)
        for r in d['body'].get('realtimePositionList') or []:
            seen[r['trainNo']].append((d['fetchedAt'], r['statnId'], r['statnNm'], r['trainSttus'], r['updnLine'], r.get('statnTnm')))
    ranked = []
    for no, rows in seen.items():
        stations = []
        for _, sid, nm, _, _, _ in rows:
            if not stations or stations[-1][0] != sid:
                stations.append((sid, nm))
        ranked.append((len(stations), no, rows, stations))
    ranked.sort(reverse=True)
    for n, no, rows, stations in ranked[:top]:
        print(f'train {no} stations={n} updn={rows[0][4]} dest={rows[0][5]} first={rows[0][0][11:19]} last={rows[-1][0][11:19]}')
        print('   ', ' > '.join(f'{nm}({sid})' for sid, nm in stations))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 5)
