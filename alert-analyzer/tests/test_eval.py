from __future__ import annotations

import pytest

from analyzer.llm import Hypothesis, Verdict
from eval.run_eval import (
    llm_hit,
    load_bundle,
    load_cases,
    main,
    rule_baseline,
    score,
    summarize,
)

CASES = {c.id: c for c in load_cases()}


def verdict(*hypotheses: tuple[str, str, list[str]], invalid: int = 0) -> Verdict:
    return Verdict(
        headline="h",
        hypotheses=[
            Hypothesis(cause="c", category=cat, confidence=conf, evidence=ev, next_check="n")
            for cat, conf, ev in hypotheses
        ],
        deploy_related=False,
        unknown=not hypotheses,
        invalid_evidence=invalid,
        total_evidence=invalid + sum(len(ev) for _, _, ev in hypotheses),
        model="claude-sonnet-5-5",
        input_tokens=1000,
        output_tokens=100,
        elapsed_s=2.0,
    )


@pytest.mark.parametrize("case", CASES.values(), ids=CASES.keys())
def test_every_must_cite_id_exists_in_its_fixture(case):
    bundle = load_bundle(case)

    assert set(case.must_cite) <= bundle.ids()


def test_confound_case_adds_a_restart_to_real_logs():
    assert rule_baseline(load_bundle(CASES["subway-api-503"])) == "external_api"
    assert rule_baseline(load_bundle(CASES["subway-503-with-restart"])) == "deploy_restart"


@pytest.mark.parametrize(
    ("case_id", "baseline"),
    [
        ("api-5xx-deploy", "deploy_restart"),
        ("otp-fail-deploy", "deploy_restart"),
        ("memory-low-1021", "unknown"),
        ("quiet", "unknown"),  # its only client_fail series is 0
    ],
)
def test_rule_baseline(case_id, baseline):
    assert rule_baseline(load_bundle(CASES[case_id])) == baseline


@pytest.mark.parametrize(
    ("expect", "v", "hit"),
    [
        ("external_api", verdict(("external_api", "high", ["m:up"])), True),
        ("external_api", verdict(("deploy_restart", "high", ["m:restarts"]), ("external_api", "high", ["m:up"])), False),
        ("unknown", verdict(), True),
        ("unknown", verdict(("resource_exhaustion", "low", ["m:memory_available_mib"])), True),
        ("unknown", verdict(("resource_exhaustion", "medium", ["m:memory_available_mib"])), False),
        ("none", verdict(), True),
        ("none", verdict(("app_bug", "low", ["t:1"])), False),
    ],
)
def test_llm_hit(expect, v, hit):
    assert llm_hit(expect, v) is hit


def test_score_and_summary():
    case = CASES["subway-503-with-restart"]
    good = score(case, load_bundle(case), verdict(("external_api", "high", ["t:118541be"]), invalid=1))
    failed = score(CASES["quiet"], load_bundle(CASES["quiet"]), None, "시간 초과")

    assert (good["llm_hit"], good["baseline_hit"], good["cited"]) == (True, False, True)
    summary = summarize([good, failed])
    assert summary["llm_hits"] == 1
    assert summary["baseline_hits"] == 1
    assert summary["must_cite_hits"] == "1/1"
    assert summary["invalid_evidence"] == "1/2"
    assert summary["failures"] == ["quiet"]


def test_main_without_yes_spends_nothing(monkeypatch, capsys):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)

    assert main([]) == 0

    err = capsys.readouterr().err
    assert "6 cases" in err and "pass --yes" in err
