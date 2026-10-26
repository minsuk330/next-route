"""Replay the incident fixtures through the analyzer and score it against a rule baseline.

This calls the real Claude API and costs money. Without --yes it prints the
cases and a rough cost estimate, then stops.

    ANTHROPIC_API_KEY=... uv run python -m eval.run_eval --yes --out eval/results.json

Scores per case: did the top hypothesis match the label, did the rule baseline
(restart -> deploy_restart, external call failures -> external_api, otherwise
unknown) match, was a must-cite id cited, and how many cited ids were invented.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import sys
import tomllib
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from analyzer.context import ContextBundle, Restart
from analyzer.llm import (
    EFFORT,
    MODEL,
    PRICES,
    SYSTEM_PROMPT,
    Analyzer,
    LlmFailure,
    Verdict,
    make_client,
    user_message,
)

ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "eval" / "cases.toml"
INCIDENTS = ROOT / "fixtures" / "incidents"
# Rough estimate only: JSON with Korean runs about 2 characters per token, and
# thinking plus the answer stays well under this many output tokens.
CHARS_PER_TOKEN = 2.0
OUTPUT_TOKENS_GUESS = 3_000


@dataclass(frozen=True)
class Case:
    id: str
    fixture: str
    expect: str
    must_cite: list[str]
    answer: str
    synthetic_context: bool = False
    add_restarts: list[dict] = field(default_factory=list)


def load_cases(path: Path = CASES) -> list[Case]:
    return [Case(**raw) for raw in tomllib.loads(path.read_text(encoding="utf-8"))["case"]]


def load_bundle(case: Case) -> ContextBundle:
    data = json.loads((INCIDENTS / f"{case.fixture}.json").read_text(encoding="utf-8"))
    bundle = ContextBundle.from_dict(data["bundle"])
    for r in case.add_restarts:
        bundle.restarts.append(Restart(r["instance"], datetime.fromisoformat(r["started_at"])))
    return bundle


def rule_baseline(bundle: ContextBundle) -> str:
    if bundle.restarts:
        return "deploy_restart"
    if any(m.key == "m:client_fail_10m" and (m.at_alert or 0) > 0 for m in bundle.metrics):
        return "external_api"
    return "unknown"


def predicted(verdict: Verdict) -> str:
    return "unknown" if verdict.unknown else verdict.hypotheses[0].category


def llm_hit(expect: str, verdict: Verdict) -> bool:
    if expect == "none":
        return verdict.unknown
    if expect == "unknown":
        return verdict.unknown or verdict.hypotheses[0].confidence == "low"
    return not verdict.unknown and verdict.hypotheses[0].category == expect


def baseline_hit(expect: str, guess: str) -> bool:
    return guess == "unknown" if expect in ("none", "unknown") else guess == expect


def cited(case: Case, verdict: Verdict) -> bool | None:
    if not case.must_cite:
        return None
    return any(e in case.must_cite for h in verdict.hypotheses for e in h.evidence)


def estimate_usd(bundles: list[ContextBundle], model: str = MODEL) -> float:
    price_in, price_out = PRICES[model]
    chars = sum(len(SYSTEM_PROMPT) + len(user_message(b)) for b in bundles)
    return (chars / CHARS_PER_TOKEN * price_in + len(bundles) * OUTPUT_TOKENS_GUESS * price_out) / 1_000_000


def score(case: Case, bundle: ContextBundle, verdict: Verdict | None, failure: str | None = None) -> dict:
    guess = rule_baseline(bundle)
    row = {
        "id": case.id,
        "expect": case.expect,
        "synthetic_context": case.synthetic_context,
        "baseline": guess,
        "baseline_hit": baseline_hit(case.expect, guess),
    }
    if verdict is None:
        return {**row, "failure": failure, "llm_hit": False}
    return {
        **row,
        "predicted": predicted(verdict),
        "llm_hit": llm_hit(case.expect, verdict),
        "cited": cited(case, verdict),
        "invalid_evidence": verdict.invalid_evidence,
        "total_evidence": verdict.total_evidence,
        "headline": verdict.headline,
        "model_headline": verdict.model_headline,
        "hypotheses": [h.model_dump() for h in verdict.hypotheses],
        "input_tokens": verdict.input_tokens,
        "output_tokens": verdict.output_tokens,
        "cost_usd": round(verdict.cost_usd, 5),
        "elapsed_s": round(verdict.elapsed_s, 1),
        "model": verdict.model,
    }


def summarize(rows: list[dict]) -> dict:
    scored_cites = [r["cited"] for r in rows if r.get("cited") is not None]
    invalid = sum(r.get("invalid_evidence", 0) for r in rows)
    total = sum(r.get("total_evidence", 0) for r in rows)
    return {
        "cases": len(rows),
        "llm_hits": sum(r["llm_hit"] for r in rows),
        "baseline_hits": sum(r["baseline_hit"] for r in rows),
        "must_cite_hits": f"{sum(scored_cites)}/{len(scored_cites)}",
        "invalid_evidence": f"{invalid}/{total}",
        "failures": [r["id"] for r in rows if "failure" in r],
        "cost_usd": round(sum(r.get("cost_usd", 0) for r in rows), 4),
        "max_elapsed_s": max((r.get("elapsed_s", 0) for r in rows), default=0),
    }


async def run_live(cases: list[Case], api_key: str, model: str, effort: str) -> list[dict]:
    client = make_client(api_key)
    try:
        return await run(cases, Analyzer(client, model=model, effort=effort))
    finally:
        await client.close()


async def run(cases: list[Case], analyzer: Analyzer) -> list[dict]:
    rows = []
    for case in cases:
        bundle = load_bundle(case)
        try:
            rows.append(score(case, bundle, await analyzer.analyze(bundle)))
        except LlmFailure as exc:
            rows.append(score(case, bundle, None, exc.reason))
        print(json.dumps(rows[-1], ensure_ascii=False), file=sys.stderr)
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--yes", action="store_true", help="actually call the API (costs money)")
    parser.add_argument("--case", action="append", default=[], help="only these case ids (repeatable)")
    parser.add_argument("--model", default=MODEL, choices=sorted(PRICES))
    parser.add_argument("--effort", default=EFFORT)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)

    cases = [c for c in load_cases() if not args.case or c.id in args.case]
    estimate = estimate_usd([load_bundle(c) for c in cases], args.model)
    print(f"{len(cases)} cases, model={args.model} effort={args.effort}, estimated ≤ ${estimate:.2f}", file=sys.stderr)
    if not args.yes:
        print("dry run: pass --yes to call the API", file=sys.stderr)
        return 0

    rows = asyncio.run(run_live(cases, os.environ["ANTHROPIC_API_KEY"], args.model, args.effort))
    result = {
        "model": args.model,
        "effort": args.effort,
        "prompt_sha256": hashlib.sha256(SYSTEM_PROMPT.encode()).hexdigest()[:12],
        "summary": summarize(rows),
        "cases": rows,
    }
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    print(json.dumps(result["summary"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
