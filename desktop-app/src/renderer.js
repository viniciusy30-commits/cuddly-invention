const workspace = document.getElementById('workspace');
const panes = [...document.querySelectorAll('.pane')];
const layoutToggle = document.getElementById('layout-toggle');
let singlePane = null;
let layoutFrame = 0;

function scheduleLayout() {
  cancelAnimationFrame(layoutFrame);
  layoutFrame = requestAnimationFrame(() => {
    const rectangles = panes.map((pane) => {
      const viewport = pane.querySelector('.viewport');
      const rect = viewport.getBoundingClientRect();
      return { x: rect.left, y: rect.top, width: rect.width, height: rect.height };
    });
    window.quadBrowser.setLayout(rectangles);
  });
}

function setSinglePane(index) {
  singlePane = singlePane === index ? null : index;
  workspace.classList.toggle('single', singlePane !== null);
  panes.forEach((pane, i) => pane.classList.toggle('active', i === singlePane));
  layoutToggle.innerHTML = singlePane === null ? '▦ <span>Grade 2 × 2</span>' : '▦ <span>Voltar à grade</span>';
  scheduleLayout();
}

panes.forEach((pane, index) => {
  pane.querySelector('.address-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const input = pane.querySelector('.address');
    const status = pane.querySelector('.status-text');
    status.textContent = 'Carregando…';
    try {
      await window.quadBrowser.navigate(index, input.value);
    } catch (error) {
      status.textContent = error?.message || 'Não foi possível abrir o endereço.';
    }
  });

  pane.querySelectorAll('[data-action]').forEach((button) => {
    button.addEventListener('click', () => {
      const action = button.dataset.action;
      if (action === 'expand') {
        setSinglePane(index);
      } else {
        window.quadBrowser.action(index, action);
      }
    });
  });
});

layoutToggle.addEventListener('click', () => {
  if (singlePane === null) setSinglePane(0);
  else setSinglePane(null);
});

window.quadBrowser.onPaneState((state) => {
  const pane = panes[state.index];
  if (!pane) return;
  const input = pane.querySelector('.address');
  if (state.url && document.activeElement !== input) input.value = state.url;
  pane.querySelector('.pane-title').textContent = state.title || state.url || 'Nova página';
  pane.querySelector('.status-text').textContent = state.loading ? 'Carregando…' : 'Pronto';
  pane.querySelector('[data-action="back"]').disabled = !state.canGoBack;
  pane.querySelector('[data-action="forward"]').disabled = !state.canGoForward;
  const note = pane.querySelector('.loading-note');
  if (note) note.remove();
});

window.quadBrowser.onError((data) => {
  const pane = panes[data.index];
  if (pane) pane.querySelector('.status-text').textContent = data.message;
});

window.quadBrowser.onRequestLayout(scheduleLayout);
new ResizeObserver(scheduleLayout).observe(workspace);
window.addEventListener('resize', scheduleLayout);
window.addEventListener('DOMContentLoaded', scheduleLayout);
scheduleLayout();
