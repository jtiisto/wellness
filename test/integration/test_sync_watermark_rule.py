"""The PWA stores' pull-watermark rule: only a PULL response advances it.

`lastServerSyncTime` is the `since` cursor of the next incremental pull. The
server back-dates a pull's `serverTime` by SYNC_WATERMARK_OVERLAP_SECONDS so the
next pull's window overlaps the writes that were in flight during this one; an
upload response's stamp is an ordinary write timestamp with no such margin.
Storing the upload's stamp over the pull's therefore discarded the overlap, and
a row the OTHER client committed in those seconds was never delivered — silent
cross-client staleness until the row was next touched.

Coach always had this rule; the journal store did not (native review 2026-08-11,
item 2). The advance lives inside the stores' signal writes, which `node --test`
cannot import, so it is pinned here — the same seam test_auto_sync.py and
test_force_sync.py use for store contracts.

The allowed right-hand sides are listed per store rather than pattern-matched:
any new expression assigned to `lastServerSyncTime` fails this test on purpose,
so re-approving it is a deliberate act.
"""
import re
from pathlib import Path

import pytest

JS_DIR = Path(__file__).parent.parent.parent / "public" / "js"

# RHS of every `lastServerSyncTime:` assignment, whitespace-normalized.
_ASSIGNMENT = re.compile(r"lastServerSyncTime:\s*([^,\n}]+)")

# Pull responses (delta / full download) — the only server values allowed to
# become the cursor. Everything else here is local: a default, a reload from
# storage, or a read echoed into a debug snapshot.
_ALLOWED = {
    "journal/store.js": {
        "null",
        "metadata?.lastServerSyncTime || null",
        "meta.lastServerSyncTime",
        "serverData.serverTime || getUtcNow()",  # GET /sync/delta
    },
    "coach/store.js": {
        "null",
        "metadata?.lastServerSyncTime || null",
        "syncMetadata.value.lastServerSyncTime",
        "meta.lastServerSyncTime",
        "data.serverTime",  # GET /sync (full or incremental download)
    },
}


@pytest.mark.integration
class TestPullWatermarkRule:
    @pytest.mark.parametrize("store", sorted(_ALLOWED))
    def test_watermark_comes_only_from_a_pull_response(self, store):
        source = (JS_DIR / store).read_text()
        found = {" ".join(m.strip().split()) for m in _ASSIGNMENT.findall(source)}
        unexpected = found - _ALLOWED[store]
        assert not unexpected, (
            f"{store} assigns lastServerSyncTime from {sorted(unexpected)}. "
            "The pull watermark may only come from a delta/download response — "
            "an upload response's serverTime carries no overlap, and storing it "
            "drops the other client's writes from the next pull's window. If "
            "this is a rename, add the new expression to _ALLOWED."
        )

    @pytest.mark.parametrize("store", sorted(_ALLOWED))
    def test_upload_response_is_never_the_cursor(self, store):
        """Direct pin on the shape of the bug: the variable each store binds its
        POST response to must never appear as the watermark's source."""
        source = " ".join((JS_DIR / store).read_text().split())
        for upload_var in ("result", "uploadResult", "response"):
            assert f"lastServerSyncTime: {upload_var}.serverTime" not in source, (
                f"{store} advances the pull watermark from the upload response "
                f"(`{upload_var}.serverTime`) — see this module's docstring."
            )

    def test_journal_cursor_is_written_only_by_the_delta_pull(self):
        """The journal store had the bug in BOTH sync paths (triggerSync and
        forceSync). `serverData` is bound only inside pullServerChanges, so the
        server-sourced write belongs to the pull and to nothing else."""
        source = (JS_DIR / "journal" / "store.js").read_text()
        assert source.count("lastServerSyncTime: serverData.serverTime") == 1
        assert source.count("serverData = await response.json()") == 1
