"""In-memory bookkeeping: which alerts were already taken, and the daily cap.

Both reset when the container restarts. Losing them only means one extra
analysis of an alert that is still firing, so nothing is persisted.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

# Korea has no daylight saving time, so a fixed offset is exact (and avoids
# depending on tzdata being present in the slim image).
KST = timezone(timedelta(hours=9))


class SeenStore:
    def __init__(self, ttl: timedelta = timedelta(hours=24), max_entries: int = 5000):
        self._ttl = ttl
        self._max_entries = max_entries
        self._seen: dict[str, datetime] = {}

    def check_and_add(self, key: str, now: datetime) -> bool:
        """True the first time a key is seen within the TTL."""
        self._evict(now)
        if key in self._seen:
            return False
        self._seen[key] = now
        return True

    def discard(self, key: str) -> None:
        self._seen.pop(key, None)

    def __len__(self) -> int:
        return len(self._seen)

    def _evict(self, now: datetime) -> None:
        cutoff = now - self._ttl
        for key in [k for k, at in self._seen.items() if at < cutoff]:
            del self._seen[key]
        # dicts keep insertion order, so the first keys are the oldest.
        while len(self._seen) >= self._max_entries:
            del self._seen[next(iter(self._seen))]


class DailyBudget:
    """At most `limit` analyses per KST calendar day."""

    def __init__(self, limit: int):
        self._limit = limit
        self._day: date | None = None
        self._used = 0
        self._notified = False

    def try_spend(self, now: datetime) -> bool:
        self._roll(now)
        if self._used >= self._limit:
            return False
        self._used += 1
        return True

    def exhausted_notice_due(self, now: datetime) -> bool:
        """True once per day, the first time the cap is hit."""
        self._roll(now)
        if self._used >= self._limit and not self._notified:
            self._notified = True
            return True
        return False

    def _roll(self, now: datetime) -> None:
        today = now.astimezone(KST).date()
        if today != self._day:
            self._day = today
            self._used = 0
            self._notified = False
