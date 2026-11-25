// Interface do Copiloto:
// 1. Modo "Conversar" (Gemini Live contínuo): painel flutuante com onda reativa ao áudio, transcrições e botão Encerrar.
// 2. Modo "Peça ao Gemini" (one-shot): faixa que sobe na parte inferior (estilo assistente do Android), já gravando,
//    executa, responde e fecha sozinha.
// 3. Fluxo legado de resposta de texto / pergunta digitada (copilot.ask).

import { sendCommand } from './bridge.js';
import { el, setText, createAppIconEl, appCandidatesForKey } from './util.js';
import { setDockCopilotPhase } from './dock.js';

const REPLY_TTL_MS = 8000;
const LIVE_BAR_WEIGHTS = [0.38, 0.56, 0.78, 0.94, 1.0, 0.88, 1.0, 0.92, 0.76, 0.54, 0.36];
const ASK_BAR_WEIGHTS = [0.42, 0.68, 0.92, 1.0, 0.86, 0.98, 0.74, 0.52];

let overlay = null;
let askSheet = null;
let phase = 'idle'; // idle | live | ask | listening | thinking | reply
let renderedKey = '';
let seenReply = null;
let shownReply = '';
let lastUser = '';
let replyText = '';
let replyUser = '';
let replyTimer = null;
let lastState = null;
let askInput = null;

// Refs para atualização incremental sem recriar DOM (evita flicker durante streaming de transcrição/nível)
let liveDom = null;
let askDom = null;
let targetLevel = 0;
let smoothLevel = 0;
let waveRafId = 0;

function clearReplyTimer() {
  clearTimeout(replyTimer);
  replyTimer = null;
}

function armReplyTimer() {
  clearReplyTimer();
  replyTimer = setTimeout(() => {
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
  const mode = String(c?.mode || 'idle').toLowerCase();
  if (mode === 'live') return 'live';
  if (mode === 'ask') return 'ask';
  if (c?.listening) return 'listening';
  if (c?.thinking) return 'thinking';
  if (replyText) return 'reply';
  return 'idle';
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

function makeGeminiSparkleSvg(size = 24) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'currentColor');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', 'M12 2c.7 4.9 4.1 8.3 9 9-4.9.7-8.3 4.1-9 9-.7-4.9-4.1-8.3-9-9 4.9-.7 8.3-4.1 9-9z');
  svg.appendChild(p);
  return svg;
}

function makeOpenGeminiAppBtn(compact = false) {
  const icon = createAppIconEl({
    candidates: appCandidatesForKey('gemini', lastState?.phone?.roles || {}),
    size: compact ? 20 : 22,
    radius: 6,
    alt: 'Gemini',
    fallback: makeGeminiSparkleSvg(compact ? 16 : 18)
  });
  return el(
    'button',
    {
      className: compact ? 'copilot-open-gemini-btn compact' : 'copilot-open-gemini-btn',
      type: 'button',
      'data-test': 'copilot-open-gemini',
      'aria-label': 'Abrir aplicativo Gemini oficial',
      onClick: () => sendCommand('copilot.openGeminiApp')
    },
    icon,
    el('span', null, compact ? 'Gemini oficial' : 'Abrir Gemini oficial')
  );
}

function buildReactiveWave(weights, barClass) {
  const wave = el('div', { className: 'copilot-reactive-wave', 'aria-hidden': 'true' });
  const bars = weights.map((w, idx) => {
    const bar = el('span', { className: barClass });
    bar.dataset.weight = String(w);
    bar.dataset.idx = String(idx);
    wave.appendChild(bar);
    return bar;
  });
  return { wave, bars };
}

function ensureWaveLoop() {
  if (waveRafId) return;
  const tick = (ts) => {
    if (phase !== 'live' && phase !== 'ask' && phase !== 'listening') {
      waveRafId = 0;
      return;
    }
    smoothLevel += (targetLevel - smoothLevel) * 0.28;
    const bars = phase === 'live'
      ? liveDom?.bars
      : phase === 'ask'
        ? askDom?.bars
        : overlay?.querySelectorAll('.copilot-wave-bar');

    if (bars && bars.length) {
      const cPhase = String(lastState?.copilot?.phase || '');
      const speaking = Boolean(lastState?.copilot?.speaking) || cPhase === 'speaking';
      const activeFloor = cPhase === 'connecting' ? 0.14 : speaking ? 0.22 : 0.12;
      const lvl = Math.max(activeFloor, Math.min(1, smoothLevel));
      const t = ts * 0.008;

      bars.forEach((bar, idx) => {
        const w = Number(bar.dataset.weight || 0.75);
        const ripple = 0.55 + 0.45 * Math.sin(t + idx * 0.72);
        const h = Math.round(10 + lvl * ripple * w * 46);
        bar.style.height = `${h}px`;
        bar.style.opacity = String(0.55 + lvl * 0.45);
      });
    }
    waveRafId = requestAnimationFrame(tick);
  };
  waveRafId = requestAnimationFrame(tick);
}

// ---------------------------------------------------------------------------
// 1. Modo Live Contínuo ("Conversar")
// ---------------------------------------------------------------------------

function mountLiveCard() {
  const { wave, bars } = buildReactiveWave(LIVE_BAR_WEIGHTS, 'copilot-live-bar');
  const statusPill = el('span', { className: 'copilot-live-pill' }, 'GEMINI LIVE');
  const phaseLabel = el('span', { className: 'copilot-live-phase' }, 'Conectando…');
  const stopBtn = el(
    'button',
    {
      className: 'copilot-end-btn',
      type: 'button',
      'data-test': 'copilot-live-stop',
      'aria-label': 'Encerrar conversa ao vivo'
    },
    'Encerrar'
  );
  stopBtn.addEventListener('click', () => sendCommand('copilot.live.stop'));

  const userLine = el('div', { className: 'copilot-transcript-line user hidden' });
  const userLabel = el('span', { className: 'copilot-transcript-tag' }, 'Você');
  const userTextNode = el('span', { className: 'copilot-transcript-text', 'data-test': 'copilot-user-text' }, '');
  userLine.append(userLabel, userTextNode);

  const replyLine = el('div', { className: 'copilot-transcript-line model hidden' });
  const replyLabel = el('span', { className: 'copilot-transcript-tag' }, 'Gemini');
  const replyTextNode = el('span', { className: 'copilot-transcript-text', 'data-test': 'copilot-reply-text' }, '');
  replyLine.append(replyLabel, replyTextNode);

  const hintNode = el(
    'span',
    { className: 'copilot-live-hint' },
    'Conversa contínua · pode falar por cima para interromper · encerra após 20 s de silêncio'
  );

  const card = el(
    'div',
    { className: 'copilot-live-card', 'data-test': 'copilot-live-panel' },
    el(
      'div',
      { className: 'copilot-live-header' },
      el(
        'div',
        { className: 'copilot-live-title-group' },
        el('span', { className: 'copilot-live-dot', 'aria-hidden': 'true' }),
        statusPill,
        phaseLabel
      ),
      el(
        'div',
        { className: 'copilot-live-actions' },
        makeOpenGeminiAppBtn(true),
        stopBtn
      )
    ),
    el('div', { className: 'copilot-live-wave-box' }, wave),
    el('div', { className: 'copilot-live-transcript' }, userLine, replyLine),
    hintNode
  );

  overlay.replaceChildren(card);
  liveDom = {
    card,
    phaseLabel,
    bars,
    userLine,
    userTextNode,
    replyLine,
    replyTextNode,
    hintNode
  };
}

function syncLiveCard(c) {
  if (!liveDom) mountLiveCard();
  const cPhase = String(c?.phase || 'listening');
  const isSpeaking = Boolean(c?.speaking) || cPhase === 'speaking';
  const isThinking = Boolean(c?.thinking) || cPhase === 'thinking';
  liveDom.card.classList.toggle('is-speaking', isSpeaking);
  liveDom.card.classList.toggle('is-thinking', isThinking);

  const phaseText = c?.status || (
    cPhase === 'connecting'
      ? 'Conectando ao Gemini Live…'
      : isThinking
        ? 'Executando ação no painel…'
        : isSpeaking
          ? 'Gemini respondendo…'
          : 'Ao vivo · pode falar'
  );
  setText(liveDom.phaseLabel, phaseText);

  const uTxt = String(c?.userText || '').trim();
  const rTxt = String(c?.replyText || '').trim();
  liveDom.userLine.classList.toggle('hidden', !uTxt);
  if (uTxt) setText(liveDom.userTextNode, uTxt);

  liveDom.replyLine.classList.toggle('hidden', !rTxt);
  if (rTxt) setText(liveDom.replyTextNode, rTxt);
}

// ---------------------------------------------------------------------------
// 2. Faixa "Peça ao Gemini" (estilo assistente inferior do Android)
// ---------------------------------------------------------------------------

function mountAskSheet() {
  const { wave, bars } = buildReactiveWave(ASK_BAR_WEIGHTS, 'copilot-ask-bar');
  const titleNode = el('span', { className: 'copilot-ask-title' }, 'Peça ao Gemini');
  const userNode = el('span', { className: 'copilot-ask-user hidden', 'data-test': 'copilot-ask-user' }, '');
  const replyNode = el('span', { className: 'copilot-ask-reply hidden', 'data-test': 'copilot-ask-reply' }, '');

  const closeBtn = el(
    'button',
    {
      className: 'close-hero-btn sm',
      type: 'button',
      'data-test': 'copilot-ask-close',
      'aria-label': 'Fechar Peça ao Gemini'
    },
    makeCloseSvg()
  );
  closeBtn.addEventListener('click', () => sendCommand('copilot.live.stop'));

  const inner = el(
    'div',
    { className: 'copilot-ask-sheet-inner' },
    el('div', { className: 'copilot-ask-glow-bar', 'aria-hidden': 'true' }),
    el(
      'div',
      { className: 'copilot-ask-row' },
      el('span', { className: 'copilot-ask-orb', 'aria-hidden': 'true' },
        createAppIconEl({
          candidates: appCandidatesForKey('gemini', lastState?.phone?.roles || {}),
          size: 32,
          radius: 9,
          alt: 'Gemini',
          fallback: makeGeminiSparkleSvg(26)
        })
      ),
      el('div', { className: 'copilot-ask-wave-wrap' }, wave),
      el('div', { className: 'copilot-ask-texts' }, titleNode, userNode, replyNode),
      makeOpenGeminiAppBtn(true),
      closeBtn
    )
  );

  askSheet.replaceChildren(inner);
  askDom = { inner, titleNode, userNode, replyNode, bars };
}

function syncAskSheet(c) {
  if (!askDom) mountAskSheet();
  const cPhase = String(c?.phase || 'listening');
  const isSpeaking = Boolean(c?.speaking) || cPhase === 'speaking';
  const isThinking = Boolean(c?.thinking) || cPhase === 'thinking';
  askDom.inner.classList.toggle('is-speaking', isSpeaking);
  askDom.inner.classList.toggle('is-thinking', isThinking);

  const headline = c?.status || (
    cPhase === 'connecting'
      ? 'Conectando ao Gemini…'
      : isThinking
        ? 'Executando…'
        : isSpeaking
          ? 'Gemini respondendo…'
          : 'Ouvindo… pode falar'
  );
  setText(askDom.titleNode, headline);

  const uTxt = String(c?.userText || '').trim();
  const rTxt = String(c?.replyText || '').trim();
  askDom.userNode.classList.toggle('hidden', !uTxt);
  if (uTxt) setText(askDom.userNode, `“${uTxt}”`);

  askDom.replyNode.classList.toggle('hidden', !rTxt);
  if (rTxt) setText(askDom.replyNode, rTxt);
}

// ---------------------------------------------------------------------------
// 3. Fluxo legado (ouvindo / pensando / resposta de texto + input)
// ---------------------------------------------------------------------------

function renderListening() {
  const { wave } = buildReactiveWave([0.45, 0.72, 1.0, 0.62, 0.88, 0.52, 0.78], 'copilot-wave-bar');
  const suggestions = ['“Ir para casa”', '“Tocar Coldplay”', '“Qual a temperatura do motor?”'];
  const row = el(
    'div',
    { className: 'copilot-listen-row', role: 'button', tabindex: '0', 'aria-label': 'Cancelar comando de voz' },
    wave,
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
    makeCloseSvg()
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
    el('div', { className: 'copilot-ask-form' }, askInput, askBtn, makeOpenGeminiAppBtn(true))
  );
}

// ---------------------------------------------------------------------------
// Máquina de estado visual
// ---------------------------------------------------------------------------

function applyPhase(next) {
  const c = lastState?.copilot || {};
  const mode = String(c.mode || 'idle').toLowerCase();
  const cPhase = String(c.phase || next);

  if (next === 'live' || next === 'ask' || next === 'listening' || next === 'thinking') {
    replyText = '';
    clearReplyTimer();
  }

  setDockCopilotPhase(cPhase, mode);

  if (next === 'live') {
    askSheet.classList.add('hidden');
    askDom = null;
    overlay.classList.remove('hidden');
    overlay.classList.add('live-mode');
    if (phase !== 'live') {
      phase = 'live';
      renderedKey = 'live';
      askInput = null;
      mountLiveCard();
    }
    syncLiveCard(c);
    ensureWaveLoop();
    return;
  }

  if (next === 'ask') {
    overlay.classList.add('hidden');
    overlay.classList.remove('live-mode');
    overlay.replaceChildren();
    liveDom = null;
    askInput = null;
    askSheet.classList.remove('hidden');
    if (phase !== 'ask') {
      phase = 'ask';
      renderedKey = 'ask';
      mountAskSheet();
    }
    syncAskSheet(c);
    ensureWaveLoop();
    return;
  }

  // Não está em live nem em ask: fecha a faixa inferior
  askSheet.classList.add('hidden');
  askSheet.replaceChildren();
  askDom = null;
  liveDom = null;
  overlay.classList.remove('live-mode');

  const key = next === 'reply'
    ? `reply|${replyUser}|${replyText}`
    : next === 'thinking'
      ? `thinking|${replyUser || lastUser}`
      : next;

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
  overlay.style.animation = 'none';
  void overlay.offsetWidth;
  overlay.style.animation = '';

  if (next === 'listening') {
    askInput = null;
    clearReplyTimer();
    renderListening();
    ensureWaveLoop();
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
  askSheet = document.getElementById('copilot-ask-sheet');
  overlay.addEventListener('pointerdown', (e) => e.stopPropagation());
  askSheet.addEventListener('pointerdown', (e) => e.stopPropagation());
}

export function updateCopilotLevel(level) {
  const n = Number(level);
  if (Number.isFinite(n)) {
    targetLevel = Math.max(0, Math.min(1, n));
  }
}

export function updateCopilot(state) {
  lastState = state;
  const c = state.copilot || {};
  lastUser = c.userText || c.lastUser || lastUser;
  if (c.level !== undefined) updateCopilotLevel(c.level);

  const activeVoiceMode = c.mode === 'live' || c.mode === 'ask';

  if (seenReply === null) {
    seenReply = c.lastReply || '';
    shownReply = seenReply;
  } else if (!activeVoiceMode && c.lastReply && c.lastReply !== seenReply) {
    seenReply = c.lastReply;
    // Se acabamos de sair do modo Live/Ask, não abre popup redundante
    if (phase !== 'live' && phase !== 'ask' && c.lastReply !== shownReply) {
      showReply(c.lastUser, c.lastReply);
      return;
    }
    shownReply = c.lastReply;
  } else if (activeVoiceMode && c.lastReply) {
    seenReply = c.lastReply;
    shownReply = c.lastReply;
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
  const mode = lastState?.copilot?.mode;
  if (mode === 'live' || mode === 'ask') return;
  seenReply = t;
  if (t === shownReply && phase === 'reply') {
    armReplyTimer();
    return;
  }
  showReply(lastState?.copilot?.lastUser || lastUser, t);
}
