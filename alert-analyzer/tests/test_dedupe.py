from __future__ import annotations

from datetime import datetime, timedelta, timezone

from analyzer.dedupe import DailyBudget, SeenStore

NOW = datetime(2026, 10, 26, 7, 0, tzinfo=timezone.utc)


def test_seen_key_expires_after_ttl():
    seen = SeenStore(ttl=timedelta(hours=24))

    assert seen.check_and_add("a", NOW)
    assert not seen.check_and_add("a", NOW + timedelta(hours=23))
    assert seen.check_and_add("a", NOW + timedelta(hours=25))


def test_discarded_key_can_be_taken_again():
    seen = SeenStore()
    seen.check_and_add("a", NOW)
    seen.discard("a")

    assert seen.check_and_add("a", NOW)


def test_oldest_keys_are_dropped_when_full():
    seen = SeenStore(max_entries=3)
    for i, key in enumerate("abcd"):
        seen.check_and_add(key, NOW + timedelta(seconds=i))

    assert len(seen) == 3
    assert seen.check_and_add("a", NOW + timedelta(seconds=10))


def test_budget_stops_at_limit_and_notices_once():
    budget = DailyBudget(limit=2)

    assert budget.try_spend(NOW)
    assert budget.try_spend(NOW)
    assert not budget.try_spend(NOW)
    assert budget.exhausted_notice_due(NOW)
    assert not budget.exhausted_notice_due(NOW)


def test_budget_resets_at_kst_midnight():
    budget = DailyBudget(limit=1)
    before_midnight = datetime(2026, 10, 26, 14, 59, tzinfo=timezone.utc)  # 23:59 KST
    after_midnight = datetime(2026, 10, 26, 15, 1, tzinfo=timezone.utc)  # 00:01 KST next day

    assert budget.try_spend(before_midnight)
    assert not budget.try_spend(before_midnight)
    assert budget.try_spend(after_midnight)
