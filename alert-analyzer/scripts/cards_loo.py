"""Leave-one-out check for the incident cards (costs about $1; run only when asked).

    docker exec -i observability-alert-analyzer-1 python -m scripts.cards_loo

For each card: give the model only its symptom, once without and once with the
other cards, and let a judge compare the guess with the real cause and fix
location. Baseline (2026-11-27, 25 cards, Sonnet 5.5): cause 22 → 24 of 50,
exact fixed class named 1 → 8 of 24. Card edits should not push the second
number down.
"""

from __future__ import annotations

import asyncio
import json
import re

from analyzer.cards import load

MODEL = "claude-sonnet-5-5"
FIELDS = ["유형", "감지경로", "증상", "증상로그", "원인", "조치", "고친곳", "날짜"]
CONTEXT = (
    "너는 한국 대중교통 앱(Spring Boot api/worker, OTP 길찾기 엔진, 서울·경기·TAGO 공공 API, Postgres, Redis, VPS) 의 "
    "운영 장애를 분석한다. 증상만 보고 가장 그럴듯한 원인 하나와 고칠 곳(클래스나 계층)을 추정하라. 답은 한국어 3문장 이내, "
    "형식: 원인: ... / 고칠 곳: ..."
)
JUDGE = (
    "너는 채점자다. 실제 원인과 고친 곳, 그리고 추정 답변을 비교한다. JSON 한 줄만 출력: "
    '{"cause": 0|1|2, "place": 0|1} — cause 2=핵심 원인 일치, 1=방향은 맞으나 핵심 기전 누락, 0=틀림. '
    "place 1=고친 클래스나 같은 계층을 짚음."
)
_CLASS = re.compile(r"[A-Z][a-zA-Z0-9]+")


def card_text(c: dict) -> str:
    return "\n".join(f"{k}: {c.get(k, '')}" for k in FIELDS)


def question(c: dict, others: list[dict] | None) -> str:
    q = f"증상: {c['증상']}\n증상 로그: {c.get('증상로그') or '(없음)'}"
    if others is None:
        return q
    shown = "\n\n".join(f"[과거 장애 {o['id']}]\n" + card_text(o) for o in others)
    return "아래는 이 서비스의 과거 장애 카드다. 참고해도 되고 무시해도 된다.\n\n" + shown + "\n\n---\n이번 장애\n" + q


def exact_class(c: dict, answer: str) -> bool:
    names = {t for t in _CLASS.findall(c.get("고친곳", "")) if len(t) > 6 and any(ch.islower() for ch in t)}
    return any(n in answer for n in names)


async def main() -> None:
    import anthropic

    cards = [c.fields for c in load()[0]]
    client = anthropic.AsyncAnthropic()
    sem = asyncio.Semaphore(5)

    async def ask(system: str, user: str, max_tokens: int, **kw) -> str:
        async with sem:
            r = await client.messages.create(model=MODEL, max_tokens=max_tokens, system=system,
                                             messages=[{"role": "user", "content": user}], **kw)
            return "".join(b.text for b in r.content if b.type == "text").strip()

    async def judge(c: dict, answer: str) -> dict:
        t = await ask(JUDGE, f"실제 원인: {c['원인']}\n실제 고친 곳: {c['고친곳']}\n\n추정 답변:\n{answer}", 400,
                      thinking={"type": "between_tools"})
        m = re.search(r"\{.*\}", t, re.S)
        return json.loads(m.group(0)) if m else {"cause": 0, "place": 0}

    async def one(c: dict) -> dict:
        others = [o for o in cards if o["id"] != c["id"]]
        a, b = await asyncio.gather(ask(CONTEXT, question(c, None), 8000), ask(CONTEXT, question(c, others), 8000))
        ja, jb = await asyncio.gather(judge(c, a), judge(c, b))
        return {"id": c["id"], "A": ja, "B": jb, "exactA": exact_class(c, a), "exactB": exact_class(c, b)}

    rows = await asyncio.gather(*(one(c) for c in cards))
    total = lambda k, f: sum(r[k][f] for r in rows)
    print(json.dumps({
        "cause": [total("A", "cause"), total("B", "cause"), 2 * len(rows)],
        "place": [total("A", "place"), total("B", "place"), len(rows)],
        "exact_class": [sum(r["exactA"] for r in rows), sum(r["exactB"] for r in rows)],
        "rows": rows,
    }, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
