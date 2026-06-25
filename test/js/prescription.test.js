// Unit tests for buildPrescription (coach/utils.js) — the ordered token list
// for the compact prescription line (optional modifiers: RPE · load · tempo).
import test from 'node:test';
import assert from 'node:assert/strict';

import { buildPrescription } from '../../public/js/coach/utils.js';

test('buildPrescription: empty when no optional fields are set', () => {
    assert.deepEqual(buildPrescription({ target_sets: 3, target_reps: '5' }), []);
});

test('buildPrescription: full order is rpe, load, tempo', () => {
    assert.deepEqual(
        buildPrescription({ target_rpe: '6-7', target_load: '70%', tempo: '3-1-2-0' }),
        [
            { kind: 'rpe', value: '6-7' },
            { kind: 'load', value: '70%' },
            { kind: 'tempo', value: '3-1-2-0' },
        ],
    );
});

test('buildPrescription: omits absent tokens, preserves order', () => {
    assert.deepEqual(buildPrescription({ target_load: '24kg' }), [
        { kind: 'load', value: '24kg' },
    ]);
    assert.deepEqual(buildPrescription({ tempo: '30X1', target_rpe: '8' }), [
        { kind: 'rpe', value: '8' },
        { kind: 'tempo', value: '30X1' },
    ]);
});

test('buildPrescription: coerces values to string', () => {
    assert.deepEqual(buildPrescription({ target_rpe: 8 }), [{ kind: 'rpe', value: '8' }]);
});
