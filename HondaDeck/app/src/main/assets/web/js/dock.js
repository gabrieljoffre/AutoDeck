// Dock inferior (copiloto, abas, volume), banner de notificação e toasts

import { sendCommand } from './bridge.js';
import {
  el,
  throttle,
  setText,
  setHidden,
  createAppIconEl,
  appCandidatesForKey,
  getPhoneIconsVersion
} from './util.js';

let switchModeFn = null;
let volumeDragging = false;
let volumeReleaseTimer = null;
let copilotPhase = 'idle';
let copilotMode = 'idle';
let lastNotifId = null;
let lastNotifSig = '';
let lastCallIconSig = '';

function createGenericMsgSvg() {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', '22');
  svg.setAttribute('height', '22');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'var(--accent)');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', 'M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z');
  svg.appendChild(p);
  return svg;
}

function createGenericPhoneSvg() {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', '22');
  svg.setAttribute('height', '22');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', 'M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.12.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.58 2.81.7A2 2 0 0 1 22 16.92z');
  svg.appendChild(p);
  return svg;
}

function candidatesForNotification(notification, phoneRoles = {}) {
  const pkg = String(notification?.packageName || '').trim();
  const isPhone = notification?.source !== 'tablet';
  const candidates = [];
  if (pkg) {
    candidates.push({ source: isPhone ? 'phone' : 'tablet', pkg });
    candidates.push({ source: isPhone ? 'tablet' : 'phone', pkg });
  }
  const appLower = String(notification?.app || '').toLowerCase();
  if (appLower.includes('whatsapp')) {
    candidates.push(...appCandidatesForKey('whatsapp', phoneRoles));
  } else if (appLower.includes('mensag') || appLower.includes('sms')) {
    candidates.push(...appCandidatesForKey('sms', phoneRoles));
  }
  return candidates;
}

const VOL_ICON_ON = 'M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7M19.5 5.5a9 9 0 0 1 0 13';
const VOL_ICON_MUTED = 'M4 9h4l5-4v14l-5-4H4zM17 9l5 6M22 9l-5 6';

const sendVolume = throttle((pct) => sendCommand('audio.setVolume', { pct }), 150);

export function initDock({ switchMode }) {
  switchModeFn = switchMode;

  document.querySelectorAll('[data-mode-tab]').forEach((btn) => {
    btn.addEventListener('click', () => switchModeFn(btn.dataset.modeTab));
  });

  // Botão único do copiloto: toca -> já escuta; tocar de novo enquanto ativo encerra.
  document.getElementById('dock-ask-gemini-btn')?.addEventListener('click', () => {
    if (copilotMode !== 'idle' || copilotPhase === 'listening' || copilotPhase === 'thinking') {
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

  document.getElementById('call-answer-btn')?.addEventListener('click', () => {
    sendCommand('phone.call.answer');
  });
  document.getElementById('call-end-btn')?.addEventListener('click', () => {
    sendCommand('phone.call.end');
  });
  document.getElementById('voice-note-cancel-btn')?.addEventListener('click', () => {
    sendCommand('phone.voice.cancel');
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

/** Reflete o estado do copiloto no botão único "Peça ao Gemini" do dock. */
export function setDockCopilotPhase(phase, mode = 'idle') {
  copilotPhase = phase;
  copilotMode = mode;
  const askBtn = document.getElementById('dock-ask-gemini-btn');
  const askLbl = document.getElementById('dock-ask-label');

  // 'live' (legado) é tratado como 'ask': só existe um fluxo de voz.
  const isAsk = mode === 'ask' || mode === 'live';
  const legacyBusy = mode === 'idle' && (phase === 'listening' || phase === 'thinking');

  if (askBtn && askLbl) {
    askBtn.classList.toggle('active', isAsk || legacyBusy);
    setText(
      askLbl,
      isAsk || legacyBusy
        ? (phase === 'speaking' ? 'GEMINI…' : phase === 'thinking' ? 'AÇÕES…' : phase === 'connecting' ? 'CONECTANDO…' : 'OUVINDO…')
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

export function updateNotification(notification, phone = null) {
  const banner = document.getElementById('notif-banner');
  if (!notification) {
    lastNotifId = null;
    lastNotifSig = '';
    setHidden(banner, true);
    return;
  }
  const roles = phone?.roles || {};
  const iconVer = getPhoneIconsVersion();
  const sig = `${notification.id}|${notification.app}|${notification.packageName || ''}|${notification.title}|${notification.text}|${iconVer}`;
  if (sig === lastNotifSig) return;
  lastNotifSig = sig;
  lastNotifId = notification.id;
  const iconBox = banner.querySelector('.notif-icon-box');
  if (iconBox) {
    iconBox.replaceChildren(
      createAppIconEl({
        candidates: candidatesForNotification(notification, roles),
        size: 32,
        radius: 9,
        alt: notification.app || 'Mensagem',
        fallback: createGenericMsgSvg()
      })
    );
  }
  setText(document.getElementById('notif-app'), notification.app || 'Mensagem');
  setText(document.getElementById('notif-title'), notification.title || '');
  setText(document.getElementById('notif-text'), notification.text || '');
  setHidden(banner, false);
}

// ---------------------------------------------------------------------------
// Banner de chamada & indicador de gravação de áudio (HondaDeck Ponte)
// ---------------------------------------------------------------------------

function formatCallDuration(startedAtMs, nowMs) {
  if (!startedAtMs || startedAtMs <= 0) return '';
  const totalSec = Math.max(0, Math.floor((nowMs - startedAtMs) / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

export function updateCallBanner(phone, nowMs = Date.now()) {
  const banner = document.getElementById('call-banner');
  if (!banner) return;
  const call = phone?.call;
  const status = String(call?.status || 'IDLE').toUpperCase();
  const active = status === 'RINGING' || status === 'DIALING' || status === 'ACTIVE';
  if (!active) {
    setHidden(banner, true);
    banner.classList.remove('ringing');
    return;
  }

  const origin = call.isWhatsApp ? 'WhatsApp' : 'Telefone';
  let statusTxt = origin;
  if (status === 'RINGING') statusTxt = `Recebendo chamada · ${origin}`;
  else if (status === 'DIALING') statusTxt = `Chamando… · ${origin}`;
  else if (status === 'ACTIVE') {
    const dur = formatCallDuration(Number(call.startedAtMs || 0), nowMs);
    statusTxt = dur ? `Em chamada · ${dur}` : `Em chamada · ${origin}`;
  }

  const roles = phone?.roles || {};
  const callIconSig = `${Boolean(call.isWhatsApp)}|${roles.dialer || ''}|${roles.whatsapp || ''}|${getPhoneIconsVersion()}`;
  if (callIconSig !== lastCallIconSig) {
    lastCallIconSig = callIconSig;
    const iconBox = banner.querySelector('.call-icon-box');
    if (iconBox) {
      iconBox.replaceChildren(
        createAppIconEl({
          candidates: appCandidatesForKey(call.isWhatsApp ? 'whatsapp' : 'dialer', roles),
          size: 32,
          radius: 9,
          alt: origin,
          fallback: createGenericPhoneSvg()
        })
      );
    }
  }

  setText(document.getElementById('call-status-label'), statusTxt);
  setText(document.getElementById('call-caller-name'), call.displayName || call.number || 'Chamada em andamento');
  setHidden(document.getElementById('call-answer-btn'), status !== 'RINGING');
  banner.classList.toggle('ringing', status === 'RINGING');
  setHidden(banner, false);
}

export function updateVoiceNotePill(phone) {
  const pill = document.getElementById('voice-note-pill');
  if (!pill) return;
  const recording = Boolean(phone?.recordingVoiceNote);
  setHidden(pill, !recording);
  if (recording) {
    setText(document.getElementById('voice-note-label'), phone.voiceNoteStatus || 'Gravando…');
  }
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
