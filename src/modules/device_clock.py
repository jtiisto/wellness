"""Device clock — which timezone the watch was in, at any instant.

The server never assumes its own timezone is the watch's (docs/ARCHITECTURE.md
"Device clock"). Garmin's daily rows are keyed by the DEVICE's local day and
the wrist stream is true-UTC epochs, so the one fact the server has to know is
the zone in force on the device at a given instant — and the phone, which
travels with the watch, reports it on every request (`X-Client-Zone` /
`X-Client-Offset-Min`). The `garmin` module records the change points; this
module turns them into a total function of time.

Nothing here imports FastAPI or sqlite beyond the loader it borrows from the
garmin module: it is pure arithmetic over `(observed_at, zone_id, offset_min)`
triples, so every rule below is unit-testable without a request or a database.

Two rules do all the work:

- **Device-local date of an instant** (`local_date_of`) — the date of that
  instant under the segment's zone. `zoneinfo` resolves DST exactly, so the
  reported offset is only a cross-check and the fallback for a zone id this
  host's tz database does not know.
- **UTC of a device-local wall time** (`utc_of_local`) — the instant whose
  device-local wall clock equals it *under the zone in force at that instant*.
  When two instants qualify (a change point inside the day, or a DST fold) the
  EARLIER one wins. When none does — a wall time the device never showed, e.g.
  the hour a spring-forward skips, or one stranded by a zone jump — the answer
  is None and the caller drops the row it came from.

Before the first observation the zone is the SERVER's own, which is exactly
the behaviour that predates this module: history and a headerless deployment
read as they always did.
"""
import logging
from bisect import bisect_right
from datetime import date, datetime, time, timedelta, timezone, tzinfo
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

logger = logging.getLogger(__name__)

_EPOCH_UTC = datetime(1970, 1, 1, tzinfo=timezone.utc)

# Widest real UTC offset (±14 h) with an hour of slack. A device-local wall
# clock can only be this far from the same reading taken as UTC, which is what
# makes the coarse window below a provable superset of the answers.
_OFFSET_BOUND_MS = int(timedelta(hours=15).total_seconds() * 1000)


class _HostLocalZone(tzinfo):
    """The host's own local zone, as the platform itself computes it.

    Deliberately NOT a `ZoneInfo`: Python has no portable way to name the
    host's IANA zone, and what this segment must reproduce byte for byte is
    sqlite's `date(..., 'localtime')` and `datetime.fromtimestamp()` — the
    platform's conversion, DST history and all. Delegating to those two
    functions *is* that behaviour, on every OS, with no zone-id guessing.

    Stateless, so one shared instance serves every timeline.
    """

    def fromutc(self, dt):
        """UTC wall clock (tzinfo=self) -> local wall clock (tzinfo=self)."""
        stamp = (dt.replace(tzinfo=timezone.utc) - _EPOCH_UTC).total_seconds()
        return datetime.fromtimestamp(stamp).replace(tzinfo=self)

    def utcoffset(self, dt):
        """Offset at a LOCAL wall clock. A naive datetime's `.timestamp()` is
        the platform's local->UTC conversion, fold included, so the offset is
        simply the difference it implies."""
        if dt is None:
            return None
        naive = dt.replace(tzinfo=None)
        as_utc = datetime.fromtimestamp(naive.timestamp(), timezone.utc)
        return naive - as_utc.replace(tzinfo=None)

    def dst(self, dt):
        # Unknown rather than wrong: nothing here needs the DST *component*
        # (fromutc is overridden and utcoffset is exact), and inventing one
        # would be a second, drifting source of truth.
        return None

    def tzname(self, dt):
        return None

    def __repr__(self):
        return "HostLocalZone()"


HOST_LOCAL_ZONE = _HostLocalZone()


def resolve_zone(zone_id, offset_min) -> tzinfo:
    """A reported zone as a tzinfo: the IANA zone when this host knows it,
    else the fixed offset the phone sent alongside it.

    The offset is the FALLBACK, never the primary: a real zone id carries the
    DST rules, which a single offset cannot.
    """
    try:
        return ZoneInfo(zone_id)
    except (ZoneInfoNotFoundError, ValueError, TypeError):
        try:
            return timezone(timedelta(minutes=int(offset_min)))
        except (TypeError, ValueError):
            return timezone.utc


class ZoneTimeline:
    """The device's zone as a total function of time.

    Segments: the server's own zone from -infinity, then one per recorded
    change point, opening at that point's `observed_at`. Construct from
    `(observed_at_utc, zone_id, offset_min)` triples — `load()` reads them from
    the garmin module's DB, and an empty list is the pre-header behaviour.
    """

    def __init__(self, server_zone: tzinfo, points):
        # Normalize BEFORE sorting: a naive stamp and an aware one cannot be
        # compared at all (TypeError), and a list mixing the two is exactly
        # what a hand-built timeline or a half-migrated row yields. Naive is
        # read as UTC, the repo's rule; aware is converted, not re-labelled.
        normalized = []
        for observed_at, zone_id, offset_min in points:
            if observed_at.tzinfo is None:
                observed_at = observed_at.replace(tzinfo=timezone.utc)
            else:
                observed_at = observed_at.astimezone(timezone.utc)
            normalized.append((observed_at, zone_id, offset_min))
        normalized.sort(key=lambda p: p[0])

        self._points = normalized
        self.server_zone = server_zone
        # Parallel arrays. `_zones[0]` runs from -inf; `_boundaries[i]` is the
        # epoch-ms instant at which `_zones[i + 1]` takes over.
        self._zones = [server_zone]
        self._boundaries = []
        for observed_at, zone_id, offset_min in normalized:
            self._boundaries.append(int(observed_at.timestamp() * 1000))
            self._zones.append(resolve_zone(zone_id, offset_min))

    # ---- construction ----------------------------------------------------

    @classmethod
    def empty(cls, server_zone: tzinfo = None) -> "ZoneTimeline":
        """The pre-header timeline: the server's zone, everywhere, forever."""
        return cls(server_zone or HOST_LOCAL_ZONE, [])

    @classmethod
    def load(cls, db_path, server_zone: tzinfo = None) -> "ZoneTimeline":
        """Read the change points from the garmin module's DB.

        An absent file, an unmigrated DB or a read error all mean the same
        thing — no observations — and yield the empty (server-zone) timeline
        rather than an error: a deployment with no Android client, or one
        whose first request has not landed yet, must read exactly as it did
        before this module existed.
        """
        # Imported here, not at module scope, so this module stays pure: the
        # rules above are arithmetic anyone can import without pulling FastAPI
        # and sqlite in behind them.
        from modules.garmin import load_zone_timeline

        points = load_zone_timeline(db_path) if db_path is not None else []
        return cls(server_zone or HOST_LOCAL_ZONE, points)

    # ---- introspection ---------------------------------------------------

    @property
    def points(self):
        """The change points behind this timeline, oldest first."""
        return list(self._points)

    @property
    def fingerprint(self) -> str:
        """A cheap identity for the timeline's CONTENT.

        Carried in the strain memo's marker: a new change point must force a
        rescan rather than serve days bucketed under the old zone. Length plus
        the newest `observed_at` is enough — points are append-only and a
        change point is stamped to the second.
        """
        last = self._points[-1][0].isoformat() if self._points else ""
        return f"{len(self._points)}:{last}"

    def __len__(self):
        return len(self._points)

    # ---- the two rules ---------------------------------------------------

    def _segment_index(self, ts_ms: int) -> int:
        return bisect_right(self._boundaries, ts_ms)

    def _segment_span(self, index):
        """(start_ms, end_ms) of a segment; None on either side means open."""
        start = self._boundaries[index - 1] if index > 0 else None
        end = (self._boundaries[index]
               if index < len(self._boundaries) else None)
        return start, end

    def zone_at(self, t_utc: datetime) -> tzinfo:
        """The device's zone at an instant."""
        return self._zones[self._segment_index(_to_ms(t_utc))]

    def local_date_of(self, t_utc: datetime) -> date:
        """The device-local calendar date of an instant.

        The input is normalized ONCE, up front. Doing it per use would have
        segment selection read a naive input as UTC (via `_to_ms`) while
        `astimezone` read the same value as server-local — two different
        instants inside one call.
        """
        if t_utc.tzinfo is None:
            t_utc = t_utc.replace(tzinfo=timezone.utc)
        return t_utc.astimezone(self.zone_at(t_utc)).date()

    def local_date_of_ms(self, ts_ms) -> date:
        """`local_date_of` for an epoch-ms integer — one datetime construction
        instead of two, which matters at a quarter-million wrist samples."""
        zone = self._zones[self._segment_index(ts_ms)]
        return datetime.fromtimestamp(ts_ms / 1000, tz=zone).date()

    def utc_of_local(self, naive_local: datetime, hint: date = None):
        """The instant whose device-local wall clock is `naive_local`.

        The zone that counts is the one in force AT THAT INSTANT, which is
        what makes this more than an offset subtraction: a candidate computed
        under a segment's zone only counts when it actually lands inside that
        segment. Two qualifying instants (a change point inside the day, or an
        autumn DST fold) resolve to the EARLIER one. None qualifying — a wall
        clock the device never displayed — returns None, and the caller drops
        the row whole rather than placing it on a guess.

        BOTH sides of an autumn fold are tried, not just the earlier one. The
        earlier side can be excluded by a segment boundary while the later one
        qualifies — a change point landing inside the repeated hour is exactly
        that case — and trying only `fold=0` would report "never displayed"
        about a wall clock the watch displayed twice. Outside a fold the two
        candidates are the same instant, so the rule stays "earliest wins".

        `hint` is the device-local calendar date the caller expects (Garmin's
        own `activity_date`). It only widens the coarse segment filter below;
        the span test is what decides, so the answer is the same with or
        without it.
        """
        if naive_local.tzinfo is not None:
            # Already an instant: an offset-bearing stamp is honored as
            # written rather than re-interpreted (modules/garmin's rule).
            return naive_local.astimezone(timezone.utc)

        # A coarse pre-filter over the segments. It is anchored on the wall
        # clock READ AS UTC — every qualifying instant is within one real
        # offset of that, always — so the window is a superset of the answers
        # no matter what the hint says, and a hint that disagrees with the wall
        # clock's own date can only widen it. That matters: this filter is an
        # optimization, and an over-tight one would silently drop the row.
        anchors = [_to_ms(naive_local.replace(tzinfo=timezone.utc))]
        if hint is not None:
            day = datetime.combine(hint, time.min, tzinfo=timezone.utc)
            anchors += [_to_ms(day), _to_ms(day + timedelta(days=1))]
        window = (min(anchors) - _OFFSET_BOUND_MS,
                  max(anchors) + _OFFSET_BOUND_MS)

        best = None
        for index, zone in enumerate(self._zones):
            start, end = self._segment_span(index)
            if start is not None and start >= window[1]:
                continue
            if end is not None and end <= window[0]:
                continue
            for fold in (0, 1):
                candidate = naive_local.replace(tzinfo=zone, fold=fold)
                try:
                    as_utc = candidate.astimezone(timezone.utc)
                except (OSError, OverflowError, ValueError):
                    continue
                # A wall clock inside a spring-forward gap round-trips to a
                # DIFFERENT wall clock, under either fold: it never existed in
                # this zone. (Naive comparison ignores `fold`, which is what
                # lets the fold-1 side of an autumn repeat round-trip cleanly.)
                if as_utc.astimezone(zone).replace(tzinfo=None) != naive_local:
                    continue
                ms = _to_ms(as_utc)
                if start is not None and ms < start:
                    continue
                if end is not None and ms >= end:
                    continue
                if best is None or as_utc < best:
                    best = as_utc
        return best

    # ---- bulk bucketing --------------------------------------------------

    def bucketer(self):
        """A callable `f(ts_ms) -> 'YYYY-MM-DD' | None` for a run of samples.

        `local_date_of_ms` is exact but costs a timezone conversion per call,
        and the wrist stream's cold scan is a quarter-million samples. This
        closure caches the epoch-ms window of the local day it last resolved —
        clipped to the enclosing segment, so a mid-day change point still
        splits the day — and answers everything inside it with two integer
        comparisons. The window is only adopted once both its edges have been
        verified to carry the same local date, so a zone whose local midnight
        a DST jump skipped falls back to the exact path instead of caching a
        boundary that does not exist.

        None means the instant is not representable on this host (an absurd
        timestamp); the caller skips that sample rather than bucketing it
        under a name no metric date can match.
        """
        state = {"lo": None, "hi": None, "iso": None}

        def bucket(ts_ms):
            lo, hi = state["lo"], state["hi"]
            if lo is not None and lo <= ts_ms < hi:
                return state["iso"]
            index = self._segment_index(ts_ms)
            zone = self._zones[index]
            try:
                day = datetime.fromtimestamp(ts_ms / 1000, tz=zone).date()
            except (OSError, OverflowError, ValueError):
                state["lo"] = state["hi"] = None
                return None
            iso = day.isoformat()
            state["lo"] = state["hi"] = None
            try:
                day_lo = _to_ms(datetime.combine(day, time.min, tzinfo=zone))
                day_hi = _to_ms(datetime.combine(
                    day + timedelta(days=1), time.min, tzinfo=zone))
                edges_agree = (
                    datetime.fromtimestamp(day_lo / 1000, tz=zone).date() == day
                    and datetime.fromtimestamp(
                        (day_hi - 1) / 1000, tz=zone).date() == day)
            except (OSError, OverflowError, ValueError):
                return iso
            if edges_agree and day_lo <= ts_ms < day_hi:
                seg_lo, seg_hi = self._segment_span(index)
                state["lo"] = day_lo if seg_lo is None else max(day_lo, seg_lo)
                state["hi"] = day_hi if seg_hi is None else min(day_hi, seg_hi)
                state["iso"] = iso
            return iso

        return bucket


def _to_ms(t: datetime) -> int:
    """An aware datetime as epoch milliseconds (naive input is read as UTC)."""
    if t.tzinfo is None:
        t = t.replace(tzinfo=timezone.utc)
    return int(t.timestamp() * 1000)
