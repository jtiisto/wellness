"""Typed interval-intent input for workout analysis."""

from dataclasses import asdict, dataclass
from typing import Any


@dataclass(frozen=True)
class IntervalIntent:
    source: str = "unknown"
    rounds: int | None = None
    work_duration_s: int | None = None
    rest_duration_s: int | None = None
    target_hr_min: int | None = None
    target_hr_max: int | None = None
    modality: str | None = None
    notes: str | None = None

    @property
    def has_structure(self) -> bool:
        return (
            self.rounds is not None
            and self.work_duration_s is not None
            and self.rest_duration_s is not None
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def parse_intent(value: dict[str, Any] | None) -> IntervalIntent:
    """Validate caller-supplied interval intent.

    Unknown intent is a normal state and returns a source="unknown" model.
    """
    if value is None:
        return IntervalIntent()
    if not isinstance(value, dict):
        raise ValueError("intent must be an object")

    source = value.get("source") or "user_supplied"
    if source not in ("coach", "user_supplied", "inferred", "unknown"):
        raise ValueError("intent.source must be coach, user_supplied, inferred, or unknown")

    rounds = _optional_positive_int(value, "rounds")
    work_duration_s = _optional_positive_int(value, "work_duration_s")
    rest_duration_s = _optional_positive_int(value, "rest_duration_s")
    target_hr_min = _optional_positive_int(value, "target_hr_min")
    target_hr_max = _optional_positive_int(value, "target_hr_max")

    structured_values = (rounds, work_duration_s, rest_duration_s)
    if any(v is not None for v in structured_values) and not all(
        v is not None for v in structured_values
    ):
        raise ValueError(
            "rounds, work_duration_s, and rest_duration_s must be supplied together"
        )
    if target_hr_min is not None and target_hr_max is not None and target_hr_min > target_hr_max:
        raise ValueError("target_hr_min must be <= target_hr_max")

    return IntervalIntent(
        source=source,
        rounds=rounds,
        work_duration_s=work_duration_s,
        rest_duration_s=rest_duration_s,
        target_hr_min=target_hr_min,
        target_hr_max=target_hr_max,
        modality=_optional_str(value, "modality"),
        notes=_optional_str(value, "notes"),
    )


def _optional_positive_int(value: dict[str, Any], key: str) -> int | None:
    raw = value.get(key)
    if raw is None:
        return None
    if isinstance(raw, bool):
        raise ValueError(f"{key} must be an integer")
    try:
        parsed = int(raw)
    except (TypeError, ValueError):
        raise ValueError(f"{key} must be an integer")
    if parsed < 1:
        raise ValueError(f"{key} must be >= 1")
    return parsed


def _optional_str(value: dict[str, Any], key: str) -> str | None:
    raw = value.get(key)
    if raw is None:
        return None
    text = str(raw).strip()
    return text or None
