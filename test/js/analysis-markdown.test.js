// Pins the raw-HTML escaping in markdownToHtml (codex review 2026-07-09 P1):
// model-produced markdown is rendered with dangerouslySetInnerHTML, so raw
// HTML tokens must come out escaped — markdown features must keep working.
//
// Also pins the status-marker handling either side of it: the wire's three
// tokens expanded into this client's emoji, and the legacy emoji/word pairs
// still collapsed for the bodies device caches keep serving. Neither half was
// pinned by anything before the tokens landed.
import test from 'node:test';
import assert from 'node:assert/strict';
import { markdownToHtml } from '../../public/js/analysis/utils.js';

test('block-level raw HTML is escaped, not rendered', () => {
    const out = markdownToHtml('<img src=x onerror="alert(1)">\n\ntext');
    assert.ok(!out.includes('<img'), out);
    assert.ok(out.includes('&lt;img'), out);
});

test('inline raw HTML is escaped, not rendered', () => {
    const out = markdownToHtml('before <b onmouseover="alert(1)">bold</b> after');
    assert.ok(!out.includes('<b '), out);
    assert.ok(out.includes('&lt;b'), out);
});

test('SVG and script payloads are escaped', () => {
    for (const payload of [
        '<svg onload="alert(1)"></svg>',
        '<script>alert(1)</script>',
        '<iframe src="javascript:alert(1)"></iframe>',
    ]) {
        const out = markdownToHtml(payload);
        assert.ok(!/<(svg|script|iframe)/.test(out), out);
    }
});

test('markdown features still render', () => {
    const out = markdownToHtml('# Title\n\n**bold** and `code`\n\n| a | b |\n|---|---|\n| 1 | 2 |');
    assert.ok(out.includes('<h1>'), out);
    assert.ok(out.includes('<strong>bold</strong>'), out);
    assert.ok(out.includes('<code>code</code>'), out);
    assert.ok(out.includes('<div class="table-wrap"><table>'), out);
});

// ---- status markers: the wire's tokens, drawn in this client's notation ----

test('each token expands to the emoji this client draws it as', () => {
    assert.ok(markdownToHtml('[ok]').includes('✅'));
    assert.ok(markdownToHtml('[watch]').includes('🟡'));
    assert.ok(markdownToHtml('[act]').includes('🔴'));
});

test('tokens expand inside table cells and inside prose', () => {
    const cell = markdownToHtml('| Metric | Status |\n|---|---|\n| Sleep | [ok] |');
    assert.ok(cell.includes('<td>✅</td>'), cell);

    const prose = markdownToHtml('Recovery is [ok] and load is [act].');
    assert.ok(prose.includes('Recovery is ✅ and load is 🔴.'), prose);
});

test('a token the model shouted still expands', () => {
    // The server only ever emits lower case; a model ignoring the output
    // contract is the reason this is lenient rather than exact.
    assert.ok(markdownToHtml('[OK] and [Watch]').includes('✅ and 🟡'));
});

test('an unrecognized token is left as readable text', () => {
    const out = markdownToHtml('[flag] and [ok]');
    assert.ok(out.includes('[flag]'), out);
    assert.ok(out.includes('✅'), out);
});

// ---- status markers: the legacy pairs device caches keep serving -----------

test('the legacy emoji/word pair still collapses, in either order', () => {
    assert.ok(markdownToHtml('🟢 OK').includes('<p>🟢</p>'));
    assert.ok(markdownToHtml('RED 🔴').includes('<p>🔴</p>'));
    assert.ok(markdownToHtml('✅ pass').includes('<p>✅</p>'));
});

test('collapsing respects word boundaries', () => {
    assert.ok(markdownToHtml('🟢 OKAY').includes('🟢 OKAY'));
    assert.ok(markdownToHtml('REDACTED 🔴').includes('REDACTED 🔴'));
});

test('an expanded token whose word survived collapses too', () => {
    // A body mixing both vocabularies — the expansion runs first, so the
    // leftover word is collapsed against the emoji it just produced.
    assert.ok(markdownToHtml('[act] FAIL').includes('<p>🔴</p>'));
});

test('a plain check mark and unmarked prose pass through untouched', () => {
    assert.ok(markdownToHtml('✓ done').includes('✓ done'));
    const prose = 'The session went well and the numbers held.';
    assert.ok(markdownToHtml(prose).includes(prose));
});
