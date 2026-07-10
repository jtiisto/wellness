// Pins the raw-HTML escaping in markdownToHtml (codex review 2026-07-09 P1):
// model-produced markdown is rendered with dangerouslySetInnerHTML, so raw
// HTML tokens must come out escaped — markdown features must keep working.
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
