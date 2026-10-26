"""Ask Claude for cause hypotheses, then keep only the ones backed by real evidence.

The model sees the masked context bundle and must cite evidence ids from it
("t:…", "m:…", "m:restarts"). Ids it invents are removed, and a hypothesis left
with no evidence is dropped, so a confident but unsupported story never reaches
Slack. Whether a restart happened is decided by code, not by the model.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import anthropic
from pydantic import BaseModel, ValidationError

from analyzer.context import ContextBundle

MODEL = "claude-sonnet-5-5"
EFFORT = "medium"
MAX_TOKENS = 16_000
TIMEOUT_S = 60.0
MAX_RETRIES = 2
# Server-side refusal fallback ("default" picks the model per refusal category).
FALLBACK_BETA = "server-side-fallback-2026-07-01"
MAX_HYPOTHESES = 3
# USD per token, claude-sonnet-5-5.
PRICE_IN = 2.0 / 1_000_000
PRICE_OUT = 10.0 / 1_000_000

SYSTEM_PROMPT = (Path(__file__).parent / "prompts" / "system.md").read_text(encoding="utf-8")

Category = Literal["deploy_restart", "external_api", "resource_exhaustion", "app_bug", "infra"]
Confidence = Literal["high", "medium", "low"]


class Hypothesis(BaseModel):
    cause: str
    category: Category
    confidence: Confidence
    evidence: list[str]
    next_check: str


class Analysis(BaseModel):
    """What the model fills in. Everything else on Verdict is computed here."""

    headline: str
    hypotheses: list[Hypothesis]


@dataclass(frozen=True)
class Verdict:
    headline: str
    hypotheses: list[Hypothesis]   # evidence-checked, at most MAX_HYPOTHESES
    deploy_related: bool           # a process restarted near the window (from Prometheus)
    unknown: bool                  # no hypothesis survived the evidence check
    invalid_evidence: int          # cited ids that were not in the bundle (before the check)
    total_evidence: int
    model: str
    input_tokens: int
    output_tokens: int
    elapsed_s: float
    request_id: str | None = None

    @property
    def cost_usd(self) -> float:
        return self.input_tokens * PRICE_IN + self.output_tokens * PRICE_OUT


class LlmFailure(Exception):
    """The analysis could not be produced. `reason` is safe to show in Slack."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def check_evidence(analysis: Analysis, valid_ids: set[str]) -> tuple[list[Hypothesis], int, int]:
    """Drop ids not in the bundle, then hypotheses left without evidence.

    Returns (kept hypotheses, invalid id count, total id count).
    """
    kept: list[Hypothesis] = []
    invalid = total = 0
    for hypothesis in analysis.hypotheses:
        cited = [e.strip() for e in hypothesis.evidence]
        total += len(cited)
        valid = [e for e in cited if e in valid_ids]
        invalid += len(cited) - len(valid)
        if valid:
            kept.append(hypothesis.model_copy(update={"evidence": list(dict.fromkeys(valid))}))
    return kept[:MAX_HYPOTHESES], invalid, total


def user_message(bundle: ContextBundle) -> str:
    ids = ", ".join(sorted(bundle.ids())) or "(없음)"
    return (
        "아래는 방금 울린 알림의 맥락 묶음이다.\n\n"
        f"<context>\n{bundle.to_json()}\n</context>\n\n"
        f"근거로 쓸 수 있는 id: {ids}"
    )


class Analyzer:
    def __init__(
        self,
        client: anthropic.AsyncAnthropic,
        *,
        model: str = MODEL,
        effort: str = EFFORT,
        system_prompt: str = SYSTEM_PROMPT,
    ):
        self._client = client
        self._model = model
        self._effort = effort
        self._system = system_prompt

    async def analyze(self, bundle: ContextBundle) -> Verdict:
        started = time.monotonic()
        try:
            response = await self._client.beta.messages.parse(
                model=self._model,
                max_tokens=MAX_TOKENS,
                system=self._system,
                messages=[{"role": "user", "content": user_message(bundle)}],
                output_format=Analysis,
                output_config={"effort": self._effort},
                fallbacks="default",
                betas=[FALLBACK_BETA],
            )
        except ValidationError:
            # parse() validates the text before we can look at stop_reason, so a
            # refusal or cut-off mid-answer surfaces here as malformed JSON.
            raise LlmFailure("응답 형식 오류(중간 거절 또는 잘림)") from None
        except anthropic.APITimeoutError:
            raise LlmFailure(f"시간 초과({TIMEOUT_S:.0f}초, 재시도 {MAX_RETRIES}회)") from None
        except anthropic.APIConnectionError:
            raise LlmFailure("Claude API 연결 실패") from None
        except anthropic.APIStatusError as exc:
            raise LlmFailure(f"Claude API 오류 HTTP {exc.status_code}") from None

        if response.stop_reason == "refusal":
            category = response.stop_details.category if response.stop_details else None
            raise LlmFailure(f"모델이 응답을 거절함({category or '사유 미상'})")
        if response.stop_reason == "max_tokens":
            raise LlmFailure("출력 토큰 상한에 걸림")
        analysis = response.parsed_output
        if analysis is None:
            raise LlmFailure("응답이 비어 있음")

        kept, invalid, total = check_evidence(analysis, bundle.ids())
        return Verdict(
            headline=analysis.headline.strip(),
            hypotheses=kept,
            deploy_related=bool(bundle.restarts),
            unknown=not kept,
            invalid_evidence=invalid,
            total_evidence=total,
            model=response.model,
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
            elapsed_s=time.monotonic() - started,
            request_id=getattr(response, "_request_id", None),
        )


def make_client(api_key: str) -> anthropic.AsyncAnthropic:
    return anthropic.AsyncAnthropic(api_key=api_key, timeout=TIMEOUT_S, max_retries=MAX_RETRIES)
