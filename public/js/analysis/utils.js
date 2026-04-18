import { marked } from 'marked';

marked.setOptions({ gfm: true, breaks: false });

export function formatTimestamp(isoStr) {
    if (!isoStr) return '';
    const d = new Date(isoStr);
    return d.toLocaleDateString('en-US', {
        month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit'
    });
}

// Status cells in report tables often render as "🟢 OK" / "🟡 YELLOW" /
// "🔴 RED" — the color emoji and the word carry the same information, so
// the text is pure visual noise. Collapse the redundant pair (in either
// order) to emoji-only so the status column scans cleanly. Applied to the
// raw markdown before parsing so it also catches prose, not just cells.
function collapseStatusText(md) {
    const word = '(?:OK|RED|YELLOW|GREEN|PASS|FAIL)';
    const dot = '[🟢🟡🔴✅❌⚠️]';
    return md
        .replace(new RegExp(`(${dot})\\s+${word}\\b`, 'gi'), '$1')
        .replace(new RegExp(`\\b${word}\\s+(${dot})`, 'gi'), '$1');
}

export function markdownToHtml(md) {
    if (!md) return '';
    const cleaned = collapseStatusText(md);
    return marked.parse(cleaned).replace(/<table>/g, '<div class="table-wrap"><table>')
                                .replace(/<\/table>/g, '</table></div>');
}

export function elapsedTime(startIso) {
    const start = new Date(startIso);
    const seconds = Math.floor((Date.now() - start) / 1000);
    if (seconds < 60) return `${seconds}s`;
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
