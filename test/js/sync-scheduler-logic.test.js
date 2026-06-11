// Unit tests for the scheduler's pure decision logic (shared/sync-scheduler-logic.js):
// the backoff math and outcome routing whose bugs mean silently lost syncs.
import test from 'node:test';
import assert from 'node:assert/strict';
import { computeRetryDelay, classifySyncOutcome } from '../../public/js/shared/sync-scheduler-logic.js';

test('computeRetryDelay: exponential backoff doubles per attempt', () => {
    assert.equal(computeRetryDelay(0, 5000, 120000), 5000);
    assert.equal(computeRetryDelay(1, 5000, 120000), 10000);
    assert.equal(computeRetryDelay(2, 5000, 120000), 20000);
    assert.equal(computeRetryDelay(3, 5000, 120000), 40000);
});

test('computeRetryDelay: capped at maxMs', () => {
    assert.equal(computeRetryDelay(5, 5000, 120000), 120000);  // 160000 capped
    assert.equal(computeRetryDelay(50, 5000, 120000), 120000); // no overflow blowup
});

test('classifySyncOutcome: success and handled-conflicts reset retry state', () => {
    assert.equal(classifySyncOutcome({ success: true }), 'reset');
    assert.equal(classifySyncOutcome({ success: false, reason: 'conflicts' }), 'reset');
});

test('classifySyncOutcome: offline / already-syncing are not errors', () => {
    assert.equal(classifySyncOutcome({ success: false, reason: 'offline' }), 'skip');
    assert.equal(classifySyncOutcome({ success: false, reason: 'already syncing' }), 'skip');
});

test('classifySyncOutcome: failure with error object routes to error handling', () => {
    assert.equal(classifySyncOutcome({ success: false, error: new Error('x') }), 'error');
});

test('classifySyncOutcome: generic failure schedules a silent retry', () => {
    assert.equal(classifySyncOutcome({ success: false }), 'retry');
    assert.equal(classifySyncOutcome({ success: false, reason: 'mystery' }), 'retry');
});
