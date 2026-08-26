# Fitted constants for the sleep need/debt ledger (/api/trends/health/sleep).
#
# Copy this file to sleep_params.py and fill in your own fitted values.
# sleep_params.py is gitignored and will not be committed: the constants are
# derived from private health data (a personal sleep history regressed against
# the Garmin daily metrics), so they are personal data even though they are
# only numbers.
#
# Without sleep_params.py the endpoint degrades to {"available": false} — the
# values below are deliberately absurd placeholders that document the SHAPE
# only, and are never used as a fallback.

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
class SleepParams:
    baseline_min: float
    debt_half_weight: float     # fraction of last night's shortfall carried as debt
    debt_cap_min: float
    strain_quad_a: float        # strain term = a*s^2 + b*s   (s = day strain 0..21)
    strain_quad_b: float
    sleep_bias_min: float       # subtracted from Garmin sleep minutes before debt math
    strain_coeffs: StrainCoeffs      # primary estimator (needs max_heart_rate)
    strain_fallback: StrainFallback  # used when max_heart_rate is NULL


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
)
