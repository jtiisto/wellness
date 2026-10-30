# How we work

Every project imports this module. It is how work is done, whatever the stack.

## Working with the owner
- The owner decides. Don't assume: ask when a choice is theirs to make, and record
  each decision with its date and who made it. Don't be overly eager; do what was
  asked, and propose the rest.
- Never assume the owner is away. Only when the owner explicitly says they will be
  away and work should continue: take the most reversible default for each open
  choice and record it as *adopted by default*, separate from what the owner
  decided, so it can be reversed.
- Work as a senior developer: maintainability and performance first. No deprecated
  APIs unless asked.
- Keep intermediate updates terse (a line or two): decisions, findings, risks,
  results. Don't narrate upcoming steps or recap routine ones; never drop a blocker,
  caveat or result.
- For a complex question, end with the answer, a confidence level and the key
  caveats. For a simple one, just answer.

## Plan, spec, build
- **Plan.** Work beyond a small fix starts with a plan the owner agrees to before any
  code: Claude Code plan mode for a single change, a plan document for
  multi-milestone work (scope and assumptions, one section per milestone with its
  tests, a dated **Decisions** section).
- **Spec first.** Specs are the source of truth for behavior. New features, changes
  and bug fixes start as spec updates; a new spec, or a change to what the product
  does, needs the owner's approval before implementation. When the code must diverge
  from a spec for a measured technical reason, the main agent amends the spec first
  with the numbers, then the code. Specs are living documents and grow as understanding grows.
- Where plans and specs live, their format, and whether git tracks them are decided
  per project; each project's `CLAUDE.md` says.
- **Docs follow code.** Specs, docs, README and the project's `CLAUDE.md` status
  change in the same commit as the code.

## Agents
- Use subagents for focused work. One brief per scope: the files to read first, the
  exact scope, the rules (no commits, no spec edits, which files another agent owns),
  the gate to run, and the report format (files changed, test counts, coverage,
  measured deviations from the spec).
- A subagent never commits. The main agent reads the diff, runs the gate, amends specs
  and commits.
- Parallel agents work in git worktrees under `.claude/worktrees/` (gitignored), at
  most three at once, partitioned by file (two agents never edit the same file); the
  main agent merges them and deletes the worktrees' build output.
- **Codex reviews** read-only at every milestone boundary, and again over the fix
  diff: `codex exec --skip-git-repo-check -s read-only -C <dir> -o <report.md> - < <prompt.md>`,
  one session per area, detached. Ask for a findings table (severity, file:line,
  problem, concrete failure scenario, fix), a "verified correct" list and spec
  contradictions with citations. The main agent triages every finding ("fix, with a
  regression test" or "accepted, not fixed, because …") and logs findings and
  resolutions in the project's review log.

## Tests
- Unit and integration tests are written with the code, not after it.
- Zero tolerance for failures: never skip, loosen or delete an assertion to go green.
  Find the root cause and report it to the owner before going on.
- Each project's pre-push hook runs its full suite with a coverage gate; the hook's
  own command is the authority on whether the gate passes. Thresholds are project
  facts.
- Test fixtures are synthetic. Never copy values out of a real database, export or
  report into a fixture, a sample or a doc example.

## Version control
- Origin for every project is the Forgejo server that hosts this repo; projects are
  cloned beside it under the dev root. Whether a project is also mirrored somewhere
  public is a project fact, stated in its `CLAUDE.md`; a mirrored repo's history is
  public and not retractable.
- Solo repos: commit and push on the default branch. No feature branches or pull
  requests unless asked (worktree branches for parallel agents are local and merged
  by the main agent).
- Hooks live in the repo's tracked `githooks/`; run `git config core.hooksPath
  githooks` once per clone. pre-commit runs the tests relevant to what is staged,
  pre-push the full suite with coverage. A new project gets both.
- Never `--no-verify`, never amend, never force-push.

### Public mirrors of conventions modules
This repo is private, so a public clone of a project cannot follow
`@../conventions/<module>.md`. A project that is public therefore carries a
**mirror** of every module it imports, and the mirror is what it imports:

- The mirror lives at `docs/conventions/`: one verbatim copy per module, the list of
  modules in `MODULES`, and a `MANIFEST` recording each copy's sha256 and the commit
  here it was taken from. `../conventions/bin/sync-conventions.sh` writes all of it
  from this repo's HEAD and refuses while a listed module has uncommitted changes.
- **The mirror is generated and never edited in the project.** A change to a shared
  rule is a commit here, then a sync in each project that mirrors it — the same rule
  as "Changing a convention is a commit here", with one extra step.
- The project's `CLAUDE.md` imports the mirror (`@docs/conventions/<module>.md`), so
  every import resolves in every clone. Private projects keep importing the sibling.
- A guard test in the project's gate keeps the mirror honest: every copy's hash
  matches `MANIFEST` (always — this is what catches an edit made in the project);
  when the sibling checkout exists, the copy equals its HEAD
  (`sync-conventions.sh --check` — this is what catches a stale mirror); plus any
  check that ties the project's code to the module's content (a token table
  against a palette, say), so the implementation cannot drift from the authority
  in either direction.
- **A project spec never restates a mirrored module.** It points at the mirror and
  keeps only what is specific to that project (its colour meaning, its components,
  its measurements). One home per rule, even when the rule has a copy.

### Rules that change project files
A rule that is only true once each project changes a file of its own (a Gradle
property, a hook line, a guard test) is not adopted by being written here: a module
can state it, but only the project edit makes it so, and a sync of mirrored text
applies no settings. (The Kover configuration-cache rule was written from share on
2026-10-25; wellness still had the cache on four days later and lost a push to it.)

- Such a rule is recorded in `ADOPTION.md` here, one row per rule and project, with
  the date each project adopted it or `pending`. The commit that adds the rule adds
  the rows; a session that starts work in a project reads its pending rows first and
  flips them in the project commit that adopts the rule.
- Where the rule reduces to a fact about a file, this repo ships the check as a
  script in `bin/` and the project's guard test runs it (with an inline fallback for
  a public clone that has no sibling checkout), so drift fails the gate instead of
  waiting to be read.

### Commit and push
When asked to commit, push or both, the **main agent** runs it, never a subagent (a
subagent is done when its turn ends, so it cannot wait on a long push or report it).

1. **Prepare** (foreground, fast): `git status` and `git diff` (staged and unstaged).
2. **Sensitive-content scan** (mandatory before staging). Read the diff content of
   every file to be staged, not only the names. Content fails if it fails either test:
   - **Voice:** first-person personal narrative, prose about the owner's health,
     finances, family or other private experience ("my bloodwork last week came back low"). Code,
     schemas and feature docs that only *mention* private concepts (a `Migraine`
     tracker, a `depression_score` column) pass.
   - **Provenance:** could this concrete value have been copied out of a real data
     source? Dates, measurements, IDs or notes lifted from a live database are
     personal data however ordinary the code around them looks. Invented values pass.
     When unsure, query the source and compare; don't reason from how the code reads.
     A fixture full of medical-sounding strings is not safe by default: two DEXA scans
     and lab dates once leaked into a public repo past two voice-based reviews.

   On a match, don't stage. Report the path and a short excerpt and recommend moving
   the file out of the repo, gitignoring it, or the owner explicitly authorizing it.
   Never override this on your own. A project's automated guard, where it has one,
   supplements this judgement and never replaces it.
3. Draft the message: a subject line of at most 72 characters saying *why*, not
   what; then, if useful, a blank line and a body with the evidence (what broke,
   what was measured, review findings, gate numbers). Use the owner's message when
   given one. End it with the attribution lines the session provides.
4. Stage specific paths. Never `git add -A`, never `.env` or credential files.
5. From the project root, run `../conventions/bin/git-commit-push.sh
   commit|push|both --message "…" --files "path …"` (paths relative to the project
   root) with `run_in_background`; the hooks outlast the foreground timeout. Say it
   was started and move on. Where the project's hooks need an environment (a venv),
   activate it in the same command.
6. When it exits, read its output. For a push, confirm `IN_SYNC` (local `HEAD` equals
   upstream). Report the commit hash, the push result and the gate's test and
   coverage summary.

**If a hook or git fails, report it and stop.** Don't edit, amend, retry, pass
`--no-verify` or change code to make a hook pass. Investigate the cause only when the
owner asks.

## Dev checkouts and production
- Every clone under the dev root is a **dev checkout**: edit, test, commit, push.
  Production never runs from it.
- Production runs from pull-only checkouts on servers the ops instance owns. They are
  never edited, committed to or pushed from. A fix is made in the dev checkout and
  pushed; the owner has the ops instance deploy it. A project's `CLAUDE.md` says where
  its deploy is done, never how.
- No personal data in a dev checkout. Fixtures are synthetic (Tests); private files that
  production keeps untracked (personal parameters, say) have a tracked example here
  instead; real data is never copied down from production to debug. When a bug needs
  real data, ask the owner.
- Which role a machine has is stated in the machine-role block of its user-level
  `~/.claude/CLAUDE.md` (template: `machine-role.md` in this repo). Where it says a
  checkout is not a dev checkout, that wins over this module.

## Secrets
Passwords, tokens and keys are never in a tracked file. They live in untracked,
per-machine files (`local.properties`, `~/.gradle/gradle.properties`, `.env`), each
with a tracked template or a documented shape. When checking what exists, list file
names; never read or print a secret.
