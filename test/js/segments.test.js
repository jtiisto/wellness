// Unit tests for formatSegments (coach/utils.js) — the one mono line a cardio
// exercise's target-HR timeline renders as. Twinned by CoachNotationTest on the
// Android side (`formatSegments`); the two suites assert the same strings.
import test from 'node:test';
import assert from 'node:assert/strict';

import { formatSegments } from '../../public/js/coach/utils.js';

test('formatSegments: no timeline renders nothing', () => {
    assert.equal(formatSegments(undefined), '');
    assert.equal(formatSegments(null), '');
    assert.equal(formatSegments([]), '');
});

test('formatSegments: a range uses an en dash', () => {
    assert.equal(
        formatSegments([{ duration_sec: 300, hr_min: 125, hr_max: 140 }]),
        '5:00 125–140',
    );
});

test('formatSegments: min only is a floor, max only a ceiling', () => {
    assert.equal(formatSegments([{ duration_sec: 90, hr_min: 132 }]), '1:30 ≥132');
    assert.equal(formatSegments([{ duration_sec: 120, hr_max: 150 }]), '2:00 ≤150');
});

test('formatSegments: segments join with a middle dot, in order', () => {
    assert.equal(
        formatSegments([
            { duration_sec: 300, hr_min: 125, hr_max: 140, label: 'warmup' },
            { duration_sec: 180, hr_min: 160, hr_max: 175, label: 'hard' },
            { duration_sec: 120, hr_max: 150, label: 'easy' },
        ]),
        '5:00 125–140 · 3:00 160–175 · 2:00 ≤150',
    );
});

test('formatSegments: durations are M:SS, seconds zero-padded, minutes not', () => {
    assert.equal(
        formatSegments([
            { duration_sec: 5, hr_max: 110 },
            { duration_sec: 65, hr_max: 110 },
            { duration_sec: 600, hr_max: 110 },
            { duration_sec: 3661, hr_max: 110 },
        ]),
        '0:05 ≤110 · 1:05 ≤110 · 10:00 ≤110 · 61:01 ≤110',
    );
});

test('formatSegments: the label is not on this line', () => {
    // It is drawn against the segment in the live guide, not in the static
    // summary — the line is durations and bpm only.
    assert.equal(
        formatSegments([{ duration_sec: 60, hr_min: 140, label: 'HARD' }]),
        '1:00 ≥140',
    );
});

test('formatSegments: a segment with neither bound degrades to its duration', () => {
    // The server rejects this shape, so it can only arrive from a hand-edited
    // row; the line stays readable instead of emitting a broken token.
    assert.equal(formatSegments([{ duration_sec: 45 }]), '0:45');
});

test('formatSegments: a role changes nothing about the line', () => {
    // `role` is semantics for the Android guide — which spans a finished ride
    // averages its heart rate over, and which segment `+ 5 MIN` lengthens — and
    // it has no display meaning on either client. This line is the PWA's whole
    // stake in the field: it must read identically with the roles and without
    // them, including a value no client knows.
    const plain = [
        { duration_sec: 300, hr_max: 118 },
        { duration_sec: 2400, hr_min: 122, hr_max: 140 },
        { duration_sec: 300, hr_max: 118 },
    ];
    const roled = [
        { duration_sec: 300, hr_max: 118, role: 'warmup' },
        { duration_sec: 2400, hr_min: 122, hr_max: 140, role: 'work' },
        { duration_sec: 300, hr_max: 118, role: 'cooldown' },
    ];

    assert.equal(formatSegments(roled), formatSegments(plain));
    assert.equal(formatSegments(roled), '5:00 ≤118 · 40:00 122–140 · 5:00 ≤118');
    assert.equal(
        formatSegments([{ duration_sec: 60, hr_min: 140, role: 'sprint' }]),
        '1:00 ≥140',
    );
});

test('formatSegments: a zero bound is absent, not a bound of zero', () => {
    // JS-truthy guards, mirrored by the Kotlin twin: the server's floor is 1, so
    // a 0 is a hand-edited row, and "no bound" beats drawing "≥0".
    assert.equal(formatSegments([{ duration_sec: 45, hr_min: 0, hr_max: 130 }]), '0:45 ≤130');
    assert.equal(formatSegments([{ duration_sec: 45, hr_min: 0, hr_max: 0 }]), '0:45');
});
