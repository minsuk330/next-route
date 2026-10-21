"""서울 지하철 도착(ALL)·위치(노선별) 원본 응답을 15초마다 녹화한다. 출력: <out>/<kind>.jsonl, 한 줄 = {fetchedAt, body}."""
import json, os, sys, time, urllib.parse, urllib.request
from datetime import datetime, timezone

# 도착(ALL) 응답에서 남길 노선. 환경변수 KEEP_LINES=1001,1009 로 바꾼다(급행 녹화, subway-express S4).
KEEP_LINES = set((os.environ.get('KEEP_LINES') or '1001,1002').split(','))

def load_env(path):
    env = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env

def fetch(url):
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.loads(r.read().decode('utf-8'))

def main(env_path, out, minutes, interval, lines):
    env = load_env(env_path)
    arrival_key = env.get('SEOUL_SUBWAY_ARRIVAL') or env['SEOUL_SUBWAY_API']
    position_key = env.get('SEOUL_SUBWAY_POSITION') or env['SEOUL_SUBWAY_API']
    base = 'http://swopenAPI.seoul.go.kr/api/subway'
    os.makedirs(out, exist_ok=True)
    end = time.time() + minutes * 60
    n = 0
    while time.time() < end:
        started = time.time()
        jobs = [('arrival', f'{base}/{arrival_key}/json/realtimeStationArrival/ALL')]
        for name in lines:
            jobs.append((f'position-{name}', f'{base}/{position_key}/json/realtimePosition/0/1000/' + urllib.parse.quote(name)))
        for kind, url in jobs:
            at = datetime.now(timezone.utc).isoformat()
            try:
                body = fetch(url)
                if kind == 'arrival' and isinstance(body.get('realtimeArrivalList'), list):
                    body['realtimeArrivalList'] = [r for r in body['realtimeArrivalList']
                                                   if str(r.get('subwayId')) in KEEP_LINES]
            except Exception as e:  # 키는 URL 에 있으므로 예외 메시지에 URL 을 싣지 않는다
                body = {'error': type(e).__name__}
            with open(os.path.join(out, kind + '.jsonl'), 'a') as f:
                f.write(json.dumps({'fetchedAt': at, 'body': body}, ensure_ascii=False) + '\n')
        n += 1
        if n % 8 == 0:
            print(f'{datetime.now().isoformat(timespec="seconds")} rounds={n}', flush=True)
        time.sleep(max(0, interval - (time.time() - started)))
    print(f'done rounds={n}', flush=True)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), sys.argv[5:])
