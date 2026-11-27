const { createHash } = require('node:crypto');
const { execFile, spawn } = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const { promisify } = require('node:util');

const execFileAsync = promisify(execFile);
const MAX_SHORTCUTS = 2500;
const MAX_DEPTH = 8;

const START_APPS_SCRIPT = [
  "$ErrorActionPreference = 'SilentlyContinue'",
  "$ProgressPreference = 'SilentlyContinue'",
  "$items = @(Get-StartApps | Where-Object { $_.Name -and $_.AppID -and $_.AppID.Contains('!') } | ForEach-Object { [pscustomobject]@{ name = $_.Name; appId = $_.AppID } })",
  'ConvertTo-Json -InputObject $items -Compress -Depth 2',
].join('; ');

function stableId(kind, value) {
  const digest = createHash('sha256').update(`${kind}\0${value.toLocaleLowerCase()}`).digest('hex').slice(0, 20);
  return `${kind}:${digest}`;
}

function cleanName(value) {
  return String(value || '')
    .replace(/\.(lnk|url)$/iu, '')
    .replace(/[\u0000-\u001f]/gu, '')
    .replace(/\s+/gu, ' ')
    .trim();
}

function classify(name, location = '') {
  const source = `${name} ${location}`.toLocaleLowerCase();
  if (/\b(game|games|steam|epic|xbox|minecraft|battle\.net|riot)\b/u.test(source)) return 'Games';
  if (/\b(word|excel|powerpoint|outlook|onenote|teams|office|notion|calendar|writer|code)\b/u.test(source)) return 'Work';
  if (/\b(photos|photo|video|music|spotify|vlc|media|camera|clipchamp)\b/u.test(source)) return 'Media';
  if (/\b(settings|control panel|terminal|task manager|security|device manager|file explorer)\b/u.test(source)) return 'System';
  return 'Utilities';
}

async function collectShortcuts(root, sourceLabel) {
  const found = [];
  const pending = [{ directory: root, depth: 0 }];

  while (pending.length && found.length < MAX_SHORTCUTS) {
    const current = pending.pop();
    let entries;
    try {
      entries = await fs.readdir(current.directory, { withFileTypes: true });
    } catch {
      continue;
    }

    for (const entry of entries) {
      if (found.length >= MAX_SHORTCUTS) break;
      if (entry.name.startsWith('.') || entry.name.toLocaleLowerCase() === 'desktop.ini') continue;
      const absolutePath = path.join(current.directory, entry.name);
      if (entry.isDirectory()) {
        if (current.depth < MAX_DEPTH) pending.push({ directory: absolutePath, depth: current.depth + 1 });
        continue;
      }
      if (!entry.isFile() || !/\.(lnk|url)$/iu.test(entry.name)) continue;

      const name = cleanName(entry.name);
      if (!name || /^(uninstall|readme|license|manual|support|help)(\b|[ ._-])/iu.test(name)) continue;
      const relativeFolder = path.relative(root, path.dirname(absolutePath));
      found.push({
        id: stableId('shortcut', absolutePath),
        name,
        category: classify(name, relativeFolder),
        source: sourceLabel,
        path: absolutePath,
        searchKey: `${relativeFolder} ${absolutePath}`,
        kind: 'shortcut',
      });
    }
  }
  return found;
}

async function getStartApps() {
  try {
    const { stdout } = await execFileAsync('powershell.exe', [
      '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', START_APPS_SCRIPT,
    ], { windowsHide: true, timeout: 15000, maxBuffer: 2 * 1024 * 1024, encoding: 'utf8' });
    const parsed = JSON.parse(stdout.trim() || '[]');
    const items = Array.isArray(parsed) ? parsed : parsed ? [parsed] : [];
    return items
      .filter((item) => typeof item.name === 'string' && typeof item.appId === 'string')
      .filter((item) => /^[\p{L}\p{N}_.!@+\-]+$/u.test(item.appId))
      .map((item) => ({
        id: stableId('uwp', item.appId),
        name: cleanName(item.name),
        category: classify(item.name),
        source: 'Windows apps',
        appId: item.appId,
        searchKey: item.appId,
        kind: 'uwp',
      }))
      .filter((item) => item.name);
  } catch {
    // PowerShell or Get-StartApps may be disabled by policy; Start Menu shortcuts
    // are still enough to provide a working desktop catalog.
    return [];
  }
}

function getSearchRoots(env = process.env) {
  const roots = [];
  if (env.APPDATA) roots.push([path.join(env.APPDATA, 'Microsoft', 'Windows', 'Start Menu', 'Programs'), 'Start menu']);
  if (env.ProgramData) roots.push([path.join(env.ProgramData, 'Microsoft', 'Windows', 'Start Menu', 'Programs'), 'All users']);
  if (env.USERPROFILE) roots.push([path.join(env.USERPROFILE, 'Desktop'), 'Desktop']);
  if (env.PUBLIC) roots.push([path.join(env.PUBLIC, 'Desktop'), 'Public desktop']);
  return [...new Map(roots.map(([root, label]) => [path.normalize(root).toLocaleLowerCase(), [root, label]])).values()];
}

async function discoverApps() {
  if (process.platform !== 'win32') {
    return { apps: [], warning: 'The native app catalog is currently implemented for Windows.' };
  }

  const roots = getSearchRoots();
  const shortcutLists = await Promise.all(roots.map(([root, label]) => collectShortcuts(root, label)));
  const uwpApps = await getStartApps();
  const sorted = [...shortcutLists.flat(), ...uwpApps];
  const unique = new Map();
  for (const item of sorted) {
    const key = item.name.normalize('NFD').replace(/\p{Mn}+/gu, '').toLocaleLowerCase();
    if (!key || unique.has(key)) continue;
    unique.set(key, item);
  }

  const apps = [...unique.values()].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base', numeric: true }));
  return { apps, warning: apps.length ? '' : 'No Start Menu shortcuts were found. Install apps or add shortcuts to the Start Menu, then refresh.' };
}

function launchDetached(command, args = []) {
  return new Promise((resolve) => {
    let settled = false;
    const child = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: true, shell: false });
    child.once('error', (error) => {
      if (settled) return;
      settled = true;
      resolve({ ok: false, error: error.message });
    });
    child.once('spawn', () => {
      if (settled) return;
      settled = true;
      child.unref();
      resolve({ ok: true });
    });
  });
}

async function launchApp(appInfo, shell) {
  if (appInfo.kind === 'shortcut' && appInfo.path) {
    const error = await shell.openPath(appInfo.path);
    return error ? { ok: false, error } : { ok: true };
  }
  if (appInfo.kind === 'uwp' && appInfo.appId && /^[\p{L}\p{N}_.!@+\-]+$/u.test(appInfo.appId)) {
    return launchDetached('explorer.exe', [`shell:AppsFolder\\${appInfo.appId}`]);
  }
  return { ok: false, error: 'This app is no longer available. Refresh the app list and try again.' };
}

const QUICK_TOOLS = Object.freeze({
  calculator: () => launchDetached('calc.exe'),
  explorer: () => launchDetached('explorer.exe', [process.env.USERPROFILE || '']),
  settings: async (shell) => {
    try {
      await shell.openExternal('ms-settings:');
      return { ok: true };
    } catch (error) {
      return { ok: false, error: error.message };
    }
  },
  terminal: () => launchDetached('wt.exe'),
  taskManager: () => launchDetached('taskmgr.exe'),
});

async function launchQuickTool(id, shell) {
  const action = typeof id === 'string' && Object.hasOwn(QUICK_TOOLS, id) ? QUICK_TOOLS[id] : null;
  if (typeof action !== 'function') return { ok: false, error: 'Unknown desktop tool.' };
  return action(shell);
}

module.exports = { classify, discoverApps, getSearchRoots, launchApp, launchQuickTool };
