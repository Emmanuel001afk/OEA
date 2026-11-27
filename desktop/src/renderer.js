const ICONS = {
  home: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/><path d="M9 21v-6h6v6"/></svg>',
  grid: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1.6"/><rect x="14" y="3" width="7" height="7" rx="1.6"/><rect x="3" y="14" width="7" height="7" rx="1.6"/><rect x="14" y="14" width="7" height="7" rx="1.6"/></svg>',
  star: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9z"/></svg>',
  clock: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>',
  settings: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8.2a3.8 3.8 0 1 0 0 7.6 3.8 3.8 0 0 0 0-7.6Z"/><path d="m19.4 15 .1.1a1.8 1.8 0 0 1-2.5 2.5l-.1-.1a1.8 1.8 0 0 0-3 .9v.2a1.8 1.8 0 0 1-3.6 0v-.2a1.8 1.8 0 0 0-3-.9l-.1.1a1.8 1.8 0 0 1-2.5-2.5l.1-.1a1.8 1.8 0 0 0-.9-3h-.2a1.8 1.8 0 0 1 0-3.6h.2a1.8 1.8 0 0 0 .9-3l-.1-.1a1.8 1.8 0 0 1 2.5-2.5l.1.1a1.8 1.8 0 0 0 3-.9v-.2a1.8 1.8 0 0 1 3.6 0v.2a1.8 1.8 0 0 0 3 .9l.1-.1a1.8 1.8 0 0 1 2.5 2.5l-.1.1a1.8 1.8 0 0 0 .9 3h.2a1.8 1.8 0 0 1 0 3.6h-.2a1.8 1.8 0 0 0-.9 3Z"/></svg>',
  search: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.4 4.4"/></svg>',
  refresh: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M4.9 9a7.5 7.5 0 0 1 12.5-2L20 12M4 17v-5h5"/><path d="M19.1 15a7.5 7.5 0 0 1-12.5 2L4 12"/></svg>',
  pin: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m16 3 5 5-3 1-3.8 3.8 1 4.2-2 2-4.2-5.8L4 18l-1-1 4.8-5-5.8-4.2 2-2 4.2 1L12 3l1-3z"/></svg>',
  calculator: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="2.5" width="14" height="19" rx="2.2"/><path d="M8 6h8v3H8zM8 13h.01M12 13h.01M16 13h.01M8 17h.01M12 17h.01M16 17h.01" stroke-width="2.6"/></svg>',
  folder: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6.5A2.5 2.5 0 0 1 5.5 4H10l2 2h6.5A2.5 2.5 0 0 1 21 8.5v9a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z"/><path d="M3 9h18"/></svg>',
  terminal: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="m7 9 3 3-3 3M13 15h4"/></svg>',
  monitor: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>',
  arrow: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
  warning: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 10 18H2z"/><path d="M12 9v5M12 18h.01"/></svg>',
  empty: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v4M12 17v4M3 12h4M17 12h4"/><path d="m5.6 5.6 2.8 2.8m7.2 7.2 2.8 2.8m0-12.8-2.8 2.8m-7.2 7.2-2.8 2.8"/><circle cx="12" cy="12" r="4"/></svg>',
};

const QUICK_TOOLS = [
  { id: 'calculator', name: 'Calculator', detail: 'Quick calculations', icon: 'calculator', tone: 'teal' },
  { id: 'explorer', name: 'File Explorer', detail: 'Browse your files', icon: 'folder', tone: 'blue' },
  { id: 'settings', name: 'Settings', detail: 'Windows settings', icon: 'settings', tone: 'violet' },
  { id: 'terminal', name: 'Terminal', detail: 'Command line', icon: 'terminal', tone: 'amber' },
  { id: 'taskManager', name: 'Task Manager', detail: 'System activity', icon: 'monitor', tone: 'blue' },
];

const PREVIEW_APPS = [
  { id: 'preview:code', name: 'Visual Studio Code', category: 'Work', source: 'Start menu', searchKey: 'Microsoft.Code', kind: 'shortcut' },
  { id: 'preview:edge', name: 'Microsoft Edge', category: 'Utilities', source: 'Start menu', searchKey: 'Microsoft.Edge', kind: 'shortcut' },
  { id: 'preview:steam', name: 'Steam', category: 'Games', source: 'Start menu', searchKey: 'Valve.Steam', kind: 'shortcut' },
  { id: 'preview:photos', name: 'Photos', category: 'Media', source: 'Windows apps', searchKey: 'Microsoft.Windows.Photos', kind: 'uwp' },
  { id: 'preview:terminal', name: 'Windows Terminal', category: 'System', source: 'Windows apps', searchKey: 'Microsoft.WindowsTerminal', kind: 'uwp' },
  { id: 'preview:notepad', name: 'Notepad', category: 'Utilities', source: 'Windows apps', searchKey: 'Microsoft.WindowsNotepad', kind: 'uwp' },
  { id: 'preview:word', name: 'Word', category: 'Work', source: 'Start menu', searchKey: 'Microsoft.Office.Word', kind: 'shortcut' },
  { id: 'preview:spotify', name: 'Spotify', category: 'Media', source: 'Start menu', searchKey: 'Spotify', kind: 'shortcut' },
  { id: 'preview:paint', name: 'Paint', category: 'Utilities', source: 'Windows apps', searchKey: 'Microsoft.Paint', kind: 'uwp' },
  { id: 'preview:calendar', name: 'Calendar', category: 'Work', source: 'Windows apps', searchKey: 'Microsoft.Windows.Calendar', kind: 'uwp' },
  { id: 'preview:photoshop', name: 'Adobe Photoshop', category: 'Media', source: 'Start menu', searchKey: 'Adobe.Photoshop', kind: 'shortcut' },
  { id: 'preview:chess', name: 'Chess', category: 'Games', source: 'Start menu', searchKey: 'OEA.Chess', kind: 'shortcut' },
];

const PAGE_DETAILS = {
  home: ['OEA / HOME', 'Your desktop', 'A calmer way to get to everything on this PC.'],
  apps: ['OEA / LIBRARY', 'All apps', 'Search and launch the apps installed on this computer.'],
  favorites: ['OEA / PINNED', 'Favorites', 'Your most-used apps, always close at hand.'],
  recent: ['OEA / ACTIVITY', 'Recent apps', 'Pick up where you left off.'],
  settings: ['OEA / PREFERENCES', 'Preferences', 'Make your desktop workspace feel like yours.'],
};

const dom = {
  frame: document.getElementById('app-frame'),
  content: document.getElementById('page-content'),
  title: document.getElementById('page-title'),
  subtitle: document.getElementById('page-subtitle'),
  eyebrow: document.getElementById('page-eyebrow'),
  search: document.getElementById('app-search'),
  refresh: document.getElementById('refresh-apps'),
  appCount: document.getElementById('nav-app-count'),
  favoriteCount: document.getElementById('nav-favorite-count'),
  connectionTitle: document.getElementById('connection-title'),
  connectionCaption: document.getElementById('connection-caption'),
  version: document.getElementById('app-version'),
  toast: document.getElementById('toast'),
};

for (const node of document.querySelectorAll('[data-icon]')) {
  const iconName = node.dataset.icon;
  if (ICONS[iconName]) node.innerHTML = ICONS[iconName];
}

const searchIndex = new OeaSearch.SearchIndex();
const state = {
  page: 'home',
  query: '',
  category: 'All',
  apps: [],
  host: { os: 'Preview', computer: 'This PC', nativeCatalog: false, appCount: 0, warning: '' },
  preferences: { version: 1, favorites: [], recent: [], accent: 'teal' },
  busy: false,
  toastTimer: null,
};

function previewState() {
  try {
    const raw = JSON.parse(localStorage.getItem('oea-desktop-preview-state') || '{}');
    return {
      favorites: Array.isArray(raw.favorites) ? raw.favorites.filter((id) => typeof id === 'string').slice(0, 80) : [],
      recent: Array.isArray(raw.recent) ? raw.recent.filter((id) => typeof id === 'string').slice(0, 16) : [],
      accent: ['teal', 'blue', 'violet', 'amber'].includes(raw.accent) ? raw.accent : 'teal',
    };
  } catch {
    return { version: 1, favorites: [], recent: [], accent: 'teal' };
  }
}

function savePreviewState(next) {
  localStorage.setItem('oea-desktop-preview-state', JSON.stringify(next));
  return next;
}

const previewApi = {
  async getBootstrap() {
    return {
      version: 'Preview',
      host: { os: 'Windows preview', computer: 'This PC', nativeCatalog: false, appCount: PREVIEW_APPS.length, warning: '' },
      apps: PREVIEW_APPS,
      preferences: previewState(),
    };
  },
  async refreshApps() {
    return {
      apps: PREVIEW_APPS,
      host: { os: 'Windows preview', computer: 'This PC', nativeCatalog: false, appCount: PREVIEW_APPS.length, warning: '' },
    };
  },
  async getIcon() { return ''; },
  async launchApp() { return { ok: false, error: 'This browser preview cannot launch Windows apps. Use the packaged OEA Desktop executable.' }; },
  async toggleFavorite(id) {
    const prefs = previewState();
    const favorites = new Set(prefs.favorites);
    if (favorites.has(id)) favorites.delete(id); else favorites.add(id);
    return savePreviewState({ ...prefs, favorites: [...favorites] });
  },
  async setAccent(accent) {
    const prefs = previewState();
    if (['teal', 'blue', 'violet', 'amber'].includes(accent)) prefs.accent = accent;
    return savePreviewState(prefs);
  },
  async launchQuickTool() { return { ok: false, error: 'Desktop tools launch from the Windows build, not the browser preview.' }; },
  async windowAction() { return false; },
};

const api = window.oea || previewApi;

function element(tag, className = '', text = '') {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
}

function iconMarkup(name) {
  return ICONS[name] || ICONS.empty;
}

function setPage(page, keepQuery = false) {
  if (!PAGE_DETAILS[page]) return;
  state.page = page;
  state.category = 'All';
  if (!keepQuery) {
    state.query = '';
    dom.search.value = '';
  }
  document.querySelectorAll('[data-page]').forEach((button) => {
    const active = button.dataset.page === page;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  render();
}

function showToast(message) {
  dom.toast.textContent = message;
  dom.toast.classList.add('show');
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => dom.toast.classList.remove('show'), 3000);
}

function appColorIndex(app) {
  let value = 0;
  for (const char of String(app.id || app.name || '')) value = (value * 31 + char.charCodeAt(0)) | 0;
  return Math.abs(value) % 6;
}

function appMonogram(name) {
  const words = String(name || '?').trim().split(/\s+/u).filter(Boolean);
  if (words.length > 1) return `${words[0][0]}${words[1][0]}`.toLocaleUpperCase();
  return (words[0] || '?').slice(0, 2).toLocaleUpperCase();
}

function hydrateIcon(app, iconNode) {
  if (!window.oea || !app.path) return;
  api.getIcon(app.id).then((dataUrl) => {
    if (!dataUrl || !iconNode.isConnected) return;
    const image = element('img');
    image.alt = '';
    image.src = dataUrl;
    iconNode.replaceChildren(image);
  }).catch(() => {});
}

function createAppCard(app) {
  const card = element('article', 'app-card');
  card.tabIndex = 0;
  card.setAttribute('role', 'button');
  card.setAttribute('aria-label', `Launch ${app.name}`);
  card.dataset.appId = app.id;

  const icon = element('div', `app-icon tone-${appColorIndex(app)}`);
  icon.setAttribute('aria-hidden', 'true');
  icon.append(element('span', 'app-monogram', appMonogram(app.name)));
  hydrateIcon(app, icon);

  const copy = element('div', 'app-card-copy');
  copy.append(element('span', 'app-name', app.name));
  copy.append(element('span', 'app-category', app.category || app.source || 'App'));

  const pin = element('button', `pin-button${state.preferences.favorites.includes(app.id) ? ' pinned' : ''}`);
  pin.type = 'button';
  pin.dataset.pinId = app.id;
  pin.setAttribute('aria-label', state.preferences.favorites.includes(app.id) ? `Remove ${app.name} from favorites` : `Pin ${app.name} to favorites`);
  pin.setAttribute('aria-pressed', String(state.preferences.favorites.includes(app.id)));
  pin.title = state.preferences.favorites.includes(app.id) ? 'Unpin from favorites' : 'Add to favorites';
  pin.innerHTML = iconMarkup('pin');

  card.append(icon, copy, pin);
  card.addEventListener('click', async (event) => {
    if (event.target.closest('[data-pin-id]')) return;
    await launchApp(app);
  });
  card.addEventListener('keydown', async (event) => {
    if ((event.key === 'Enter' || event.key === ' ') && !event.target.closest('[data-pin-id]')) {
      event.preventDefault();
      await launchApp(app);
    }
  });
  pin.addEventListener('click', async (event) => {
    event.stopPropagation();
    state.preferences = await api.toggleFavorite(app.id);
    updateCounts();
    render();
    showToast(state.preferences.favorites.includes(app.id) ? `${app.name} pinned to Favorites` : `${app.name} removed from Favorites`);
  });
  return card;
}

async function launchApp(app) {
  const result = await api.launchApp(app.id);
  if (!result.ok) {
    showToast(result.error || 'Could not launch this app.');
    return;
  }
  if (result.preferences) state.preferences = result.preferences;
  updateCounts();
  showToast(`Opening ${app.name}…`);
  if (state.page === 'home' || state.page === 'recent') render();
}

function sectionHeading(title, subtitle, actionLabel, action) {
  const row = element('div', 'section-heading');
  const text = element('div');
  text.append(element('h2', '', title));
  if (subtitle) text.append(element('p', '', subtitle));
  row.append(text);
  if (actionLabel) {
    const button = element('button', 'text-button', actionLabel);
    button.type = 'button';
    button.addEventListener('click', action);
    row.append(button);
  }
  return row;
}

function createAppsGrid(apps, emptyTitle, emptyText, emptyButton) {
  if (!apps.length) {
    const empty = element('div', 'empty-state');
    const badge = element('div', 'empty-icon');
    badge.innerHTML = iconMarkup('empty');
    empty.append(badge, element('h3', '', emptyTitle), element('p', '', emptyText));
    if (emptyButton) {
      const button = element('button', 'primary-button', emptyButton.label);
      button.type = 'button';
      button.addEventListener('click', emptyButton.action);
      empty.append(button);
    }
    return empty;
  }
  const grid = element('div', 'apps-grid');
  for (const app of apps) grid.append(createAppCard(app));
  return grid;
}

function getSearchedApps() {
  let apps = OeaSearch.searchApps(state.apps, state.query, {
    index: searchIndex,
    recentIds: state.preferences.recent,
  });
  if (state.category !== 'All') apps = apps.filter((app) => app.category === state.category);
  return apps;
}

function createQuickTool(tool, compact = false) {
  const button = element('button', compact ? 'tool-nav-item' : 'quick-tool-card');
  button.type = 'button';
  button.dataset.quickTool = tool.id;
  const badge = element('span', compact ? `tool-mini tone-${tool.tone}` : `tool-mini tone-${tool.tone}`);
  badge.innerHTML = iconMarkup(tool.icon);
  button.append(badge);
  if (compact) {
    button.append(element('span', '', tool.name));
  } else {
    const copy = element('span', 'quick-tool-copy');
    copy.append(element('span', 'quick-tool-name', tool.name));
    copy.append(element('span', 'quick-tool-caption', tool.detail));
    button.append(copy);
  }
  button.addEventListener('click', async () => {
    const result = await api.launchQuickTool(tool.id);
    if (result.ok) showToast(`Opening ${tool.name}…`);
    else showToast(result.error || `${tool.name} is not available.`);
  });
  return button;
}

function addNoticeIfNeeded(parent) {
  const warning = state.host.warning;
  if (!warning) return;
  const notice = element('div', 'notice-bar');
  notice.innerHTML = iconMarkup('warning');
  notice.append(document.createTextNode(warning));
  parent.append(notice);
}

function renderHome() {
  const grid = element('div', 'home-grid');
  const mainColumn = element('div', 'home-main-column');
  const aside = element('div', 'home-aside');

  const hour = new Date().getHours();
  const greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
  const hero = element('section', 'hero-card');
  const heroCopy = element('div', 'hero-copy');
  heroCopy.append(element('div', 'hero-kicker', 'YOUR DESKTOP, IN SYNC'));
  heroCopy.append(element('h2', '', `${greeting}. Ready when you are.`));
  heroCopy.append(element('p', '', 'Find your tools, favorite apps, and recent work from one focused desktop home.'));
  const actions = element('div', 'hero-actions');
  const browse = element('button', 'primary-button');
  browse.type = 'button';
  browse.append(document.createTextNode('Browse apps '));
  const arrow = element('span'); arrow.innerHTML = iconMarkup('arrow'); browse.append(arrow);
  browse.addEventListener('click', () => setPage('apps'));
  const focus = element('button', 'secondary-button', 'Refresh app list');
  focus.type = 'button';
  focus.addEventListener('click', refreshApps);
  actions.append(browse, focus);
  heroCopy.append(actions);
  const heroMark = element('div', 'hero-mark');
  heroMark.innerHTML = '<div class="brand-glyph" aria-hidden="true"><span></span><span></span><span></span><span></span></div>';
  hero.append(heroCopy, heroMark);
  mainColumn.append(hero);

  const quick = element('section', 'section-card');
  quick.append(sectionHeading('Quick tools', 'Everyday Windows tools, one click away.'));
  const toolGrid = element('div', 'quick-tool-grid');
  for (const tool of QUICK_TOOLS.slice(0, 3)) toolGrid.append(createQuickTool(tool));
  quick.append(toolGrid);
  mainColumn.append(quick);

  const favoriteApps = state.preferences.favorites
    .map((id) => state.apps.find((app) => app.id === id))
    .filter(Boolean)
    .slice(0, 6);
  const favorites = element('section', 'section-card');
  favorites.append(sectionHeading(
    'Favorites',
    favoriteApps.length ? 'Your pinned apps.' : 'Pin the apps you use most to keep them here.',
    'See all',
    () => setPage('favorites')
  ));
  favorites.append(createAppsGrid(
    favoriteApps,
    'Your favorites live here',
    'Pin an app with the star on its card and it will be ready on this home screen.',
    { label: 'Explore apps', action: () => setPage('apps') }
  ));
  mainColumn.append(favorites);

  const clock = element('section', 'section-card stat-card');
  clock.append(element('div', 'stat-label', 'LOCAL TIME'));
  const clockValue = element('div', 'stat-value', new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date()));
  clockValue.id = 'local-clock';
  clock.append(clockValue);
  clock.append(element('div', 'stat-note', new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric' }).format(new Date())));
  aside.append(clock);

  const host = element('section', 'section-card host-card');
  const hostRow = element('div', 'host-row');
  const hostIcon = element('div', 'host-icon'); hostIcon.innerHTML = iconMarkup('monitor');
  const hostCopy = element('div', 'host-copy');
  hostCopy.append(element('span', 'host-name', state.host.computer || 'This PC'));
  hostCopy.append(element('span', 'host-caption', `${state.host.os} desktop`));
  hostRow.append(hostIcon, hostCopy);
  host.append(hostRow);
  host.append(element('div', 'host-separator'));
  host.append(element('div', 'host-footnote', `${state.apps.length} apps indexed. Your app list and preferences stay on this PC.`));
  aside.append(host);

  const recentApps = state.preferences.recent
    .map((id) => state.apps.find((app) => app.id === id))
    .filter(Boolean)
    .slice(0, 4);
  const recent = element('section', 'section-card');
  recent.append(sectionHeading('Recently opened', 'Your latest launches.', 'View recent', () => setPage('recent')));
  recent.append(createAppsGrid(
    recentApps,
    'No recent apps yet',
    'Apps you launch from OEA appear here.',
    { label: 'Open app library', action: () => setPage('apps') }
  ));
  aside.append(recent);

  grid.append(mainColumn, aside);
  dom.content.append(grid);
}

function renderFilters(parent) {
  const categories = ['All', ...new Set(state.apps.map((app) => app.category).filter(Boolean).sort((a, b) => a.localeCompare(b)))];
  const row = element('div', 'filter-row');
  for (const category of categories) {
    const chip = element('button', `filter-chip${state.category === category ? ' active' : ''}`, category);
    chip.type = 'button';
    chip.setAttribute('aria-pressed', String(state.category === category));
    chip.addEventListener('click', () => { state.category = category; render(); });
    row.append(chip);
  }
  parent.append(row);
}

function renderAppLibrary() {
  const wrapper = element('div');
  addNoticeIfNeeded(wrapper);
  renderFilters(wrapper);
  const apps = getSearchedApps();
  const label = element('section', 'section-card');
  label.append(sectionHeading(
    state.query ? `Search results · ${apps.length}` : `${apps.length} applications`,
    state.query ? `Matching “${state.query}” on this computer.` : 'The apps discovered from this PC’s Start Menu and desktop shortcuts.',
    'Refresh',
    refreshApps
  ));
  label.append(createAppsGrid(
    apps,
    'No matching apps',
    state.apps.length ? 'Try another search or choose a different category.' : 'Add shortcuts to your Windows Start Menu, then refresh the catalog.',
    state.apps.length ? { label: 'Clear search', action: () => { state.query = ''; dom.search.value = ''; render(); } } : { label: 'Refresh apps', action: refreshApps }
  ));
  wrapper.append(label);
  dom.content.append(wrapper);
}

function renderFavorites() {
  const wrapper = element('div');
  const apps = state.preferences.favorites.map((id) => state.apps.find((app) => app.id === id)).filter(Boolean);
  const section = element('section', 'section-card');
  section.append(sectionHeading(`${apps.length} pinned apps`, 'Pinned apps are saved for this Windows user.'));
  section.append(createAppsGrid(
    apps,
    'Nothing pinned yet',
    'Go to All apps and click the star on an app card to add it to Favorites.',
    { label: 'Browse all apps', action: () => setPage('apps') }
  ));
  wrapper.append(section);
  dom.content.append(wrapper);
}

function renderRecent() {
  const wrapper = element('div');
  const apps = state.preferences.recent.map((id) => state.apps.find((app) => app.id === id)).filter(Boolean);
  const section = element('section', 'section-card');
  section.append(sectionHeading(`${apps.length} recent apps`, 'Your launch history is stored locally on this PC.'));
  section.append(createAppsGrid(
    apps,
    'No recent apps yet',
    'Apps you open from OEA will appear here for quick access.',
    { label: 'Browse all apps', action: () => setPage('apps') }
  ));
  wrapper.append(section);
  dom.content.append(wrapper);
}

function renderSettings() {
  const wrapper = element('div', 'settings-grid');
  const theme = element('section', 'section-card setting-card');
  theme.append(element('h2', '', 'Accent color'));
  theme.append(element('p', '', 'Choose the glow and highlight color for your workspace. This changes appearance only.'));
  const choices = element('div', 'accent-options');
  for (const accent of ['teal', 'blue', 'violet', 'amber']) {
    const button = element('button', `accent-choice ${accent}`);
    button.type = 'button';
    button.title = `${accent[0].toLocaleUpperCase()}${accent.slice(1)} accent`;
    button.setAttribute('aria-label', button.title);
    button.setAttribute('aria-pressed', String(state.preferences.accent === accent));
    button.addEventListener('click', async () => {
      state.preferences = await api.setAccent(accent);
      applyAccent();
      render();
      showToast(`${button.title} applied`);
    });
    choices.append(button);
  }
  theme.append(choices);

  const host = element('section', 'section-card setting-card');
  host.append(element('h2', '', 'This computer'));
  host.append(element('p', '', 'OEA reads Windows Start Menu shortcuts and registered Store apps. Launching an app uses the normal Windows shell; OEA does not replace your taskbar or change the default desktop.'));
  const hostLine = element('div', 'host-row settings-host-row');
  const hostIcon = element('div', 'host-icon'); hostIcon.innerHTML = iconMarkup('monitor');
  const hostCopy = element('div', 'host-copy');
  hostCopy.append(element('span', 'host-name', state.host.computer || 'This PC'));
  hostCopy.append(element('span', 'host-caption', `${state.host.os} · ${state.apps.length} apps found`));
  hostLine.append(hostIcon, hostCopy);
  host.append(hostLine);

  const shortcuts = element('section', 'section-card setting-card');
  shortcuts.append(element('h2', '', 'Keyboard shortcuts'));
  shortcuts.append(element('p', '', 'Move around OEA without leaving the keyboard.'));
  const list = element('div', 'shortcut-list');
  for (const [label, keys] of [['Search apps', 'Ctrl K'], ['Launch first result', 'Enter'], ['Clear search', 'Esc'], ['Refresh apps', 'Ctrl R']]) {
    const row = element('div', 'shortcut-row'); row.append(element('span', '', label)); row.append(element('kbd', '', keys)); list.append(row);
  }
  shortcuts.append(list);

  const privacy = element('section', 'section-card setting-card');
  privacy.append(element('h2', '', 'Privacy and storage'));
  privacy.append(element('p', '', 'Pinned apps and recent launches are saved to your Windows user profile. OEA does not upload your app list or use an account.'));
  const privacyLine = element('div', 'host-footnote settings-footnote', 'App launch commands are restricted to shortcuts and app IDs discovered on this computer.');
  privacy.append(privacyLine);

  wrapper.append(theme, host, shortcuts, privacy);
  dom.content.append(wrapper);
}

function render() {
  const details = PAGE_DETAILS[state.page] || PAGE_DETAILS.home;
  dom.eyebrow.textContent = details[0];
  dom.title.textContent = state.page === 'home' ? details[1] : details[1];
  dom.subtitle.textContent = details[2];
  dom.content.replaceChildren();
  applyAccent();

  if (state.page === 'home') renderHome();
  else if (state.page === 'apps') renderAppLibrary();
  else if (state.page === 'favorites') renderFavorites();
  else if (state.page === 'recent') renderRecent();
  else if (state.page === 'settings') renderSettings();

  if (state.query && state.page !== 'apps') {
    state.page = 'apps';
    document.querySelector('[data-page="apps"]')?.classList.add('active');
    dom.content.replaceChildren();
    renderAppLibrary();
  }
  updateCounts();
}

function updateCounts() {
  dom.appCount.textContent = String(state.apps.length);
  dom.favoriteCount.textContent = String(state.preferences.favorites.length);
  dom.connectionTitle.textContent = state.host.computer || 'This PC';
  dom.connectionCaption.textContent = state.host.nativeCatalog ? 'Windows apps ready' : 'Desktop workspace';
  dom.version.textContent = state.host.os === 'Preview' ? 'Preview' : `v${state.version || '0.1.0'}`;
}

function applyAccent() {
  dom.frame.dataset.accent = state.preferences.accent || 'teal';
}

async function refreshApps() {
  if (state.busy) return;
  state.busy = true;
  dom.refresh.classList.add('loading');
  dom.refresh.disabled = true;
  try {
    const result = await api.refreshApps();
    state.apps = result.apps || [];
    state.host = result.host || state.host;
    searchIndex.clear();
    render();
    showToast(`Found ${state.apps.length} apps`);
  } catch (error) {
    showToast(`Could not refresh apps: ${error.message}`);
  } finally {
    state.busy = false;
    dom.refresh.classList.remove('loading');
    dom.refresh.disabled = false;
  }
}

async function bootstrap() {
  try {
    const payload = await api.getBootstrap();
    state.apps = Array.isArray(payload.apps) ? payload.apps : [];
    state.host = payload.host || state.host;
    state.preferences = payload.preferences || state.preferences;
    state.version = payload.version || '0.1.0';
    render();
  } catch (error) {
    state.host.warning = `Startup failed: ${error.message}`;
    render();
  }
}

document.querySelectorAll('[data-page]').forEach((button) => {
  button.addEventListener('click', () => setPage(button.dataset.page));
});
document.querySelectorAll('[data-window]').forEach((button) => {
  button.addEventListener('click', () => api.windowAction(button.dataset.window));
});
document.querySelectorAll('[data-quick-tool]').forEach((button) => {
  const tool = QUICK_TOOLS.find((item) => item.id === button.dataset.quickTool);
  if (tool) button.addEventListener('click', async () => {
    const result = await api.launchQuickTool(tool.id);
    if (result.ok) showToast(`Opening ${tool.name}…`);
    else showToast(result.error || `${tool.name} is not available.`);
  });
});
dom.refresh.addEventListener('click', refreshApps);
dom.search.addEventListener('input', () => {
  state.query = dom.search.value.trim();
  if (state.query && state.page !== 'apps') setPage('apps', true);
  else render();
});
dom.search.addEventListener('keydown', async (event) => {
  if (event.key === 'Escape') {
    dom.search.value = '';
    state.query = '';
    render();
  } else if (event.key === 'Enter') {
    const first = getSearchedApps()[0];
    if (first) await launchApp(first);
  }
});
document.addEventListener('keydown', (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k') {
    event.preventDefault();
    dom.search.focus();
    dom.search.select();
  } else if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'r') {
    event.preventDefault();
    refreshApps();
  } else if (event.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) {
    event.preventDefault();
    dom.search.focus();
  }
});

document.getElementById('page-content').addEventListener('click', (event) => {
  const browse = event.target.closest('[data-page-jump]');
  if (browse) setPage(browse.dataset.pageJump);
});

setInterval(() => {
  const clock = document.getElementById('local-clock');
  if (clock) clock.textContent = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date());
}, 15000);

bootstrap();
