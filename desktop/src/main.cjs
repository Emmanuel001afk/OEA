const { app, BrowserWindow, ipcMain, screen, shell } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { pathToFileURL } = require('node:url');
const { discoverApps, launchApp, launchQuickTool } = require('./host/windows.cjs');
const {
  DEFAULT_PREFERENCES,
  recordLaunch,
  sanitizePreferences,
  setAccent,
  toggleFavorite,
} = require('./shared/preferences.cjs');

const APP_ID = 'com.oea.desktop';
const TRUSTED_RENDERER_URL = pathToFileURL(path.join(__dirname, 'index.html')).href;
let mainWindow = null;
let catalog = [];
let catalogWarning = '';
let catalogById = new Map();
let preferenceState = { ...DEFAULT_PREFERENCES };
let iconCache = new Map();

app.setAppUserModelId(APP_ID);

function stateFile() {
  return path.join(app.getPath('userData'), 'desktop-state.json');
}

function loadPreferences() {
  try {
    const saved = JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
    return sanitizePreferences(saved);
  } catch {
    return { ...DEFAULT_PREFERENCES };
  }
}

function savePreferences(next) {
  preferenceState = sanitizePreferences(next);
  const destination = stateFile();
  const temporary = `${destination}.tmp`;
  try {
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(temporary, JSON.stringify(preferenceState, null, 2), { encoding: 'utf8', mode: 0o600 });
    fs.renameSync(temporary, destination);
  } catch (error) {
    try { fs.rmSync(temporary, { force: true }); } catch {}
    console.error('Could not save OEA Desktop preferences:', error.message);
  }
  return preferenceState;
}

async function refreshCatalog() {
  try {
    const result = await discoverApps();
    catalog = result.apps;
    catalogWarning = result.warning || '';
    catalogById = new Map(catalog.map((item) => [item.id, item]));
    iconCache = new Map();
  } catch (error) {
    catalog = [];
    catalogById.clear();
    catalogWarning = `Could not read this PC's app catalog: ${error.message}`;
  }
  return catalog;
}

function hostInformation() {
  const platformNames = { win32: 'Windows', darwin: 'macOS', linux: 'Linux' };
  return {
    os: platformNames[process.platform] || process.platform,
    computer: os.hostname(),
    nativeCatalog: process.platform === 'win32',
    appCount: catalog.length,
    warning: catalogWarning,
  };
}

function isTrustedSender(event) {
  return event.senderFrame?.url === TRUSTED_RENDERER_URL;
}

function registerIpc() {
  const trusted = (event) => {
    if (!isTrustedSender(event)) throw new Error('Untrusted OEA Desktop IPC sender.');
  };

  ipcMain.handle('oea:get-bootstrap', async (event) => {
    trusted(event);
    if (!catalog.length && !catalogWarning) await refreshCatalog();
    return {
      version: app.getVersion(),
      host: hostInformation(),
      apps: catalog,
      preferences: preferenceState,
    };
  });

  ipcMain.handle('oea:refresh-apps', async (event) => {
    trusted(event);
    await refreshCatalog();
    return { apps: catalog, host: hostInformation() };
  });

  ipcMain.handle('oea:get-icon', async (event, id) => {
    trusted(event);
    if (typeof id !== 'string' || id.length > 240) return '';
    const item = catalogById.get(id);
    if (!item || !item.path) return '';
    if (iconCache.has(id)) return iconCache.get(id);
    try {
      const image = await app.getFileIcon(item.path, { size: 'large' });
      const dataUrl = image.isEmpty() ? '' : image.toDataURL();
      iconCache.set(id, dataUrl);
      return dataUrl;
    } catch {
      iconCache.set(id, '');
      return '';
    }
  });

  ipcMain.handle('oea:launch-app', async (event, id) => {
    trusted(event);
    if (typeof id !== 'string' || id.length > 240) return { ok: false, error: 'Invalid app selection.' };
    const item = catalogById.get(id);
    if (!item) return { ok: false, error: 'This app is no longer available. Refresh the list and try again.' };
    const result = await launchApp(item, shell);
    if (result.ok) savePreferences(recordLaunch(preferenceState, id));
    return { ...result, preferences: preferenceState };
  });

  ipcMain.handle('oea:toggle-favorite', (event, id) => {
    trusted(event);
    if (typeof id !== 'string' || !catalogById.has(id)) return preferenceState;
    return savePreferences(toggleFavorite(preferenceState, id));
  });

  ipcMain.handle('oea:set-accent', (event, accent) => {
    trusted(event);
    return savePreferences(setAccent(preferenceState, accent));
  });

  ipcMain.handle('oea:launch-quick-tool', async (event, id) => {
    trusted(event);
    if (process.platform !== 'win32') return { ok: false, error: 'Quick system tools are currently implemented for Windows.' };
    return launchQuickTool(id, shell);
  });

  ipcMain.handle('oea:window-action', (event, action) => {
    trusted(event);
    if (!mainWindow) return false;
    if (action === 'minimize') mainWindow.minimize();
    else if (action === 'toggle-maximize') {
      if (mainWindow.isMaximized()) mainWindow.unmaximize();
      else mainWindow.maximize();
    } else if (action === 'close') mainWindow.close();
    else return false;
    return true;
  });
}

function createWindow() {
  const { width: workWidth, height: workHeight } = screen.getPrimaryDisplay().workAreaSize;
  const width = Math.min(1440, Math.max(320, workWidth - 48));
  const height = Math.min(920, Math.max(320, workHeight - 48));

  mainWindow = new BrowserWindow({
    width,
    height,
    minWidth: Math.min(960, width),
    minHeight: Math.min(640, height),
    center: true,
    frame: false,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#071014',
    title: 'OEA Desktop',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', (event, target) => {
    if (target !== TRUSTED_RENDERER_URL) event.preventDefault();
  });
  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.on('closed', () => { mainWindow = null; });
  mainWindow.loadFile(path.join(__dirname, 'index.html'));
}

app.whenReady().then(() => {
  preferenceState = loadPreferences();
  registerIpc();
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
