// Unit tests for the shared DirtySet generation machinery (shared/dirty-set.js)
// — the one implementation behind journal trackers, journal entries, and coach
// dates. The per-module sync-logic tests exercise it through their wrappers;
// these pin the core algorithm directly.
import test from 'node:test';
import assert from 'node:assert/strict';
import { markDirty, clearApplied } from '../../public/js/shared/dirty-set.js';

test('markDirty: adds a new key and starts its generation at 1', () => {
    const r = markDirty([], {}, 'a');
    assert.deepEqual(r.keys, ['a']);
    assert.deepEqual(r.generations, { a: 1 });
});

test('markDirty: re-marking keeps membership but bumps the generation', () => {
    const r = markDirty(['a'], { a: 1 }, 'a');
    assert.deepEqual(r.keys, ['a']);
    assert.deepEqual(r.generations, { a: 2 });
});

test('markDirty: does not mutate its inputs', () => {
    const keys = ['a'];
    const gens = { a: 1 };
    markDirty(keys, gens, 'b');
    assert.deepEqual(keys, ['a']);
    assert.deepEqual(gens, { a: 1 });
});

test('clearApplied: clears applied keys with unchanged generations, drops their gens', () => {
    const r = clearApplied(['a', 'b'], { a: 1, b: 1 }, ['a'], { a: 1 });
    assert.deepEqual(r.keys, ['b']);
    assert.deepEqual(r.generations, { b: 1 });
});

test('clearApplied: a key re-modified mid-sync (gen advanced) stays dirty', () => {
    const r = clearApplied(['a'], { a: 2 }, ['a'], { a: 1 });
    assert.deepEqual(r.keys, ['a']);
    assert.deepEqual(r.generations, { a: 2 });
});

test('clearApplied: null snapshot skips the re-modification check', () => {
    const r = clearApplied(['a'], { a: 5 }, ['a'], null);
    assert.deepEqual(r.keys, []);
    assert.deepEqual(r.generations, {});
});

test('clearApplied: unapplied keys are untouched', () => {
    const r = clearApplied(['a', 'b'], { a: 1, b: 3 }, [], { a: 1, b: 3 });
    assert.deepEqual(r.keys, ['a', 'b']);
    assert.deepEqual(r.generations, { a: 1, b: 3 });
});
