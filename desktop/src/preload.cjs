const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('oea', Object.freeze({
  getBootstrap: () => ipcRenderer.invoke('oea:get-bootstrap'),
  refreshApps: () => ipcRenderer.invoke('oea:refresh-apps'),
  getIcon: (id) => ipcRenderer.invoke('oea:get-icon', id),
  launchApp: (id) => ipcRenderer.invoke('oea:launch-app', id),
  toggleFavorite: (id) => ipcRenderer.invoke('oea:toggle-favorite', id),
  setAccent: (accent) => ipcRenderer.invoke('oea:set-accent', accent),
  launchQuickTool: (id) => ipcRenderer.invoke('oea:launch-quick-tool', id),
  windowAction: (action) => ipcRenderer.invoke('oea:window-action', action),
}));
