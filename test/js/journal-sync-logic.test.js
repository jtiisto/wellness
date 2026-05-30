// Unit tests for the pure journal sync-logic (extracted from journal/store.js).
// Pins the optimistic-concurrency upload contract and the dual tracker+entry
// dirty-clearing rule (the edit-during-sync race).
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    computeUploadPayload,
    computeClearedDirtyState,
} from '../../public/js/journal/sync-logic.js';

// ---- computeUploadPayload (the _baseLastModifiedAt contract) -------------

test('computeUploadPayload: existing tracker sends base token, never echoes lastModifiedAt', () => {
    const meta = { clientId: 'c1', dirtyTrackers: ['t1'], dirtyEntries: [] };
    const config = [{ id: 't1', name: 'Water', lastModifiedAt: '2026-05-01T00:00:00Z' }];
    const { payload, dirtyTrackerIds } = computeUploadPayload(meta, config, {});
    assert.equal(payload.config.length, 1);
    const item = payload.config[0];
    assert.equal(item._baseLastModifiedAt, '2026-05-01T00:00:00Z');
    assert.ok(!('lastModifiedAt' in item), 'top-level lastModifiedAt must not be echoed');
    assert.equal(item.name, 'Water');
    assert.deepEqual(dirtyTrackerIds, ['t1']);
});

test('computeUploadPayload: brand-new tracker omits _baseLastModifiedAt (insert-if-absent)', () => {
    const meta = { clientId: 'c1', dirtyTrackers: ['t1'], dirtyEntries: [] };
    const config = [{ id: 't1', name: 'New' }]; // no lastModifiedAt yet
    const { payload } = computeUploadPayload(meta, config, {});
    assert.ok(!('_baseLastModifiedAt' in payload.config[0]));
});

test('computeUploadPayload: dirty tracker missing from config is skipped (orphan guard)', () => {
    const meta = { clientId: 'c1', dirtyTrackers: ['gone'], dirtyEntries: [] };
    const { payload, dirtyTrackerIds } = computeUploadPayload(meta, [], {});
    assert.equal(payload.config.length, 0);
    assert.deepEqual(dirtyTrackerIds, []);
});

test('computeUploadPayload: entries carry base token; new entry omits it; orphan skipped', () => {
    const meta = {
        clientId: 'c1', dirtyTrackers: [],
        dirtyEntries: ['2026-05-01|t1', '2026-05-01|t2', '2026-05-02|gone'],
    };
    const logs = {
        '2026-05-01': {
            t1: { value: 5, completed: true, lastModifiedAt: '2026-05-01T00:00:00Z' },
            t2: { value: 3, completed: false }, // new, no token
        },
        // 2026-05-02 has no entry for the dirty key -> orphan, skipped
    };
    const { payload, dirtyEntryKeys } = computeUploadPayload(meta, [], logs);
    assert.equal(payload.days['2026-05-01'].t1._baseLastModifiedAt, '2026-05-01T00:00:00Z');
    assert.equal(payload.days['2026-05-01'].t1.value, 5);
    assert.ok(!('_baseLastModifiedAt' in payload.days['2026-05-01'].t2));
    assert.ok(!payload.days['2026-05-02']); // orphan dirty entry skipped
    assert.deepEqual(dirtyEntryKeys.sort(), ['2026-05-01|t1', '2026-05-01|t2']);
});

// ---- computeClearedDirtyState (dual tracker+entry dirty-clearing) --------

test('computeClearedDirtyState: uploaded + gen unchanged clears; gen advanced stays dirty', () => {
    const next = computeClearedDirtyState({
        uploadedTrackerIds: ['t1', 't2'],
        uploadedEntryKeys: ['d|e1', 'd|e2'],
        snapshotTrackerGens: { t1: 1, t2: 1 },
        snapshotEntryGens: { 'd|e1': 1, 'd|e2': 1 },
        dirtyTrackers: ['t1', 't2'],
        dirtyEntries: ['d|e1', 'd|e2'],
        dirtyTrackerGenerations: { t1: 2, t2: 1 },        // t1 re-modified mid-sync
        dirtyEntryGenerations: { 'd|e1': 1, 'd|e2': 2 },  // e2 re-modified mid-sync
    });
    assert.deepEqual(next.dirtyTrackers, ['t1']);  // t1 kept (gen advanced)
    assert.deepEqual(next.dirtyEntries, ['d|e2']); // e2 kept (gen advanced)
    assert.deepEqual(next.dirtyTrackerGenerations, { t1: 2 });
    assert.deepEqual(next.dirtyEntryGenerations, { 'd|e2': 2 });
});

test('computeClearedDirtyState: not-uploaded items stay dirty', () => {
    const next = computeClearedDirtyState({
        uploadedTrackerIds: ['t1'],
        uploadedEntryKeys: [],
        snapshotTrackerGens: { t1: 1 },
        snapshotEntryGens: {},
        dirtyTrackers: ['t1', 't2'],
        dirtyEntries: ['d|e1'],
        dirtyTrackerGenerations: { t1: 1, t2: 1 },
        dirtyEntryGenerations: { 'd|e1': 1 },
    });
    assert.deepEqual(next.dirtyTrackers, ['t2']);  // t1 cleared, t2 kept
    assert.deepEqual(next.dirtyEntries, ['d|e1']); // not uploaded -> kept
    assert.deepEqual(next.dirtyTrackerGenerations, { t2: 1 });
    assert.deepEqual(next.dirtyEntryGenerations, { 'd|e1': 1 });
});
