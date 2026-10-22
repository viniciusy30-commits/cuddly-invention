const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('quadBrowser', {
  navigate: (index, address) => ipcRenderer.invoke('quad:navigate', index, address),
  action: (index, action) => ipcRenderer.invoke('quad:action', index, action),
  openExternal: (url) => ipcRenderer.invoke('quad:open-external', url),
  setLayout: (rectangles) => ipcRenderer.send('quad:set-layout', rectangles),
  onPaneState: (callback) => ipcRenderer.on('quad:pane-state', (_event, data) => callback(data)),
  onError: (callback) => ipcRenderer.on('quad:error', (_event, data) => callback(data)),
  onRequestLayout: (callback) => ipcRenderer.on('quad:request-layout', callback)
});
