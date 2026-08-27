# Fitted constants for the sleep need/debt ledger (/api/trends/health/sleep).
#
# Copy this file to sleep_params.py and fill in your own fitted values.
# sleep_params.py is gitignored and will not be committed: the constants are
# derived from private health data (a personal sleep history regressed against
# Garmin daily metrics, the all-day heart-rate stream, and activity records),
# so they are personal data even though they are only numbers.
#
# Without sleep_params.py the endpoint degrades to {"available": false} — the
# values below are deliberately absurd placeholders that document the SHAPE
# only, and are never used as a fallback.
#
# The day-strain estimator is TIERED, best source first, decided per calendar
# day (see trends_queries._strain_estimate):
#   1. `hybrid`         — Banister TRIMP over the all-day wrist HR stream plus
#                         the day's activity windows (strap-quality averages),
#                         each weighted separately. PRIMARY.
#   2. `strain_coeffs`  — the daily-feature regression (needs max_heart_rate).
#   3. `strain_fallback`— calorie-only, when the day has no max_heart_rate.
# A day drops a tier when its inputs are absent, not the whole response.

from dataclasses import dataclass


@dataclass(frozen=True)
class StrainCoeffs:
    log_ac: float
    steps_k: float
    max_hr: float
    intercept: float


@dataclass(frozen=True)
class StrainFallback:
    slope: float
    intercept: float


@dataclass(frozen=True)
class HybridStrainCoeffs:
    ban_out: float           # weight on wrist TRIMP accrued OUTSIDE activities
    ban_act: float           # weight on strap TRIMP (activity avg_hr x duration)
    intercept: float
    hr_max: float            # personal HR max, the Banister exponent's ceiling
    rest_hr_fallback: float  # used on a day with no resting_heart_rate
    min_samples: int         # wrist samples/day below which the day drops a tier


@dataclass(frozen=True)
class SleepParams:
    baseline_min: float
    debt_half_weight: float     # fraction of last night's shortfall carried as debt
    debt_cap_min: float
    strain_quad_a: float        # strain term = a*s^2 + b*s   (s = day strain 0..21)
    strain_quad_b: float
    sleep_bias_min: float       # subtracted from Garmin sleep minutes before debt math
    strain_coeffs: StrainCoeffs      # tier 2 (needs max_heart_rate)
    strain_fallback: StrainFallback  # tier 3 (max_heart_rate is NULL)
    hybrid: HybridStrainCoeffs       # tier 1 — primary


PARAMS = SleepParams(
    baseline_min=500.0,
    debt_half_weight=0.25,
    debt_cap_min=60.0,
    strain_quad_a=1.0,
    strain_quad_b=-5.0,
    sleep_bias_min=0.0,
    strain_coeffs=StrainCoeffs(
        log_ac=1.0, steps_k=5.0, max_hr=0.5, intercept=-50.0),
    strain_fallback=StrainFallback(slope=0.1, intercept=10.0),
    hybrid=HybridStrainCoeffs(
        ban_out=1.0, ban_act=2.0, intercept=0.0,
        hr_max=200.0, rest_hr_fallback=50.0, min_samples=100),
)
