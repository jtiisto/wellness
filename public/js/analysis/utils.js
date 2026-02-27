import { marked } from 'marked';

marked.setOptions({ gfm: true, breaks: false });

export function formatTimestamp(isoStr) {
    if (!isoStr) return '';
    const d = new Date(isoStr);
    return d.toLocaleDateString('en-US', {
        month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit'
    });
}

export function markdownToHtml(md) {
    if (!md) return '';
    return marked.parse(md).replace(/<table>/g, '<div class="table-wrap"><table>')
                           .replace(/<\/table>/g, '</table></div>');
}

export function elapsedTime(startIso) {
    const start = new Date(startIso);
    const seconds = Math.floor((Date.now() - start) / 1000);
    if (seconds < 60) return `${seconds}s`;
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
