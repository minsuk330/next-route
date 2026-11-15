/*
 * 대구교통공사 공식 열차시각 API → JSON 내려받기 (브라우저 콘솔용)
 *
 * 왜 브라우저인가: https://www.dtro.or.kr/open_content_new/ko/OpenApi/stationTime.php 는
 * 서버(curl, RestClient) 호출에 봇 확인 스크립트를 돌려준다. 브라우저는 그 확인을 정상 통과하므로
 * 사람이 브라우저에서 한 번 돌려 파일로 받고, 그 파일을 저장소 리소스로 커밋한다.
 *
 * 사용법
 *   1. 크롬에서 https://www.dtro.or.kr/index.do?menu_id=00001480 을 연다(페이지가 정상으로 보이면 확인 통과).
 *   2. 개발자 도구(⌥⌘I) > Console 에 이 파일 전체를 붙여넣고 Enter.
 *   3. 약 6~8분 뒤 daegu-timetable-YYYYMMDD.json 이 내려받아진다. 중간에 멈추면 같은 탭에서 다시 붙여넣으면
 *      이미 받은 것은 건너뛰고 이어서 받는다(window.__dtroExport 에 진행분 보관).
 *   4. 받은 파일을 src/main/resources/subway/daegu/timetable.json 으로 커밋한다(SOURCE.md 에 받은 날짜 기록).
 *
 * 요청 수: 역 94개 × 상/하행 2 × 평일/토/휴일 3 = 약 564회, 요청 사이 500ms.
 * 응답 예: <SCHEDULE>5:42:00, 5:57:00, …, 23:45:00(이곡)</SCHEDULE>  괄호 = 그 열차의 종착역.
 */
(async () => {
  const BASE = '/open_content_new/ko/OpenApi/stationTime.php';
  const DELAY_MS = 500;
  const METHS = ['UP', 'DOWN'];
  const TYPES = ['WEEKDAY', 'SAT', 'HOLIDAY'];

  // 호선별 역. 각 역은 [시도할 이름들...] — 앞에서부터 결과가 나오는 이름을 쓴다.
  // 출처: TAGO 역명(괄호 부기명 포함)과 대구교통공사 파일데이터 역명. 짧은 이름을 먼저 둔다.
  const STATIONS = {
    1: [
      ['설화명곡'], ['화원'], ['대곡', '대곡(정부대구청사)'], ['진천'], ['월배'], ['상인'], ['월촌'], ['송현'],
      ['서부정류장', '서부정류장(관문시장)'], ['대명'], ['안지랑'], ['현충로'], ['영대병원'], ['교대'],
      ['명덕', '명덕(2.28민주운동기념회관)'], ['반월당'], ['중앙로'], ['대구역', '대구'], ['칠성시장'],
      ['신천', '신천(경북대입구)'], ['동대구역', '동대구'], ['동구청', '동구청(큰고개)'], ['아양교'], ['동촌'],
      ['해안'], ['방촌'], ['용계'], ['율하'], ['신기'], ['반야월'], ['각산'],
      ['안심', '안심(혁신도시.첨복단지)'], ['대구한의대병원'], ['부호', '부호(경일대호산대)'], ['하양', '하양(대구가톨릭대)'],
    ],
    2: [
      ['문양'], ['다사'], ['대실'], ['강창'], ['계명대'], ['성서산업단지'], ['이곡'],
      ['용산', '용산(서부법원.검찰청입구)'], ['죽전'], ['감삼'], ['두류'], ['내당'], ['반고개'], ['청라언덕'],
      ['반월당'], ['경대병원'], ['대구은행'], ['범어'], ['수성구청', '수성구청(KBS)'], ['만촌'],
      ['담티', '담티(산대.대륜)'], ['연호'], ['수성알파시티', '수성알파시티(삼성라이온즈파크)'], ['고산'],
      ['신매'], ['사월'], ['정평'], ['임당'], ['영남대'],
    ],
    3: [
      ['칠곡경대병원'], ['학정'], ['팔거', '팔거(국립농관원.통계청)'], ['동천'], ['칠곡운암'], ['구암'], ['태전'],
      ['매천'], ['매천시장'], ['팔달'], ['공단'], ['만평'], ['팔달시장'], ['원대'], ['북구청'], ['달성공원'],
      ['서문시장', '서문시장(동산병원)'], ['청라언덕'], ['남산'], ['명덕', '명덕(2.28민주운동기념회관)'],
      ['건들바위'], ['대봉교'], ['수성시장'], ['수성구민운동장'], ['어린이세상'], ['황금'],
      ['수성못', '수성못(TBC)'], ['지산'], ['범물'], ['용지'],
    ],
  };

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const state = (window.__dtroExport ||= { results: {}, resolved: {}, failures: [] });
  const key = (line, name, meth, type) => `${line}|${name}|${meth}|${type}`;

  class BotPage extends Error {}

  async function call(line, name, meth, type) {
    const qs = new URLSearchParams({ STT_NM: name, LINE_NO: String(line), SCHEDULE_METH: meth, SCHEDULE_TYPE: type });
    const res = await fetch(`${BASE}?${qs}`, { credentials: 'same-origin' });
    const text = await res.text();
    if (!text.includes('<apiDataList')) {
      if (text.includes('sabSignature')) throw new BotPage('봇 확인 페이지가 왔다');
      throw new Error(`예상 밖 응답 ${res.status}: ${text.slice(0, 120)}`);
    }
    const doc = new DOMParser().parseFromString(text, 'application/xml');
    return [...doc.getElementsByTagName('apiData')].map((d) => {
      const v = (t) => d.getElementsByTagName(t)[0]?.textContent?.trim() ?? null;
      const raw = v('SCHEDULE') || '';
      return {
        lineNo: v('LINE_NO'), sttCd: v('STT_CD'), sttNm: v('STT_NM'), scheduleMeth: v('SCHEDULE_METH'),
        schedule: raw.split(',').map((s) => s.trim()).filter(Boolean),
      };
    }).filter((a) => a.schedule.length > 0);
  }

  // 1단계: 역마다 결과가 나오는 이름을 찾는다(평일 상행 → 평일 하행 순으로 시도).
  async function resolve(line, candidates) {
    const id = `${line}|${candidates[0]}`;
    if (state.resolved[id] !== undefined) return state.resolved[id];
    for (const name of candidates) {
      for (const meth of METHS) {
        const rows = await call(line, name, meth, 'WEEKDAY');
        await sleep(DELAY_MS);
        if (rows.length) {
          state.results[key(line, name, meth, 'WEEKDAY')] = rows;
          return (state.resolved[id] = name);
        }
      }
    }
    return (state.resolved[id] = null);
  }

  let done = 0;
  const total = Object.values(STATIONS).reduce((n, l) => n + l.length, 0) * METHS.length * TYPES.length;
  try {
    for (const [line, list] of Object.entries(STATIONS)) {
      for (const candidates of list) {
        const name = await resolve(line, candidates);
        if (!name) {
          state.failures.push({ line, candidates, reason: '모든 이름 후보가 빈 결과' });
          console.warn(`[dtro] ${line}호선 ${candidates.join(' / ')} — 이름을 못 찾음`);
          done += METHS.length * TYPES.length;
          continue;
        }
        for (const meth of METHS) {
          for (const type of TYPES) {
            const k = key(line, name, meth, type);
            if (!(k in state.results)) {
              state.results[k] = await call(line, name, meth, type); // 종착역 한쪽 방향은 [] 가 정상
              await sleep(DELAY_MS);
            }
            if (++done % 30 === 0) console.log(`[dtro] ${done}/${total}`);
          }
        }
      }
    }
  } catch (e) {
    if (e instanceof BotPage) {
      console.error('[dtro] 봇 확인 페이지가 왔습니다. 이 탭을 새로고침한 뒤 스크립트를 다시 붙여넣으면 이어서 받습니다.');
    } else {
      console.error('[dtro] 중단:', e);
    }
    return;
  }

  const rows = Object.entries(state.results).map(([k, data]) => {
    const [line, name, meth, type] = k.split('|');
    return { line: Number(line), query: name, meth, type, data };
  });
  const ymd = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' }).replaceAll('-', '');
  const out = {
    source: 'https://www.dtro.or.kr/open_content_new/ko/OpenApi/stationTime.php',
    fetchedAt: new Date().toISOString(),
    stationCount: Object.values(state.resolved).filter(Boolean).length,
    failures: state.failures,
    rows,
  };
  const blob = new Blob([JSON.stringify(out, null, 1)], { type: 'application/json' });
  const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: `daegu-timetable-${ymd}.json` });
  document.body.appendChild(a); a.click(); a.remove();
  console.log(`[dtro] 완료: 역 ${out.stationCount}개, 요청 결과 ${rows.length}건, 이름 실패 ${state.failures.length}건 → ${a.download}`);
})();
