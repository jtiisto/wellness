// Unit tests for the pure journal sync-logic (extracted from journal/store.js).
// Pins the optimistic-concurrency upload contract and the dual tracker+entry
// dirty-clearing rule (the edit-during-sync race).
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    computeUploadPayload,
    computeClearedDirtyState,
    computeAcceptedApply,
    computeRejectedApply,
    computeDropDeletedTrackers,
    computePruneDeletedTrackers,
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

// ---- computeAcceptedApply (stamp server tokens onto accepted rows) -------

test('computeAcceptedApply: stamps lastModifiedAt onto matching trackers and entries', () => {
    const config = [
        { id: 't1', name: 'Water', lastModifiedAt: 'old' },
        { id: 't2', name: 'Sleep', lastModifiedAt: 'old' },
    ];
    const logs = {
        '2026-05-01': { t1: { value: 5, completed: true, lastModifiedAt: 'old' } },
    };
    const next = computeAcceptedApply(
        [{ id: 't1', lastModifiedAt: 'new-t1' }],
        [{ date: '2026-05-01', trackerId: 't1', lastModifiedAt: 'new-e1' }],
        config, logs,
    );
    assert.equal(next.trackerConfig.find(t => t.id === 't1').lastModifiedAt, 'new-t1');
    assert.equal(next.trackerConfig.find(t => t.id === 't2').lastModifiedAt, 'old'); // untouched
    assert.equal(next.dailyLogs['2026-05-01'].t1.lastModifiedAt, 'new-e1');
    assert.equal(next.dailyLogs['2026-05-01'].t1.value, 5); // other fields preserved
});

test('computeAcceptedApply: skips accepted entries not present locally', () => {
    const logs = { '2026-05-01': { t1: { value: 1, completed: false } } };
    const next = computeAcceptedApply(
        [],
        [{ date: '2026-05-01', trackerId: 'gone', lastModifiedAt: 'x' }],
        [], logs,
    );
    assert.ok(!next.dailyLogs['2026-05-01'].gone); // not created
    assert.equal(next.dailyLogs['2026-05-01'].t1.value, 1);
});

test('computeAcceptedApply: empty inputs return the same references (no spurious write)', () => {
    const config = [{ id: 't1' }];
    const logs = { '2026-05-01': {} };
    const next = computeAcceptedApply([], [], config, logs);
    assert.equal(next.trackerConfig, config);
    assert.equal(next.dailyLogs, logs);
});

// ---- computeRejectedApply (recover from rejected uploads in-cycle) -------

test('computeRejectedApply: upserts non-deleted serverRow; appends when absent', () => {
    const config = [{ id: 't1', name: 'stale' }];
    const next = computeRejectedApply(
        [
            { id: 't1', serverRow: { id: 't1', name: 'server-wins', lastModifiedAt: 's1' } },
            { id: 't2', serverRow: { id: 't2', name: 'brand-new', lastModifiedAt: 's2' } },
        ],
        [], config, {},
    );
    assert.equal(next.trackerConfig.find(t => t.id === 't1').name, 'server-wins');
    assert.equal(next.trackerConfig.find(t => t.id === 't2').name, 'brand-new'); // appended
    assert.deepEqual(next.trackerIdsToDelete, []);
});

test('computeRejectedApply: soft-deleted serverRow routes to trackerIdsToDelete, not upsert', () => {
    const config = [{ id: 't1', name: 'keep-me' }];
    const next = computeRejectedApply(
        [{ id: 't1', serverRow: { id: 't1', deleted: true } }],
        [], config, {},
    );
    assert.deepEqual(next.trackerIdsToDelete, ['t1']);
    // not upserted: the deleted row is left in config for dropDeletedTrackerIds to remove
    assert.equal(next.trackerConfig.find(t => t.id === 't1').name, 'keep-me');
});

test('computeRejectedApply: overwrites rejected entry with serverRow value/completed/stamp', () => {
    const logs = { '2026-05-01': { t1: { value: 99, completed: false, extra: 'dropped' } } };
    const next = computeRejectedApply(
        [],
        [{ date: '2026-05-01', trackerId: 't1', serverRow: { value: 7, completed: true, lastModifiedAt: 's1' } }],
        [], logs,
    );
    assert.deepEqual(next.dailyLogs['2026-05-01'].t1, { value: 7, completed: true, lastModifiedAt: 's1' });
});

test('computeRejectedApply: rejection with no serverRow is skipped', () => {
    const config = [{ id: 't1', name: 'orig' }];
    const next = computeRejectedApply(
        [{ id: 't1' }], // no serverRow
        [], config, {},
    );
    assert.equal(next.trackerConfig.find(t => t.id === 't1').name, 'orig');
    assert.deepEqual(next.trackerIdsToDelete, []);
});

// ---- computeDropDeletedTrackers (server-side delete cleanup) --------------

test('computeDropDeletedTrackers: drops config + entries + dirty (tracker & entry-level) + gens', () => {
    const config = [{ id: 't1' }, { id: 't2' }];
    const logs = {
        '2026-05-01': { t1: { value: 1 }, t2: { value: 2 } },
        '2026-05-02': { t2: { value: 3 } },
    };
    const meta = {
        dirtyTrackers: ['t1', 't2'],
        dirtyEntries: ['2026-05-01|t1', '2026-05-02|t2'],
        dirtyTrackerGenerations: { t1: 1, t2: 1 },
        dirtyEntryGenerations: { '2026-05-01|t1': 1, '2026-05-02|t2': 1 },
    };
    const next = computeDropDeletedTrackers(['t1'], config, logs, meta);

    assert.deepEqual(next.trackerConfig, [{ id: 't2' }]);            // t1 removed
    assert.ok(!('t1' in next.dailyLogs['2026-05-01']));             // t1 entry removed
    assert.equal(next.dailyLogs['2026-05-01'].t2.value, 2);        // t2 entry kept
    assert.equal(next.logsChanged, true);
    assert.equal(next.dirtyChanged, true);
    assert.deepEqual(next.meta.dirtyTrackers, ['t2']);
    assert.deepEqual(next.meta.dirtyEntries, ['2026-05-02|t2']);   // entry matched by key split
    assert.deepEqual(next.meta.dirtyTrackerGenerations, { t2: 1 });
    assert.deepEqual(next.meta.dirtyEntryGenerations, { '2026-05-02|t2': 1 });
});

test('computeDropDeletedTrackers: dirtyChanged false when no dirty matched the deleted id', () => {
    const config = [{ id: 't1' }, { id: 't2' }];
    const logs = { '2026-05-01': { t1: { value: 1 } } };
    const meta = {
        dirtyTrackers: ['t2'],
        dirtyEntries: ['2026-05-01|t2'],
        dirtyTrackerGenerations: { t2: 1 },
        dirtyEntryGenerations: { '2026-05-01|t2': 1 },
    };
    const next = computeDropDeletedTrackers(['t1'], config, logs, meta);

    assert.deepEqual(next.trackerConfig, [{ id: 't2' }]); // config always pruned
    assert.equal(next.logsChanged, true);                 // t1 entry dropped
    assert.equal(next.dirtyChanged, false);               // no dirty t1 state existed
    // generations untouched (the conditional gen-prune block was skipped)
    assert.deepEqual(next.meta.dirtyTrackerGenerations, { t2: 1 });
    assert.deepEqual(next.meta.dirtyEntryGenerations, { '2026-05-01|t2': 1 });
});

// ---- computePruneDeletedTrackers (local _deleted cleanup post-sync) -------

test('computePruneDeletedTrackers: removes _deleted trackers and their entries', () => {
    const config = [{ id: 't1', _deleted: true }, { id: 't2' }];
    const logs = {
        '2026-05-01': { t1: { value: 1 }, t2: { value: 2 } },
        '2026-05-02': { t2: { value: 3 } },
    };
    const next = computePruneDeletedTrackers(config, logs);
    assert.deepEqual(next.trackerConfig, [{ id: 't2' }]);
    assert.ok(!('t1' in next.dailyLogs['2026-05-01']));
    assert.equal(next.dailyLogs['2026-05-01'].t2.value, 2);
    assert.equal(next.dailyLogs['2026-05-02'].t2.value, 3); // day without t1 untouched
    assert.equal(next.logsChanged, true);
});

test('computePruneDeletedTrackers: returns null when no _deleted trackers', () => {
    const next = computePruneDeletedTrackers([{ id: 't1' }], { '2026-05-01': { t1: {} } });
    assert.equal(next, null);
});

test('computePruneDeletedTrackers: logsChanged false when deleted tracker has no entries', () => {
    const config = [{ id: 't1', _deleted: true }, { id: 't2' }];
    const logs = { '2026-05-01': { t2: { value: 2 } } };
    const next = computePruneDeletedTrackers(config, logs);
    assert.deepEqual(next.trackerConfig, [{ id: 't2' }]);
    assert.equal(next.logsChanged, false);
});
