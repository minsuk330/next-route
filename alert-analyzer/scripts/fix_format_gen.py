"""Model half of eval/fix_format.py: reads the dataset on stdin, prints both answers per case."""

import asyncio, json, sys
import anthropic
CASES = [{"i": i, "path": d["path"], "snippet": d["snippet"], "message": d["message"]} for i, d in enumerate(json.load(sys.stdin))]
MODEL = "claude-sonnet-5-5"
SYSTEM = ("너는 Java 백엔드 버그를 고친다. 사용자가 배포된 코드의 메서드 원문을 준다. 각 줄 앞의 숫자는 파일의 실제 줄 번호이고 코드의 일부가 아니다. "
          "커밋 메시지가 설명하는 수정을 이 메서드 안에서 최소한으로 하라. 설명은 쓰지 말고 지정한 형식만 출력한다.")
FMT = {
 "udiff": ("답은 unified diff 하나만 ```diff 블록으로. 헤더는 --- a/{path} 와 +++ b/{path}. "
           "각 덩어리는 @@ -시작,줄수 +시작,줄수 @@ 형식으로 실제 줄 번호를 넣고 앞뒤 문맥 3줄을 포함한다."),
 "search": ("답은 SEARCH/REPLACE 블록만. 형식:\n<<<<<<< SEARCH\n(원문 그대로 복사, 줄 번호 접두 없이)\n=======\n(바꿀 코드)\n>>>>>>> REPLACE\n"
            "블록은 여러 개 가능. SEARCH 는 파일 안에서 한 번만 나오도록 충분히 길게."),
}
client = anthropic.AsyncAnthropic()
sem = asyncio.Semaphore(5)
usage = {"in": 0, "out": 0}
async def ask(c, fmt):
    user = f"파일: {c['path']}\n\n커밋 메시지:\n{c['message']}\n\n메서드 원문:\n{c['snippet']}\n\n" + FMT[fmt].format(path=c['path'])
    async with sem:
        for t in range(3):
            try:
                r = await client.messages.create(model=MODEL, max_tokens=8000, system=SYSTEM, messages=[{"role": "user", "content": user}])
                usage["in"] += r.usage.input_tokens; usage["out"] += r.usage.output_tokens
                return "".join(b.text for b in r.content if b.type == "text")
            except Exception as e:
                if t == 2: return "ERROR " + type(e).__name__
                await asyncio.sleep(3)
async def main():
    res = await asyncio.gather(*(ask(c, f) for c in CASES for f in ("udiff", "search")))
    out = [{"i": c["i"], "udiff": res[2*k], "search": res[2*k+1]} for k, c in enumerate(CASES)]
    print(json.dumps({"answers": out, "usage": usage}, ensure_ascii=False))
asyncio.run(main())
