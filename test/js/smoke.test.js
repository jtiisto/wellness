// Confirms the node:test harness is wired up and runs the test/js/ suite.
// Real coverage lives in coach-sync-logic.test.js / journal-sync-logic.test.js.
import test from 'node:test';
import assert from 'node:assert/strict';

test('node:test harness runs', () => {
  assert.equal(1 + 1, 2);
});
