// Overlay do Copiloto: ouvindo (barras), pensando, cartão de resposta (8 s) com campo de texto

import { sendCommand } from './bridge.js';
import { el } from './util.js';
import { setDockCopilotPhase } from './dock.js';

const REPLY_TTL_MS = 8000;
const WAVE_HEIGHTS = [22, 38, 52, 30, 46, 26, 40];

let overlay = null;
let phase = 'idle'; // idle | listening | thinking | reply
let renderedKey = '';
let seenReply = null; // null = ainda não recebemos o primeiro estado
let shownReply = '';
let lastUser = '';
let replyText = '';
let replyUser = '';
let replyTimer = null;
let lastState = null;
let askInput = null;

function clearReplyTimer() {
  clearTimeout(replyTimer);
  replyTimer = null;
}

function armReplyTimer() {
  clearReplyTimer();
  replyTimer = setTimeout(() => {
    // Não fecha enquanto o usuário está digitando uma nova pergunta
    if (askInput && (document.activeElement === askInput || askInput.value.trim())) {
      armReplyTimer();
      return;
    }
    closeReply();
  }, REPLY_TTL_MS);
}

function closeReply() {
  clearReplyTimer();
  if (phase === 'reply') {
    replyText = '';
    applyPhase(derivePhase());
  }
}

function derivePhase() {
  const c = lastState?.copilot;
  if (c?.listening) return 'listening';
  if (c?.thinking) return 'thinking';
  if (replyText) return 'reply';
  return 'idle';
}

function buildWave() {
  const wave = el('div', { className: 'copilot-wave', 'aria-hidden': 'true' });
  WAVE_HEIGHTS.forEach((h, i) => {
    const bar = el('span', { className: 'copilot-wave-bar' });
    bar.style.height = `${h}px`;
    bar.style.animationDelay = `${(i * 0.11).toFixed(2)}s`;
    bar.style.animationDuration = `${(0.7 + (i % 3) * 0.18).toFixed(2)}s`;
    wave.appendChild(bar);
  });
  return wave;
}

function makeCloseSvg() {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', '18');
  svg.setAttribute('height', '18');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2.2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', 'M6 6l12 12M18 6L6 18');
  svg.appendChild(p);
  return svg;
}

function renderListening() {
  const suggestions = ['“Ir para casa”', '“Tocar playlist Honda Estrada”', '“Qual a temperatura do motor?”'];
  const row = el(
    'div',
    { className: 'copilot-listen-row', role: 'button', tabindex: '0', 'aria-label': 'Cancelar comando de voz' },
    buildWave(),
    el(
      'div',
      { className: 'copilot-main-texts' },
      el('span', { className: 'copilot-headline' }, 'Estou ouvindo…'),
      el('span', { className: 'copilot-subline' }, `Tente: ${suggestions.join(' · ')}`),
      el('span', { className: 'copilot-subline' }, 'Toque aqui para cancelar')
    ),
    el('span', { className: 'close-hero-btn sm', 'aria-hidden': 'true' }, makeCloseSvg())
  );
  row.addEventListener('click', () => sendCommand('copilot.cancel'));
  overlay.replaceChildren(row);
}

function renderThinking() {
  const row = el(
    'div',
    { className: 'copilot-listen-row', role: 'button', tabindex: '0', 'aria-label': 'Toque para cancelar' },
    el('span', { className: 'copilot-think-spinner', 'aria-hidden': 'true' }),
    el(
      'div',
      { className: 'copilot-main-texts' },
      el('span', { className: 'copilot-headline' }, 'Pensando…'),
      el('span', { className: 'copilot-subline' }, replyUser || lastUser ? `“${replyUser || lastUser}”` : 'Consultando o Gemini')
    )
  );
  row.addEventListener('click', () => sendCommand('copilot.cancel'));
  overlay.replaceChildren(row);
}

function submitAsk() {
  const text = (askInput?.value || '').trim();
  if (!text) return;
  askInput.value = '';
  clearReplyTimer();
  replyUser = text;
  sendCommand('copilot.ask', { text });
}

function renderReply() {
  const closeBtn = el(
    'button',
    { className: 'close-hero-btn sm', type: 'button', 'aria-label': 'Fechar resposta' },
    (() => {
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('width', '18');
      svg.setAttribute('height', '18');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('fill', 'none');
      svg.setAttribute('stroke', 'currentColor');
      svg.setAttribute('stroke-width', '2.2');
      svg.setAttribute('stroke-linecap', 'round');
      const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', 'M6 6l12 12M18 6L6 18');
      svg.appendChild(p);
      return svg;
    })()
  );
  closeBtn.addEventListener('click', closeReply);

  askInput = el('input', {
    className: 'copilot-ask-input',
    type: 'text',
    autocomplete: 'off',
    spellcheck: 'false',
    placeholder: 'Pergunte algo ao copiloto…',
    'aria-label': 'Pergunta ao copiloto'
  });
  askInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitAsk();
  });
  askInput.addEventListener('input', () => armReplyTimer());

  const askBtn = el('button', { className: 'btn-accent-sm', type: 'button' }, 'Perguntar');
  askBtn.addEventListener('click', submitAsk);

  overlay.replaceChildren(
    el(
      'div',
      { className: 'copilot-reply-header' },
      el('span', { className: 'copilot-user-q' }, replyUser ? `Você: “${replyUser}”` : 'Copiloto'),
      closeBtn
    ),
    el('div', { className: 'copilot-reply-body' }, replyText),
    el('div', { className: 'copilot-ask-form' }, askInput, askBtn)
  );
}

function applyPhase(next) {
  if (next === 'listening' || next === 'thinking') replyText = '';
  const key = next === 'reply' ? `reply|${replyUser}|${replyText}` : next === 'thinking' ? `thinking|${replyUser || lastUser}` : next;
  const dockPhase = next === 'listening' || next === 'thinking' ? next : 'idle';
  setDockCopilotPhase(dockPhase);

  if (key === renderedKey) return;
  renderedKey = key;
  phase = next;

  if (next === 'idle') {
    overlay.classList.add('hidden');
    overlay.replaceChildren();
    askInput = null;
    return;
  }
  overlay.classList.remove('hidden');
  // reinicia a animação de entrada
  overlay.style.animation = 'none';
  void overlay.offsetWidth;
  overlay.style.animation = '';

  if (next === 'listening') {
    askInput = null;
    clearReplyTimer();
    renderListening();
  } else if (next === 'thinking') {
    askInput = null;
    clearReplyTimer();
    renderThinking();
  } else {
    renderReply();
    armReplyTimer();
  }
}

function showReply(user, text) {
  const t = String(text || '').trim();
  if (!t) return;
  replyText = t;
  replyUser = String(user || lastUser || '').trim();
  shownReply = t;
  applyPhase(derivePhase());
}

export function initCopilot() {
  overlay = document.getElementById('copilot-overlay');
  // Toques dentro do cartão de resposta não devem propagar para outros alvos
  overlay.addEventListener('pointerdown', (e) => e.stopPropagation());
}

export function updateCopilot(state) {
  lastState = state;
  const c = state.copilot || {};
  lastUser = c.lastUser || lastUser;

  if (seenReply === null) {
    // Primeiro estado: não exibe resposta antiga como se fosse nova
    seenReply = c.lastReply || '';
    shownReply = seenReply;
  } else if (c.lastReply && c.lastReply !== seenReply) {
    seenReply = c.lastReply;
    if (c.lastReply !== shownReply) {
      showReply(c.lastUser, c.lastReply);
      return;
    }
  }
  if (c.lastUser && replyText && c.lastUser !== replyUser && phase !== 'reply') {
    replyUser = c.lastUser;
  }
  applyPhase(derivePhase());
}

/** Evento agentReply vindo do backend (texto da resposta). */
export function onAgentReply(text) {
  const t = String(text || '').trim();
  if (!t) return;
  seenReply = t;
  if (t === shownReply && phase === 'reply') {
    armReplyTimer();
    return;
  }
  showReply(lastState?.copilot?.lastUser || lastUser, t);
}
