from __future__ import annotations

import json
from datetime import datetime, timezone

import pytest

from analyzer.cards import CARDS_PATH, Card, load, select
from analyzer.context import ContextBundle, TemplateStat, fit_budget, gather
from analyzer.llm import Analysis, FixCandidate, Hypothesis, Verdict, check_evidence, check_fix, user_message
from analyzer.slack import MAX_TEXT, render
from tests.test_code_wiring import job, logs

pytestmark = pytest.mark.anyio
STARTS = datetime(2026, 11, 27, 5, 0, tzinfo=timezone.utc)


def bundle(**kw) -> ContextBundle:
    base = dict(alerts=[], window_start=STARTS, window_end=STARTS, templates=[], quiet_summary=[], metrics=[],
                restarts=[], collection_errors=[])
    return ContextBundle(**{**base, **kw})


def card(id, fixed, **fields) -> Card:
    return Card(id=id, fields={"id": id, "고친곳": fixed, "증상": fields.get("증상", "증상"), "날짜": "2026-10-10"})


def test_shipped_cards_load_with_every_field():
    cards, error = load()
    assert error is None and len(cards) == 25
    assert [c.id for c in cards][:2] == ["K01", "K02"]
    for c in cards:
        assert set(c.fields) == {"id", "유형", "감지경로", "증상", "증상로그", "원인", "조치", "고친곳", "날짜"}
    # graph / timetable / schema fixes name no Java class, so the selector never picks them (README)
    assert sorted(c.id for c in cards if not c.classes) == ["K04", "K17", "K19"]
    assert CARDS_PATH.parent.name == "incidents"


def test_broken_or_missing_file_means_no_cards_not_a_crash(tmp_path):
    bad = tmp_path / "cards.json"
    bad.write_text("{not json")
    assert load(bad) == ([], "카드 읽기 실패(JSONDecodeError)")
    assert load(tmp_path / "missing.json")[0] == []


def test_select_prefers_shared_classes_then_domain_words_and_drops_zero():
    cards = [
        card("K1", "SubwayDirectionNormalizer, #339"),
        card("K2", "SubwayRingCalculator"),
        card("K3", "PlaceSearchService, KakaoLocalSearchAdapter"),
        card("K4", "GenericService"),
    ]
    code = [{"symbol": "SubwayDirectionNormalizer#fromPositionRow", "header": "x"}]
    chosen = select(cards, code=code, templates=[], alerts=[])
    assert [c.id for c in chosen] == ["K1"]   # a class hit wins; K2 only shares the word "Subway"
    words_only = select(cards, code=[{"symbol": "SubwayTrainTracker#x", "header": ""}], templates=[], alerts=[])
    assert [c.id for c in words_only] == ["K1", "K2"]   # no class matches: shared words decide


def test_select_uses_loggers_frames_and_limit():
    cards = [card(f"K{i}", "BusArrivalQueryService") for i in range(12)]
    t = TemplateStat(id="t:1", app="api", level="ERROR", logger="watoo.grd.nextroute.x.BusArrivalQueryService",
                     template="x", status="NEW", logger_total=1, logger_baseline=0, sample_count=1,
                     sample_capped=False, baseline_seen=False)
    assert len(select(cards, code=[], templates=[t], alerts=[])) == 10


async def test_gather_attaches_selected_cards_outside_the_budget():
    cards = [card("K1", "OtpRoutingAdapter"), card("K2", "PlaceSearchService")]
    b = await gather(job(), logs(), _no_metrics(), None, cards)
    assert [c["id"] for c in b.cards] == ["k:K1"]       # the template's logger is OtpRoutingAdapter
    assert "k:K1" in b.fix_ids() and "k:K1" not in b.ids()
    big = fit_budget(bundle(cards=b.cards, quiet_summary=["q" * 50_000]), limit=1_000)
    assert big.cards == b.cards


def _no_metrics():
    from tests.conftest import FakeMetrics
    return FakeMetrics()


def test_cards_never_count_as_cause_evidence_but_do_as_fix_refs():
    b = bundle(cards=[{"id": "k:K11", "증상": "s", "날짜": "2026-10-10"}],
               code=[{"id": "s:1", "symbol": "A#b", "start_line": 1, "end_line": 2, "revision": "f" * 40}])
    h = Hypothesis(cause="c", category="app_bug", confidence="high", evidence=["k:K11"], next_check="n")
    fixes = [
        FixCandidate(place="A", why="w", refs=["s:1", "k:K11", "k:K99"]),
        FixCandidate(place="B", why="w", refs=["t:1"]),
        *[FixCandidate(place=f"C{i}", why="w", refs=["s:1"]) for i in range(4)],
    ]
    a = Analysis(headline="h", hypotheses=[h], fix_candidates=fixes)
    kept, invalid, _, _ = check_evidence(a, b.ids())
    assert kept == [] and invalid == 1
    checked = check_fix(a, b.fix_ids())
    assert [f.place for f in checked] == ["A", "C0", "C1"] and checked[0].refs == ["s:1", "k:K11"]


def test_old_answers_without_fix_candidates_still_parse():
    a = Analysis.model_validate_json(json.dumps({"headline": "h", "hypotheses": []}))
    assert a.fix_candidates == []


def test_user_message_lists_fix_ids():
    b = bundle(cards=[{"id": "k:K3"}])
    assert "고칠 곳 후보에 쓸 수 있는 id: k:K3" in user_message(b)


def _verdict(fixes) -> Verdict:
    h = Hypothesis(cause="원인" * 200, category="app_bug", confidence="high", evidence=["s:1"], next_check="n")
    return Verdict(headline="h", hypotheses=[h, h, h], deploy_related=False, unknown=False, invalid_evidence=0,
                   total_evidence=1, model="m", input_tokens=1, output_tokens=1, elapsed_s=1.0, fix_candidates=fixes)


def test_slack_shows_fix_candidates_with_card_summary_and_keeps_the_footer():
    b = bundle(cards=[{"id": "k:K11", "증상": "9호선 0/38역 도착 열차 0대", "날짜": "2026-10-10"}],
               code=[{"id": "s:1", "symbol": "SubwayDirectionNormalizer#fromPositionRow", "start_line": 1,
                      "end_line": 2, "revision": "f" * 40}])
    fix = FixCandidate(place="SubwayDirectionNormalizer", why="방향 반전 노선 추가", refs=["s:1", "k:K11"])
    short = render(_verdict([fix]).__class__(**{**_verdict([fix]).__dict__, "hypotheses": []}), b, link="https://g")
    assert "고칠 곳 후보" in short and "`k:K11` 9호선 0/38역 도착 열차 0대(10-10)" in short
    long = render(_verdict([fix]), b, link="https://g")
    assert len(long) <= MAX_TEXT and "Grafana Explore" in long
