/**
 * Wellness App Shell - Tab navigation and dynamic module loading
 */
import { h, render } from 'preact';
import { signal, effect } from '@preact/signals';
import htm from 'htm';
import { Notifications } from './shared/notifications.js';
import { SettingsMenu } from './shared/settings.js';

const html = htm.bind(h);

// ==================== State ====================

const modules = signal([]);
const activeModuleId = signal(localStorage.getItem('wellness_active_module') || null);
const loading = signal(true);
const moduleComponents = signal({});

// ==================== Icons (inline SVG) ====================

const settingsOpen = signal(false);

const ICONS = {
    book: html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="22" height="22">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
    </svg>`,
    dumbbell: html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="22" height="22">
        <path d="M6.5 6.5h11M6.5 17.5h11"/>
        <rect x="2" y="4.5" width="4.5" height="15" rx="1"/>
        <rect x="17.5" y="4.5" width="4.5" height="15" rx="1"/>
        <rect x="4.5" y="7" width="2" height="10" rx="0.5"/>
        <rect x="17.5" y="7" width="2" height="10" rx="0.5"/>
    </svg>`,
    'chart-bar': html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="22" height="22">
        <rect x="3" y="12" width="4" height="9" rx="1"/>
        <rect x="10" y="6" width="4" height="15" rx="1"/>
        <rect x="17" y="3" width="4" height="18" rx="1"/>
    </svg>`,
    grid: html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="22" height="22">
        <rect x="3" y="3" width="7" height="7"/>
        <rect x="14" y="3" width="7" height="7"/>
        <rect x="3" y="14" width="7" height="7"/>
        <rect x="14" y="14" width="7" height="7"/>
    </svg>`,
    settings: html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="22" height="22">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
    </svg>`,
};

// ==================== Module Loading ====================

async function loadModules() {
    try {
        const res = await fetch('/api/modules');
        modules.value = await res.json();
        if (!activeModuleId.value || !modules.value.find(m => m.id === activeModuleId.value)) {
            activeModuleId.value = modules.value[0]?.id || null;
        }
    } catch (err) {
        console.error('Failed to load modules:', err);
    } finally {
        loading.value = false;
    }
}

async function loadModuleComponent(moduleId) {
    if (moduleComponents.value[moduleId]) return;

    const importMap = {
        journal:  () => import('./journal/JournalView.js'),
        coach:    () => import('./coach/CoachView.js'),
        analysis: () => import('./analysis/AnalysisView.js'),
    };

    const loader = importMap[moduleId];
    if (loader) {
        try {
            const mod = await loader();
            moduleComponents.value = {
                ...moduleComponents.value,
                [moduleId]: mod.default || mod[Object.keys(mod)[0]]
            };
        } catch (err) {
            console.error(`Failed to load module ${moduleId}:`, err);
        }
    }
}

// When active module changes, load its component
effect(() => {
    if (activeModuleId.value) {
        loadModuleComponent(activeModuleId.value);
    }
});

function selectModule(id) {
    activeModuleId.value = id;
    localStorage.setItem('wellness_active_module', id);
}

// ==================== Components ====================

function NavBar() {
    if (modules.value.length <= 1) return null;

    return html`
        <nav class="nav-bar">
            ${modules.value.map(m => html`
                <button key=${m.id}
                    class="nav-btn ${activeModuleId.value === m.id ? 'active' : ''}"
                    onClick=${() => selectModule(m.id)}
                    style="--app-color: ${m.color}"
                    title=${m.name}
                >
                    <span class="nav-icon">${ICONS[m.icon] || ICONS.grid}</span>
                    <span class="nav-label">${m.name}</span>
                </button>
            `)}
            <button class="nav-btn settings-btn"
                onClick=${() => { settingsOpen.value = true; }}
                title="Settings"
            >
                <span class="nav-icon">${ICONS.settings}</span>
                <span class="nav-label">Settings</span>
            </button>
        </nav>
    `;
}

function ModuleContent() {
    const Component = moduleComponents.value[activeModuleId.value];
    if (!Component) {
        return html`<div class="loading"><div class="loading-spinner"></div></div>`;
    }
    return html`<${Component} key=${activeModuleId.value}/>`;
}

function App() {
    if (loading.value) {
        return html`<div class="app"><div class="loading"><div class="loading-spinner"></div></div></div>`;
    }

    if (modules.value.length === 0) {
        return html`<div class="loading">No modules available</div>`;
    }

    return html`
        <div class="shell">
            <main class="module-content"><${ModuleContent}/></main>
            <${NavBar}/>
            <${Notifications}/>
            <${SettingsMenu} isOpen=${settingsOpen.value} onClose=${() => { settingsOpen.value = false; }}/>
        </div>
    `;
}

// ==================== Mount ====================

loadModules();
render(html`<${App}/>`, document.getElementById('app'));
