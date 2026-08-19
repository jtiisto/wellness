// Relative vendor path (not the bare 'marked' importmap alias) so node:test
// can import this module and pin the raw-HTML escaping below.
import { marked } from '../vendor/marked.js';

marked.setOptions({ gfm: true, breaks: false });

// Model-produced markdown is rendered with dangerouslySetInnerHTML
// (ReportView), so RAW HTML in it must never pass through: a stored report
// carrying an event handler would execute under the app origin on every
// open (codex review 2026-07-09 P1). Escaping the html tokens (block AND
// inline — both routes call renderer.html in marked's token API) keeps the
// markdown features while neutering embedded HTML; no sanitizer dependency.
function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

marked.use({ renderer: { html({ text }) { return escapeHtml(text); } } });

export function formatTimestamp(isoStr) {
    if (!isoStr) return '';
    const d = new Date(isoStr);
    return d.toLocaleDateString('en-US', {
        month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit'
    });
}

// The wire carries status as neutral data — three tokens, each client drawing
// them in its own notation (docs/ARCHITECTURE.md, "Status marker vocabulary").
// This client's notation is the emoji it has always shown, so the tokens are
// expanded straight back into it, in the same pre-parse slot the collapse has
// always run in. An unknown token is left as its own literal text: a reader
// can still read "[flag]", which is more than a swallowed marker gives them.
const STATUS_TOKEN_EMOJI = { '[ok]': '✅', '[watch]': '🟡', '[act]': '🔴' };

function expandStatusTokens(md) {
    return md.replace(/\[(?:ok|watch|act)\]/gi, m => STATUS_TOKEN_EMOJI[m.toLowerCase()]);
}

// Status cells in report tables often render as "🟢 OK" / "🟡 YELLOW" /
// "🔴 RED" — the color emoji and the word carry the same information, so
// the text is pure visual noise. Collapse the redundant pair (in either
// order) to emoji-only so the status column scans cleanly. Applied to the
// raw markdown before parsing so it also catches prose, not just cells.
//
// The server normalizes these pairs into tokens now, but this stays: report
// bodies are cached in LocalForage, so after a deploy every device keeps
// serving itself pre-change markdown out of its own cache, and the pairs
// would come back looking exactly as noisy as they did before. It runs after
// the expansion, so it also tidies a body that mixes the two vocabularies.
function collapseStatusText(md) {
    const word = '(?:OK|RED|YELLOW|GREEN|PASS|FAIL)';
    const dot = '[🟢🟡🔴✅❌⚠️]';
    return md
        .replace(new RegExp(`(${dot})\\s+${word}\\b`, 'gi'), '$1')
        .replace(new RegExp(`\\b${word}\\s+(${dot})`, 'gi'), '$1');
}

export function markdownToHtml(md) {
    if (!md) return '';
    const cleaned = collapseStatusText(expandStatusTokens(md));
    return marked.parse(cleaned).replace(/<table>/g, '<div class="table-wrap"><table>')
                                .replace(/<\/table>/g, '</table></div>');
}

export function elapsedTime(startIso) {
    const start = new Date(startIso);
    const seconds = Math.floor((Date.now() - start) / 1000);
    if (seconds < 60) return `${seconds}s`;
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
