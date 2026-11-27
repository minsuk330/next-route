"""Past production incidents, used only to suggest where to fix (docs/plan/rag-incident-context).

The 2026-11-27 leave-one-out test showed cards barely help diagnosis (22 → 24 of 50)
but point at the class that was actually fixed far more often (1 → 8 of 24), and
mislead in a couple of cases. So cards are never evidence for a cause: the model may
cite them (k:K11) only in fix candidates, and only cards that share classes or
domain words with this alert get in.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

CARDS_PATH = Path(__file__).parent / "incidents" / "cards.json"
MAX_CARDS = 10

# Java type names: two or more capitals and a lowercase letter (OTPClient, JourneySchemaIT,
# RedisOAuthChallengeAdapter). Not \b: Korean is a word character, so "Calculator에서" would miss.
_CLASS = re.compile(r"(?<![A-Za-z0-9])(?=[A-Za-z0-9]*[a-z])(?=(?:[A-Za-z0-9]*[A-Z]){2})[A-Z][A-Za-z0-9]{5,}(?![A-Za-z0-9])")
_WORD = re.compile(r"[A-Z][a-z0-9]+|[A-Z]+(?![a-z])")
# Class-name words that say nothing about the domain.
_GENERIC = {
    "Service", "Adapter", "Controller", "Api", "Query", "Reader", "Port", "Impl", "Response", "Request",
    "Exception", "Handler", "Calculator", "Client", "Config", "Recorder", "Resolver", "Mapper", "Source",
    "Seoul", "Realtime", "Time", "Selection", "Provider", "Composer", "Enricher", "Normalizer", "Test",
    "Wiring", "Search", "Info", "Data", "Line", "Station", "Train", "Route", "Arrival",
    "Id", "Ids", "Lookup", "Collector", "IT", "Store", "Repository", "Job", "Batch",
}


@dataclass(frozen=True)
class Card:
    id: str
    fields: dict

    @property
    def classes(self) -> set[str]:
        return set(_CLASS.findall(self.fields.get("고친곳", "")))

    @property
    def words(self) -> set[str]:
        return {w for c in self.classes for w in _WORD.findall(c)} - _GENERIC

    def as_dict(self) -> dict:
        return {"id": f"k:{self.id}", **{k: v for k, v in self.fields.items() if k != "id"}}


def load(path: Path = CARDS_PATH) -> tuple[list[Card], str | None]:
    """Cards, or none and a reason. A broken file never stops the analyzer."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return [Card(id=str(c["id"]), fields=c) for c in raw], None
    except (OSError, ValueError, KeyError, TypeError) as exc:
        return [], f"카드 읽기 실패({type(exc).__name__})"


def select(cards: list[Card], *, code: list[dict], templates: list, alerts: list[dict], limit: int = MAX_CARDS) -> list[Card]:
    names: set[str] = set()
    for c in code:
        names.add(str(c.get("symbol", "")).split("#")[0])
        names.update(_CLASS.findall(str(c.get("header", ""))))
    for t in templates:
        names.add(str(getattr(t, "logger", "")).rsplit(".", 1)[-1])
        for frame in getattr(t, "frames", []) or []:
            names.update(_CLASS.findall(frame))
    text = " ".join(
        [*names, *(getattr(t, "template", "") for t in templates), *(json.dumps(a, ensure_ascii=False) for a in alerts)]
    )
    words = set(_WORD.findall(text)) | {w for n in names for w in _WORD.findall(n)}
    # A shared class is strong; a shared domain word ("Subway") is weak and pulled in the
    # misleading neighbours of the 2026-11-27 test. So class hits are counted on their own and,
    # when any card has one, only those cards go in. Word overlap is a share of the card's words
    # so cards with many words (K24: Bus, Stop, Lane, ...) do not win by size.
    rows = []
    for n, card in enumerate(cards):
        class_hits = len(card.classes & names)
        word_share = len(card.words & words) / max(1, len(card.words))
        if class_hits or word_share:
            rows.append((class_hits, word_share, n, card))
    if any(r[0] for r in rows):
        rows = [r for r in rows if r[0]]
    rows.sort(key=lambda r: (-r[0], -r[1], r[2]))
    return [r[3] for r in rows[:limit]]
