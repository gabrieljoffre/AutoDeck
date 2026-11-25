// Dock inferior (copiloto, abas, volume), banner de notificação e toasts

import { sendCommand } from './bridge.js';
import { el, throttle, setText, setHidden } from './util.js';

let switchModeFn = null;
let volumeDragging = false;
let volumeReleaseTimer = null;
let copilotPhase = 'idle';
let copilotMode = 'idle';
let lastNotifId = null;
let lastNotifSig = '';

const VOL_ICON_ON = 'M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7M19.5 5.5a9 9 0 0 1 0 13';
const VOL_ICON_MUTED = 'M4 9h4l5-4v14l-5-4H4zM17 9l5 6M22 9l-5 6';

const sendVolume = throttle((pct) => sendCommand('audio.setVolume', { pct }), 150);

export function initDock({ switchMode }) {
  switchModeFn = switchMode;

  document.querySelectorAll('[data-mode-tab]').forEach((btn) => {
    btn.addEventListener('click', () => switchModeFn(btn.dataset.modeTab));
  });

  document.getElementById('dock-copilot-btn').addEventListener('click', () => {
    if (copilotMode === 'live' || copilotPhase === 'listening' || copilotPhase === 'thinking') {
      sendCommand('copilot.live.stop');
    } else {
      sendCommand('copilot.live.start');
    }
  });

  document.getElementById('dock-ask-gemini-btn')?.addEventListener('click', () => {
    if (copilotMode === 'ask') {
      sendCommand('copilot.live.stop');
    } else {
      sendCommand('copilot.ask.voice');
    }
  });

  const slider = document.getElementById('dock-vol-slider');
  const label = document.getElementById('dock-vol-label');
  const release = () => {
    clearTimeout(volumeReleaseTimer);
    // pequena folga para o estado do backend "alcançar" o valor local antes de voltar a sincronizar
    volumeReleaseTimer = setTimeout(() => {
      volumeDragging = false;
    }, 600);
  };
  slider.addEventListener('pointerdown', () => {
    clearTimeout(volumeReleaseTimer);
    volumeDragging = true;
  });
  slider.addEventListener('pointerup', release);
  slider.addEventListener('pointercancel', release);
  slider.addEventListener('change', release);
  slider.addEventListener('input', () => {
    volumeDragging = true;
    const pct = Math.round(Number(slider.value));
    setText(label, String(pct));
    sendVolume(pct);
    release();
  });

  document.getElementById('dock-mute-btn').addEventListener('click', () => {
    sendCommand('audio.toggleMute');
  });

  document.getElementById('notif-read-btn').addEventListener('click', () => {
    sendCommand('notif.read');
    hideNotification();
  });
  document.getElementById('notif-dismiss-btn').addEventListener('click', () => {
    sendCommand('notif.dismiss');
    hideNotification();
  });
}

export function setDockActiveMode(mode) {
  document.querySelectorAll('[data-mode-tab]').forEach((btn) => {
    const active = btn.dataset.modeTab === mode;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-selected', active ? 'true' : 'false');
  });
}

export function updateDock(state) {
  const audio = state.audio || {};
  const muted = Boolean(audio.muted);
  const pct = Math.max(0, Math.min(100, Math.round(Number(audio.volumePct ?? 0))));

  const slider = document.getElementById('dock-vol-slider');
  const label = document.getElementById('dock-vol-label');
  const muteBtn = document.getElementById('dock-mute-btn');
  const wrap = document.getElementById('dock-vol-wrap');

  if (!volumeDragging && String(slider.value) !== String(pct)) slider.value = String(pct);
  if (!volumeDragging) setText(label, muted ? '0' : String(pct));
  muteBtn.classList.toggle('muted', muted);
  wrap.classList.toggle('muted', muted);

  const path = document.querySelector('#dock-vol-svg path');
  if (path) {
    const next = muted ? VOL_ICON_MUTED : VOL_ICON_ON;
    if (path.getAttribute('d') !== next) path.setAttribute('d', next);
    // o segundo path (ondas) só existe no ícone ligado
    const second = document.querySelectorAll('#dock-vol-svg path')[1];
    if (second) second.style.display = muted ? 'none' : '';
  }
}

/** Reflete o estado do copiloto nos botões "Conversar" e "Peça ao Gemini" do dock. */
export function setDockCopilotPhase(phase, mode = 'idle') {
  copilotPhase = phase;
  copilotMode = mode;
  const liveBtn = document.getElementById('dock-copilot-btn');
  const liveLbl = document.getElementById('dock-copilot-label');
  const askBtn = document.getElementById('dock-ask-gemini-btn');
  const askLbl = document.getElementById('dock-ask-label');

  const isLive = mode === 'live';
  const isAsk = mode === 'ask';
  const legacyBusy = mode === 'idle' && (phase === 'listening' || phase === 'thinking');

  if (liveBtn && liveLbl) {
    liveBtn.classList.toggle('active', isLive || legacyBusy);
    liveBtn.classList.toggle('live-on', isLive);
    setText(
      liveLbl,
      isLive
        ? (phase === 'speaking' ? 'FALANDO' : phase === 'connecting' ? 'CONECTANDO' : 'AO VIVO')
        : phase === 'listening'
          ? 'OUVINDO…'
          : phase === 'thinking'
            ? 'PENSANDO…'
            : 'CONVERSAR'
    );
  }
  if (askBtn && askLbl) {
    askBtn.classList.toggle('active', isAsk);
    setText(
      askLbl,
      isAsk
        ? (phase === 'speaking' ? 'GEMINI…' : phase === 'thinking' ? 'AÇÕES…' : 'OUVINDO…')
        : 'PEÇA AO GEMINI'
    );
  }
}

// ---------------------------------------------------------------------------
// Notificação
// ---------------------------------------------------------------------------

function hideNotification() {
  setHidden(document.getElementById('notif-banner'), true);
}

export function updateNotification(notification) {
  const banner = document.getElementById('notif-banner');
  if (!notification) {
    lastNotifId = null;
    lastNotifSig = '';
    setHidden(banner, true);
    return;
  }
  const sig = `${notification.id}|${notification.app}|${notification.title}|${notification.text}`;
  if (sig === lastNotifSig) return;
  lastNotifSig = sig;
  lastNotifId = notification.id;
  setText(document.getElementById('notif-app'), notification.app || 'Mensagem');
  setText(document.getElementById('notif-title'), notification.title || '');
  setText(document.getElementById('notif-text'), notification.text || '');
  setHidden(banner, false);
}

// ---------------------------------------------------------------------------
// Toasts
// ---------------------------------------------------------------------------

export function showToast(text, kind = 'info') {
  const msg = String(text ?? '').trim();
  if (!msg) return;
  const stack = document.getElementById('toast-stack');
  if (!stack) return;
  // evita empilhar mensagens idênticas
  for (const child of stack.children) {
    if (child.dataset.msg === msg) {
      child.dataset.refresh = String(Date.now());
      return;
    }
  }
  const node = el('div', { className: kind === 'error' ? 'toast-item error' : 'toast-item', dataset: { msg } }, msg);
  stack.appendChild(node);
  while (stack.children.length > 3) stack.removeChild(stack.firstChild);
  const ttl = kind === 'error' ? 5200 : 3200;
  setTimeout(() => node.remove(), ttl);
}
