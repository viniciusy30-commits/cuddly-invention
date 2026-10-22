const { app, BrowserWindow, WebContentsView, ipcMain, shell } = require('electron');
const path = require('node:path');

let mainWindow;
const panes = [];
const HOME_URL = 'https://www.google.com';

function normalizeAddress(value) {
  const input = String(value || '').trim();
  if (!input) return HOME_URL;
  if (/^(javascript|file|data|vbscript):/i.test(input)) {
    throw new Error('Esse tipo de endereço não é permitido.');
  }
  try {
    const candidate = new URL(input.includes('://') ? input : 'https://' + input);
    if (!['http:', 'https:'].includes(candidate.protocol)) throw new Error('Use um endereço HTTP ou HTTPS.');
    return candidate.href;
  } catch {
    if (/^[^\s]+\.[^\s]+$/.test(input)) return 'https://' + input;
    return 'https://www.google.com/search?q=' + encodeURIComponent(input);
  }
}

function sendPaneState(index) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const view = panes[index];
  if (!view || view.webContents.isDestroyed()) return;
  mainWindow.webContents.send('quad:pane-state', {
    index,
    url: view.webContents.getURL(),
    title: view.webContents.getTitle(),
    loading: view.webContents.isLoading(),
    canGoBack: view.webContents.navigationHistory.canGoBack(),
    canGoForward: view.webContents.navigationHistory.canGoForward()
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#10131b',
    title: 'Quad Browser',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  for (let i = 0; i < 4; i++) {
    const view = new WebContentsView({
      webPreferences: {
        partition: 'persist:quad-browser-' + (i + 1),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true
      }
    });
    panes.push(view);
    mainWindow.contentView.addChildView(view);
    view.setBounds({ x: 0, y: 0, width: 1, height: 1 });
    view.webContents.setWindowOpenHandler(({ url }) => {
      shell.openExternal(url).catch(() => {});
      return { action: 'deny' };
    });
    view.webContents.on('did-navigate', () => sendPaneState(i));
    view.webContents.on('did-navigate-in-page', () => sendPaneState(i));
    view.webContents.on('page-title-updated', () => sendPaneState(i));
    view.webContents.on('did-start-loading', () => sendPaneState(i));
    view.webContents.on('did-stop-loading', () => sendPaneState(i));
    view.webContents.on('render-process-gone', () => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('quad:error', { index: i, message: 'A página foi encerrada inesperadamente. Tente recarregá-la.' });
      }
    });
    view.webContents.loadURL(HOME_URL).catch(() => {});
  }

  mainWindow.loadFile(path.join(__dirname, 'index.html'));
  mainWindow.on('resize', () => mainWindow.webContents.send('quad:request-layout'));
  mainWindow.on('closed', () => {
    mainWindow = null;
    panes.length = 0;
  });
}

ipcMain.on('quad:set-layout', (_event, rectangles) => {
  if (!mainWindow || !Array.isArray(rectangles)) return;
  rectangles.slice(0, 4).forEach((rect, i) => {
    const view = panes[i];
    if (!view || !rect) return;
    const values = [rect.x, rect.y, rect.width, rect.height].map(Number);
    if (!values.every(Number.isFinite)) return;
    const [x, y, width, height] = values;
    view.setBounds({
      x: Math.max(0, Math.round(x)),
      y: Math.max(0, Math.round(y)),
      width: Math.max(1, Math.round(width)),
      height: Math.max(1, Math.round(height))
    });
  });
});

ipcMain.handle('quad:navigate', async (_event, index, address) => {
  const view = panes[Number(index)];
  if (!view || view.webContents.isDestroyed()) return;
  const url = normalizeAddress(address);
  await view.webContents.loadURL(url);
  sendPaneState(Number(index));
});

ipcMain.handle('quad:action', (_event, index, action) => {
  const view = panes[Number(index)];
  if (!view || view.webContents.isDestroyed()) return;
  const contents = view.webContents;
  if (action === 'back' && contents.navigationHistory.canGoBack()) contents.navigationHistory.goBack();
  else if (action === 'forward' && contents.navigationHistory.canGoForward()) contents.navigationHistory.goForward();
  else if (action === 'reload') contents.reload();
  else if (action === 'home') contents.loadURL(HOME_URL).catch(() => {});
});

ipcMain.handle('quad:open-external', async (_event, rawUrl) => {
  try {
    const url = normalizeAddress(rawUrl);
    await shell.openExternal(url);
    return true;
  } catch {
    return false;
  }
});

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
