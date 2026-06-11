"""The per-record sync acceptance predicate — ONE implementation.

Both modules arbitrate uploads with the same rule (journal trackers/entries,
coach feedback/exercise records). It is the most safety-critical predicate in
the system: before this module it was implemented three times (coach_logs plus
two inline copies in journal), and the force-sync divergence showed that copies
of arbitration logic drift. Change it here or not at all.
"""


def should_accept_log_write(stored_last_modified, base_token):
    """Decide whether an incoming record write wins — server-side, WITHOUT
    consulting any client clock (R1; see plans/phase4-r1-coach-clock-skew.md).

    Both operands are *server-issued* Z-suffixed instants, so the comparison is
    skew-free and byte-lexical:

    * no existing row (`stored_last_modified is None`) → accept (insert; also
      covers a pre-protocol row that has no stamp yet — accept and stamp).
    * existing row, `base_token` present, `stored <= base_token` → accept: the
      client echoed the latest server stamp it saw, so its edit is newer.
      Equality accepts — the idempotent-retry case after a lost response.
    * else → reject: either the client missed a newer server write
      (`stored > base_token`), or it sent no token against an existing row
      (hard cutover — the token is required to overwrite).
    """
    if stored_last_modified is None:
        return True
    if base_token is None:
        return False
    return stored_last_modified <= base_token
