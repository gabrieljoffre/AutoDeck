// Ajustes em tela cheia (fiel a Configuracoes.dc.html): Aparência, Locais favoritos, Spotify,
// Copiloto, Veículo e Sistema. Listas dinâmicas usam assinatura para não recriar inputs em digitação.

import { sendCommand } from './bridge.js';
import {
  el,
  fmtNum,
  parseDecimal,
  jsonSig,
  createFavIconSvg,
  createAppIconEl,
  appCandidatesForKey,
  getPhoneIconsVersion
} from './util.js';
import { showToast } from './dock.js';

const SWATCH_NAMES = {
  '#22D3EE': 'Ciano',
  '#A3E635': 'Lima',
  '#FB923C': 'Âmbar',
  '#A78BFA': 'Violeta',
  '#F472B6': 'Rosa'
};
const GEMINI_MODELS = ['gemini-flash-lite-latest', 'gemini-flash-latest', 'gemini-3.8-flash'];
const GEMINI_LIVE_MODELS = ['gemini-2.5-flash-native-audio-latest', 'gemini-2.0-flash-live-001'];
const FAV_ICONS = [
  ['home', 'Casa'],
  ['work', 'Trabalho'],
  ['fuel', 'Posto'],
  ['star', 'Outro']
];
const MAX_FAVS = 12;

let lastState = null;
let currentTab = 'favorites';
let tabUpdaters = [];
let asideUpdaters = [];
let onClose = null;
let onAccent = null;
let visible = false;

const panel = () => document.getElementById('settings-main-panel');

// ---------------------------------------------------------------------------
// Infra de atualização incremental
// ---------------------------------------------------------------------------

function reg(list, sigFn, applyFn) {
  list.push({ sigFn, applyFn, sig: null });
}

function runUpdaters(list) {
  if (!lastState) return;
  for (const u of list) {
    let sig;
    try {
      sig = sigFn(u);
    } catch (err) {
      console.error('[HondaDeck] settings sig', err);
      continue;
    }
    if (sig !== u.sig) {
      u.sig = sig;
      try {
        u.applyFn(lastState);
      } catch (err) {
        console.error('[HondaDeck] settings apply', err);
      }
    }
  }
}

function sigFn(u) {
  return u.sigFn(lastState);
}

function iconSvg(paths, size = 18, stroke = 'currentColor', sw = 2) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', stroke);
  svg.setAttribute('stroke-width', String(sw));
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', paths);
  svg.appendChild(p);
  return svg;
}

const ICON_EDIT = 'M4 20h4L19 9l-4-4L4 16z';
const ICON_TRASH = 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13';

function kicker(text) {
  return el('span', { className: 'section-kicker', style: 'letter-spacing:2px;font-weight:600' }, text);
}

function header(title, sub, right = null) {
  return el(
    'div',
    { className: 'settings-header-row' },
    el('div', { style: 'display:flex;flex-direction:column;gap:4px' }, el('h1', { className: 'settings-h1' }, title), sub ? el('span', { className: 'settings-sub' }, sub) : null),
    right
  );
}

function toggleRow(label, onClick) {
  const knob = el('span', { className: 'toggle-knob' });
  const pill = el('span', { className: 'toggle-pill' }, knob);
  const btn = el('button', { className: 'toggle-row', type: 'button', role: 'switch', 'aria-checked': 'false', onClick }, el('span', null, label), pill);
  return {
    node: btn,
    set(on) {
      pill.classList.toggle('on', Boolean(on));
      btn.setAttribute('aria-checked', on ? 'true' : 'false');
    }
  };
}

function field(label, input) {
  return el('label', { className: 'field-label' }, label, input);
}

function textInput(attrs = {}) {
  return el('input', { className: 'hd-input', type: 'text', autocomplete: 'off', spellcheck: 'false', ...attrs });
}

/** Botão com confirmação inline em dois toques. */
function confirmButton(label, confirmLabel, className, onConfirm, testIds = null) {
  let timer = null;
  const attrs = { className, type: 'button' };
  if (testIds?.idle) attrs['data-test'] = testIds.idle;
  const btn = el('button', attrs, label);
  const reset = () => {
    clearTimeout(timer);
    btn.textContent = label;
    btn.classList.remove('confirming');
    if (testIds?.idle) btn.setAttribute('data-test', testIds.idle);
  };
  btn.addEventListener('click', () => {
    if (btn.classList.contains('confirming')) {
      reset();
      onConfirm();
      return;
    }
    btn.classList.add('confirming');
    btn.textContent = confirmLabel;
    if (testIds?.confirm) btn.setAttribute('data-test', testIds.confirm);
    timer = setTimeout(reset, 3500);
  });
  btn.resetConfirm = reset;
  return btn;
}

function fmtDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
  return m ? `${m[3]}/${m[2]}/${m[1]}` : String(iso || '--');
}

// ---------------------------------------------------------------------------
// Aba: Aparência
// ---------------------------------------------------------------------------

function buildAppearance(root) {
  const swatches = el('div', { className: 'swatch-row' });
  const introAnim = el('button', { className: 'btn-outline', type: 'button', onClick: () => sendCommand('settings.set', { key: 'introMode', value: 'ANIMATION' }) }, 'Animação');
  const introVideo = el('button', { className: 'btn-outline', type: 'button', onClick: () => sendCommand('settings.set', { key: 'introMode', value: 'VIDEO' }) }, 'Vídeo');

  const mapThemeAuto = el('button', { className: 'btn-outline', type: 'button', 'data-test': 'map-theme-auto', onClick: () => sendCommand('settings.set', { key: 'mapTheme', value: 'AUTO' }) }, 'Automático');
  const mapThemeLight = el('button', { className: 'btn-outline', type: 'button', 'data-test': 'map-theme-light', onClick: () => sendCommand('settings.set', { key: 'mapTheme', value: 'LIGHT' }) }, 'Claro');
  const mapThemeDark = el('button', { className: 'btn-outline', type: 'button', 'data-test': 'map-theme-dark', onClick: () => sendCommand('settings.set', { key: 'mapTheme', value: 'DARK' }) }, 'Escuro');

  root.append(
    header('Aparência', 'Cor de destaque, tema do mapa e abertura do painel. As alterações valem na hora.'),
    el('div', { className: 'settings-card-block' }, kicker('COR DE DESTAQUE'), swatches),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('TEMA DO MAPA'),
      el('div', { style: 'display:flex;gap:10px;flex-wrap:wrap' }, mapThemeAuto, mapThemeLight, mapThemeDark),
      el('span', { className: 'settings-sub' }, 'No modo automático o mapa fica claro de dia (6h–18h) e escuro à noite.')
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('ABERTURA'),
      el('div', { style: 'display:flex;gap:10px' }, introAnim, introVideo),
      el('span', { className: 'settings-sub' }, 'Com “Vídeo” a animação do logo é pulada ao abrir o painel.')
    )
  );

  reg(tabUpdaters, (s) => `${s.accent}|${jsonSig(s.settings.accentOptions)}`, (s) => {
    swatches.replaceChildren();
    s.settings.accentOptions.forEach((c, idx) => {
      const selected = String(c).toLowerCase() === String(s.accent).toLowerCase();
      const btn = el('button', {
        className: 'swatch-btn',
        type: 'button',
        'data-test': `accent-${idx}`,
        'aria-label': `Cor ${SWATCH_NAMES[String(c).toUpperCase()] || c}`,
        'aria-pressed': selected ? 'true' : 'false',
        onClick: () => onAccent && onAccent(c)
      });
      btn.style.background = c;
      btn.style.boxShadow = selected ? `0 0 0 3px #06090F, 0 0 0 5px ${c}` : 'none';
      swatches.appendChild(btn);
    });
  });
  reg(tabUpdaters, (s) => s.settings.introMode, (s) => {
    const video = String(s.settings.introMode).toUpperCase() === 'VIDEO';
    introAnim.classList.toggle('active', !video);
    introVideo.classList.toggle('active', video);
  });
  reg(tabUpdaters, (s) => String(s.settings.mapTheme || 'AUTO').toUpperCase(), (s) => {
    const t = String(s.settings.mapTheme || 'AUTO').toUpperCase();
    mapThemeAuto.classList.toggle('active', t !== 'LIGHT' && t !== 'DARK');
    mapThemeLight.classList.toggle('active', t === 'LIGHT');
    mapThemeDark.classList.toggle('active', t === 'DARK');
  });
}

// ---------------------------------------------------------------------------
// Aba: Locais favoritos
// ---------------------------------------------------------------------------

function buildFavorites(root) {
  const countLabel = el('span', { className: 'settings-sub', style: "font-family:'Chakra Petch',sans-serif" }, '');
  const list = el('div', { 'data-test': 'fav-list', style: 'display:flex;flex-direction:column;gap:10px' });
  let editing = null; // favorito em edição
  let icon = 'star';

  const nameIn = textInput({ placeholder: 'Ex.: Academia', 'data-test': 'fav-name' });
  const addrIn = textInput({ placeholder: 'Buscar endereço ou usar posição atual', 'data-test': 'fav-address' });
  const voiceIn = textInput({ placeholder: 'Ex.: ir para a academia', 'data-test': 'fav-voice' });
  const formTitle = kicker('ADICIONAR NOVO LOCAL');
  const iconRow = el('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' });
  const cancelBtn = el('button', { className: 'btn-outline hidden', type: 'button' }, 'Cancelar edição');
  const iconBtns = new Map();

  const paintIcons = () => {
    for (const [k, b] of iconBtns) b.classList.toggle('active', k === icon);
  };
  for (const [key, label] of FAV_ICONS) {
    const b = el('button', { className: 'btn-outline', type: 'button', title: label, 'aria-label': 'Ícone ' + label, style: 'width:56px;padding:0', onClick: () => { icon = key; paintIcons(); } }, createFavIconSvg(key, 20, 'currentColor'));
    iconBtns.set(key, b);
    iconRow.appendChild(b);
  }
  paintIcons();

  const resetForm = () => {
    editing = null;
    nameIn.value = '';
    addrIn.value = '';
    voiceIn.value = '';
    icon = 'star';
    paintIcons();
    formTitle.textContent = 'ADICIONAR NOVO LOCAL';
    cancelBtn.classList.add('hidden');
  };
  cancelBtn.addEventListener('click', resetForm);

  const startEdit = (fav) => {
    editing = fav;
    nameIn.value = fav.name || '';
    addrIn.value = fav.address || '';
    voiceIn.value = fav.voice || '';
    icon = ['home', 'work', 'fuel', 'star'].includes(fav.icon) ? fav.icon : 'star';
    paintIcons();
    formTitle.textContent = `EDITAR LOCAL · ${String(fav.name || '').toUpperCase()}`;
    cancelBtn.classList.remove('hidden');
    nameIn.focus();
  };

  const save = () => {
    const name = nameIn.value.trim();
    if (!name) {
      showToast('Informe o nome do local', 'error');
      nameIn.focus();
      return;
    }
    const address = addrIn.value.trim();
    if (!address && !(editing && Number(editing.lat) && Number(editing.lon))) {
      showToast('Informe o endereço ou use o local atual', 'error');
      addrIn.focus();
      return;
    }
    const args = {
      name,
      address,
      voice: voiceIn.value.trim() || `ir para ${name.toLowerCase()}`,
      icon
    };
    if (editing) {
      args.id = editing.id;
      const sameAddress = address === String(editing.address || '').trim();
      if (sameAddress && Number(editing.lat) && Number(editing.lon)) {
        args.lat = editing.lat;
        args.lon = editing.lon;
      }
    }
    sendCommand('fav.save', args);
    resetForm();
  };

  const useCurrent = () => {
    const name = nameIn.value.trim() || 'Local atual';
    sendCommand('fav.useCurrentLocation', {
      name,
      voice: voiceIn.value.trim() || `ir para ${name.toLowerCase()}`,
      icon
    });
    resetForm();
  };

  root.append(
    header('Locais favoritos', 'Aparecem no topo da navegação, prontos para um toque ou para o copiloto.', countLabel),
    list,
    el(
      'div',
      { className: 'settings-dashed-box', style: 'margin-top:auto' },
      formTitle,
      el('div', { className: 'form-grid-2' }, field('Nome', nameIn), field('Endereço', addrIn)),
      el('div', { className: 'form-grid-2' }, field('Comando de voz', voiceIn), el('div', { className: 'field-label' }, 'Ícone', iconRow)),
      el(
        'div',
        { style: 'display:flex;gap:10px;justify-content:flex-end' },
        cancelBtn,
        el('button', { className: 'btn-outline', type: 'button', 'data-test': 'fav-use-current', onClick: useCurrent }, 'Usar local atual'),
        el('button', { className: 'btn-accent-sm', type: 'button', 'data-test': 'fav-save', style: 'padding:0 22px', onClick: save }, 'Salvar favorito')
      )
    )
  );

  reg(tabUpdaters, (s) => jsonSig(s.map.favorites), (s) => {
    const favs = s.map.favorites;
    countLabel.textContent = `${favs.length} de ${MAX_FAVS}`;
    list.replaceChildren();
    if (!favs.length) {
      list.appendChild(el('span', { className: 'settings-sub', style: 'padding:10px 4px' }, 'Nenhum local favorito ainda. Adicione abaixo.'));
    }
    favs.forEach((fav, idx) => {
      const ini = String(fav.name || '?').trim().charAt(0).toUpperCase() || '?';
      list.appendChild(
        el(
          'div',
          { className: 'fav-list-row', 'data-test': `fav-item-${idx}` },
          el('span', { className: 'fav-ini-box' }, ini),
          el(
            'span',
            { style: 'flex:1;min-width:0;display:flex;flex-direction:column;gap:3px' },
            el('span', { style: 'font-size:16px;font-weight:600' }, fav.name || 'Favorito'),
            el('span', { className: 'settings-sub', style: 'font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, fav.address || 'Endereço não informado')
          ),
          fav.voice ? el('span', { className: 'settings-sub', style: 'font-size:12px;letter-spacing:1px;white-space:nowrap' }, `“${fav.voice}”`) : null,
          el('button', { className: 'icon-action-48', type: 'button', 'data-test': `fav-edit-${idx}`, 'aria-label': 'Editar local', onClick: () => startEdit(fav) }, iconSvg(ICON_EDIT)),
          el('button', { className: 'icon-action-48 danger', type: 'button', 'data-test': `fav-delete-${idx}`, 'aria-label': 'Remover local', onClick: () => {
            if (editing && editing.id === fav.id) resetForm();
            sendCommand('fav.delete', { id: fav.id });
          } }, iconSvg(ICON_TRASH))
        )
      );
    });
  });
}

// ---------------------------------------------------------------------------
// Aba: Spotify
// ---------------------------------------------------------------------------

const SPOTIFY_STATUS_TEXT = {
  CONNECTED: 'Conta conectada',
  CONNECTING: 'Conectando…',
  NEEDS_AUTH: 'Autorização necessária',
  NOT_INSTALLED: 'Aplicativo Spotify não instalado',
  FAILED: 'Falha ao conectar',
  NOT_CONFIGURED: 'Chaves do Spotify não configuradas'
};

function spotifyStatusText(s) {
  return SPOTIFY_STATUS_TEXT[String(s.media.status || '').toUpperCase()] || 'Status desconhecido';
}

function buildSpotifyToggles() {
  const pl = toggleRow('Mostrar minhas playlists', () => sendCommand('settings.set', { key: 'spotifyShowPlaylists', value: !(lastState?.settings.spotifyShowPlaylists !== false) }));
  const rec = toggleRow('Mostrar buscas recentes', () => sendCommand('settings.set', { key: 'spotifyShowRecent', value: !(lastState?.settings.spotifyShowRecent !== false) }));
  return { pl, rec };
}

function buildSpotify(root) {
  const dot = el('span', { className: 'spotify-dot' });
  const spotifyIcon = createAppIconEl({
    candidates: appCandidatesForKey('spotify'),
    size: 26,
    radius: 7,
    alt: 'Spotify',
    fallback: dot
  });
  const statusText = el('span', { style: 'font-size:16px;font-weight:600' }, '');
  const authBtn = el('button', { className: 'spotify-connect-btn', type: 'button', onClick: () => sendCommand('music.authorize') }, 'Autorizar Spotify');
  const openBtn = el(
    'button',
    { className: 'btn-outline', type: 'button', style: 'display:inline-flex;align-items:center;gap:8px', onClick: () => sendCommand('music.openApp') },
    createAppIconEl({ candidates: appCandidatesForKey('spotify'), size: 20, radius: 5, alt: 'Spotify', fallbackText: 'S' }),
    'Abrir aplicativo Spotify'
  );
  const { pl, rec } = buildSpotifyToggles();

  root.append(
    header('Spotify', 'Conexão com a sua conta e o que aparece no painel de música.'),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('CONTA'),
      el('div', { style: 'display:flex;align-items:center;gap:10px' }, spotifyIcon, statusText),
      el('div', { style: 'display:flex;gap:10px;flex-wrap:wrap' }, authBtn, openBtn)
    ),
    el('div', { className: 'settings-card-block' }, kicker('EXIBIÇÃO'), pl.node, rec.node)
  );

  reg(tabUpdaters, (s) => String(s.media.status), (s) => {
    statusText.textContent = spotifyStatusText(s);
    const st = String(s.media.status || '').toUpperCase();
    authBtn.classList.toggle('hidden', st === 'CONNECTED' || st === 'NOT_INSTALLED' || st === 'NOT_CONFIGURED');
    dot.style.background = st === 'CONNECTED' ? 'var(--spotify)' : 'var(--yellow)';
  });
  reg(tabUpdaters, (s) => `${s.settings.spotifyShowPlaylists}|${s.settings.spotifyShowRecent}`, (s) => {
    pl.set(s.settings.spotifyShowPlaylists !== false);
    rec.set(s.settings.spotifyShowRecent !== false);
  });
}

// ---------------------------------------------------------------------------
// Aba: Copiloto
// ---------------------------------------------------------------------------

function buildCopilot(root) {
  const keyStatus = el('span', { className: 'settings-sub' }, '');
  const keyIn = textInput({ type: 'password', placeholder: 'Cole a chave da API Gemini', autocomplete: 'new-password' });
  const saveKey = () => {
    const v = keyIn.value.trim();
    if (!v) {
      showToast('Cole a chave Gemini antes de salvar', 'error');
      return;
    }
    sendCommand('settings.set', { key: 'geminiKey', value: v });
    keyIn.value = '';
  };
  keyIn.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') saveKey();
  });

  const liveModelSel = el('select', { className: 'hd-select', 'aria-label': 'Modelo Gemini Live (voz)' });
  liveModelSel.addEventListener('change', () => sendCommand('settings.set', { key: 'geminiLiveModel', value: liveModelSel.value }));

  const modelSel = el('select', { className: 'hd-select', 'aria-label': 'Modelo Gemini (fallback texto)' });
  modelSel.addEventListener('change', () => sendCommand('settings.set', { key: 'geminiModel', value: modelSel.value }));

  const clearMemBtn = confirmButton(
    'Esquecer conversas',
    'Confirmar limpeza?',
    'btn-danger',
    () => sendCommand('copilot.clearMemory'),
    { idle: 'copilot-clear-memory', confirm: 'copilot-clear-memory-confirm' }
  );

  const wakeIn = textInput({ placeholder: 'ok honda' });
  const saveWake = () => {
    const v = wakeIn.value.trim();
    if (!v) {
      showToast('Informe a palavra de ativação', 'error');
      return;
    }
    sendCommand('settings.set', { key: 'wakeWord', value: v });
  };
  wakeIn.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') saveWake();
  });
  wakeIn.addEventListener('blur', () => {
    const v = wakeIn.value.trim();
    if (v && lastState && v !== lastState.settings.wakeWord) saveWake();
  });
  const wakeToggle = toggleRow('Ativar por voz (palavra de ativação)', () => sendCommand('settings.set', { key: 'wakeWordEnabled', value: !lastState?.settings.wakeWordEnabled }));
  const copilotStatus = el('span', { className: 'settings-sub' }, '');

  const openGeminiBtn = el(
    'button',
    {
      className: 'btn-outline',
      type: 'button',
      'data-test': 'settings-open-gemini',
      style: 'display:inline-flex;align-items:center;gap:8px',
      onClick: () => sendCommand('copilot.openGeminiApp')
    },
    createAppIconEl({ candidates: appCandidatesForKey('gemini'), size: 20, radius: 5, alt: 'Gemini', fallbackText: 'G' }),
    'Abrir Gemini oficial'
  );

  root.append(
    header('Copiloto (voz)', 'Assistente por voz com Gemini para rotas, música e informações do carro.'),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('GEMINI'),
      field('Chave da API', keyIn),
      keyStatus,
      el('div', { style: 'display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap' }, openGeminiBtn, el('button', { className: 'btn-accent-sm', type: 'button', onClick: saveKey }, 'Salvar chave')),
      field('Modelo Gemini Live (voz ao vivo)', liveModelSel),
      field('Modelo texto (fallback)', modelSel),
      copilotStatus
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('MEMÓRIA DAS CONVERSAS'),
      el('span', { className: 'settings-sub' }, 'O copiloto guarda um resumo curto das últimas conversas para manter contexto. Você também pode dizer “esqueça as conversas”.'),
      el('div', { style: 'display:flex' }, clearMemBtn)
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('PALAVRA DE ATIVAÇÃO'),
      field('Frase', wakeIn),
      el('div', { style: 'display:flex;justify-content:flex-end' }, el('button', { className: 'btn-outline', type: 'button', onClick: saveWake }, 'Salvar frase')),
      wakeToggle.node
    )
  );

  reg(tabUpdaters, (s) => `${s.settings.geminiKeySet}`, (s) => {
    keyStatus.textContent = s.settings.geminiKeySet ? 'Chave configurada ✓ (por segurança não é exibida)' : 'Nenhuma chave configurada — o copiloto não responderá.';
    keyStatus.style.color = s.settings.geminiKeySet ? 'var(--green)' : 'var(--yellow)';
    keyIn.placeholder = s.settings.geminiKeySet ? 'Configurada — cole outra para substituir' : 'Cole a chave da API Gemini';
  });
  reg(tabUpdaters, (s) => String(s.settings.geminiLiveModel || ''), (s) => {
    const models = [...GEMINI_LIVE_MODELS];
    if (s.settings.geminiLiveModel && !models.includes(s.settings.geminiLiveModel)) models.unshift(s.settings.geminiLiveModel);
    liveModelSel.replaceChildren(...models.map((m) => el('option', { value: m }, m)));
    liveModelSel.value = s.settings.geminiLiveModel || GEMINI_LIVE_MODELS[0];
  });
  reg(tabUpdaters, (s) => String(s.settings.geminiModel), (s) => {
    const models = [...GEMINI_MODELS];
    if (s.settings.geminiModel && !models.includes(s.settings.geminiModel)) models.unshift(s.settings.geminiModel);
    modelSel.replaceChildren(...models.map((m) => el('option', { value: m }, m)));
    modelSel.value = s.settings.geminiModel || GEMINI_MODELS[0];
  });
  reg(tabUpdaters, (s) => `${s.settings.wakeWord}|${s.settings.wakeWordEnabled}`, (s) => {
    if (document.activeElement !== wakeIn) wakeIn.value = s.settings.wakeWord || '';
    wakeToggle.set(Boolean(s.settings.wakeWordEnabled));
  });
  reg(tabUpdaters, (s) => `${s.copilot.online}|${s.copilot.status}|${s.copilot.model}`, (s) => {
    copilotStatus.textContent = `${s.copilot.online ? 'Online' : 'Offline'} · ${s.copilot.status || ''}`;
  });
}

// ---------------------------------------------------------------------------
// Aba: Celular (HondaDeck Ponte)
// ---------------------------------------------------------------------------

function fmtSyncTime(epochMs) {
  const ms = Number(epochMs || 0);
  if (!(ms > 1_000_000_000_000)) return 'Nunca sincronizado';
  const d = new Date(ms);
  const now = new Date();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) return `Hoje às ${hh}:${mm}`;
  const dd = String(d.getDate()).padStart(2, '0');
  const mo = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}/${mo} às ${hh}:${mm}`;
}

function buildPhone(root) {
  const statusDot = el('span', { className: 'phone-status-dot' });
  const statusText = el('span', { style: 'font-size:16px;font-weight:600' }, 'Desconectado');
  const statusSub = el('span', { className: 'settings-sub' }, '');
  const flagsRow = el('div', { className: 'phone-chip-row' });

  const discoverBtn = el('button', { className: 'btn-outline', type: 'button', 'data-test': 'phone-discover', onClick: () => sendCommand('phone.discover') }, 'Buscar celular');
  const reconnectBtn = el('button', { className: 'btn-outline', type: 'button', 'data-test': 'phone-reconnect', onClick: () => sendCommand('phone.reconnect') }, 'Reconectar');
  const unpairBtn = confirmButton('Desparear', 'Confirmar despareamento?', 'btn-danger', () => sendCommand('phone.unpair'), { idle: 'phone-unpair', confirm: 'phone-unpair-confirm' });

  // Bloco de Contatos sincronizados
  const contactsCountEl = el('span', { style: 'font-size:16px;font-weight:600', 'data-test': 'phone-contacts-count' }, '0 contatos sincronizados');
  const contactsSubEl = el('span', { className: 'settings-sub', 'data-test': 'phone-contacts-sync-time' }, 'Última sincronização: Nunca sincronizado');
  const syncContactsBtn = el(
    'button',
    {
      className: 'btn-accent-sm',
      type: 'button',
      'data-test': 'phone-sync-contacts',
      style: 'padding:0 20px',
      onClick: () => sendCommand('phone.syncContacts')
    },
    'Sincronizar contatos'
  );

  const discoveredList = el('div', { style: 'display:flex;flex-direction:column;gap:8px' });
  const hostIn = textInput({ placeholder: 'IP do celular (automático se vazio, ex.: 192.168.43.1)', 'data-test': 'phone-host-input' });
  const pinIn = textInput({
    inputmode: 'numeric',
    maxlength: '6',
    placeholder: 'PIN de 6 dígitos',
    'data-test': 'phone-pin-input',
    style: "font-family:'Chakra Petch',sans-serif;font-size:22px;letter-spacing:6px;text-align:center"
  });
  const submitPair = () => {
    const pin = pinIn.value.replace(/\D+/g, '').slice(0, 6);
    if (pin.length !== 6) {
      showToast('Digite os 6 dígitos do PIN exibido no celular', 'error');
      pinIn.focus();
      return;
    }
    const host = hostIn.value.trim();
    sendCommand('phone.pair', host ? { pin, host } : { pin });
    pinIn.value = '';
  };
  pinIn.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitPair();
  });

  const phoneAppsStrip = el('div', { className: 'phone-apps-strip', 'data-test': 'phone-apps-strip' });
  const msgsList = el('div', { style: 'display:flex;flex-direction:column;gap:8px' });

  root.append(
    header('Celular (HondaDeck Ponte)', 'Conecta o seu Samsung ao painel para enviar WhatsApp (texto e áudio), fazer ligações e receber mensagens.'),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('STATUS DA CONEXÃO'),
      el('div', { style: 'display:flex;align-items:center;gap:10px' }, statusDot, statusText),
      statusSub,
      flagsRow,
      phoneAppsStrip,
      el('div', { style: 'display:flex;gap:10px;flex-wrap:wrap' }, discoverBtn, reconnectBtn, unpairBtn)
    ),
    el(
      'div',
      { className: 'settings-card-block', 'data-test': 'phone-contacts-card' },
      kicker('CONTATOS SINCRONIZADOS'),
      el(
        'div',
        { style: 'display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap' },
        el('div', { style: 'display:flex;flex-direction:column;gap:4px' }, contactsCountEl, contactsSubEl),
        syncContactsBtn
      ),
      el('span', { className: 'settings-sub' }, 'Os contatos do celular ficam salvos em cache no painel para o copiloto ligar ou mandar WhatsApp por nome ou apelido.')
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('PAREAR COM PIN (UMA ÚNICA VEZ)'),
      el('span', { className: 'settings-sub' }, 'Abra o app HondaDeck Ponte no celular Samsung, verifique o PIN de 6 dígitos e digite abaixo.'),
      discoveredList,
      el('div', { className: 'form-grid-2' }, field('PIN de 6 dígitos', pinIn), field('IP do celular (opcional)', hostIn)),
      el('div', { style: 'display:flex;justify-content:flex-end' }, el('button', { className: 'btn-accent-sm', type: 'button', 'data-test': 'phone-pair-btn', style: 'padding:0 24px', onClick: submitPair }, 'Parear celular'))
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('MENSAGENS RECENTES DO CELULAR'),
      msgsList
    )
  );

  reg(tabUpdaters, (s) => jsonSig({
    c: s.phone?.connected,
    st: s.phone?.state,
    p: s.phone?.paired,
    n: s.phone?.phoneName,
    h: s.phone?.host,
    l: s.phone?.locked,
    a: s.phone?.accessibilityEnabled,
    nf: s.phone?.notificationsEnabled,
    m: s.phone?.statusMessage,
    e: s.phone?.lastError
  }), (s) => {
    const p = s.phone || {};
    const connected = Boolean(p.connected);
    statusDot.style.background = connected ? 'var(--green)' : p.paired ? 'var(--yellow)' : 'var(--rose)';
    statusText.textContent = connected
      ? `Conectado a ${p.phoneName || 'Celular'}`
      : p.paired
        ? `Desconectado (${p.phoneName || 'Celular pareado'})`
        : 'Nenhum celular pareado';
    const hostInfo = p.host ? ` · ${p.host}:${p.port || 5290}` : '';
    statusSub.textContent = `${p.statusMessage || ''}${hostInfo}`;
    unpairBtn.classList.toggle('hidden', !p.paired);
    reconnectBtn.classList.toggle('hidden', !p.paired);

    flagsRow.replaceChildren();
    if (connected) {
      const chip = (label, ok) => el('span', { className: ok ? 'phone-chip ok' : 'phone-chip warn' }, `${ok ? '✓' : '!' } ${label}`);
      flagsRow.append(
        chip(p.locked ? 'Celular bloqueado' : 'Desbloqueado', !p.locked),
        chip('Acessibilidade WhatsApp', Boolean(p.accessibilityEnabled)),
        chip('Notificações', Boolean(p.notificationsEnabled))
      );
    }
  });

  reg(tabUpdaters, (s) => `${s.phone?.iconsVersion || 0}|${jsonSig(s.phone?.roles || {})}|${jsonSig(s.phone?.iconPackages || [])}`, (s) => {
    const roles = s.phone?.roles || {};
    phoneAppsStrip.replaceChildren();
    const defs = [
      ['whatsapp', 'WhatsApp'],
      ['sms', 'Mensagens'],
      ['dialer', 'Telefone'],
      ['calendar', 'Agenda']
    ];
    for (const [key, label] of defs) {
      phoneAppsStrip.appendChild(
        el(
          'span',
          { className: 'phone-app-pill' },
          createAppIconEl({ candidates: appCandidatesForKey(key, roles), size: 22, radius: 6, alt: label, fallbackText: label[0] }),
          el('span', null, label)
        )
      );
    }
  });

  reg(tabUpdaters, (s) => jsonSig({
    c: s.phone?.connected,
    cnt: s.phone?.contactsCount,
    ts: s.phone?.contactsSyncedAtMs,
    ing: s.phone?.contactsSyncing,
    err: s.phone?.contactsSyncError
  }), (s) => {
    const p = s.phone || {};
    const count = Number(p.contactsCount || 0);
    const syncing = Boolean(p.contactsSyncing);
    const connected = Boolean(p.connected);
    contactsCountEl.textContent = count === 1
      ? '1 contato sincronizado'
      : `${count} contatos sincronizados`;
    const errPart = p.contactsSyncError ? ` · Erro: ${p.contactsSyncError}` : '';
    contactsSubEl.textContent = syncing
      ? 'Sincronizando contatos do celular…'
      : `Última sincronização: ${fmtSyncTime(p.contactsSyncedAtMs)}${errPart}`;
    syncContactsBtn.disabled = syncing || !connected;
    syncContactsBtn.textContent = syncing ? 'Sincronizando…' : 'Sincronizar agora';
  });

  reg(tabUpdaters, (s) => `${jsonSig(s.phone?.discovered || [])}|${s.phone?.iconsVersion || 0}`, (s) => {
    const list = s.phone?.discovered || [];
    const roles = s.phone?.roles || {};
    discoveredList.replaceChildren();
    if (!list.length) {
      discoveredList.appendChild(el('span', { className: 'settings-sub' }, 'Nenhum celular encontrado ainda na rede. Toque em "Buscar celular" ou informe o IP.'));
      return;
    }
    for (const d of list) {
      discoveredList.appendChild(
        el(
          'button',
          {
            className: 'fav-list-row',
            type: 'button',
            style: 'cursor:pointer;text-align:left',
            onClick: () => {
              hostIn.value = d.host || '';
              pinIn.focus();
            }
          },
          createAppIconEl({
            candidates: appCandidatesForKey('ponte', roles),
            size: 36,
            radius: 10,
            alt: 'HondaDeck Ponte',
            fallback: el('span', { className: 'fav-ini-box' }, 'P')
          }),
          el(
            'span',
            { style: 'flex:1;display:flex;flex-direction:column;gap:2px' },
            el('span', { style: 'font-size:15px;font-weight:600' }, d.name || 'HondaDeck Ponte'),
            el('span', { className: 'settings-sub', style: 'font-size:13px' }, `${d.host}:${d.port || 5290} · toque para selecionar`)
          )
        )
      );
    }
  });

  reg(tabUpdaters, (s) => `${jsonSig(s.phone?.messages || [])}|${s.phone?.iconsVersion || 0}`, (s) => {
    const msgs = s.phone?.messages || [];
    const roles = s.phone?.roles || {};
    msgsList.replaceChildren();
    if (!msgs.length) {
      msgsList.appendChild(el('span', { className: 'settings-sub' }, 'Nenhuma mensagem recebida do celular ainda.'));
      return;
    }
    for (const m of msgs.slice(0, 5)) {
      const candidates = [];
      if (m.packageName) candidates.push({ source: 'phone', pkg: m.packageName }, { source: 'tablet', pkg: m.packageName });
      const lower = String(m.app || '').toLowerCase();
      if (lower.includes('whatsapp')) candidates.push(...appCandidatesForKey('whatsapp', roles));
      else if (lower.includes('mensag') || lower.includes('sms')) candidates.push(...appCandidatesForKey('sms', roles));
      msgsList.appendChild(
        el(
          'div',
          { className: 'fav-list-row', style: 'padding:10px 14px' },
          createAppIconEl({
            candidates,
            size: 30,
            radius: 8,
            alt: m.app || 'Mensagem',
            fallbackText: (m.app || 'M')[0]
          }),
          el(
            'span',
            { style: 'flex:1;min-width:0;display:flex;flex-direction:column;gap:3px' },
            el('span', { style: 'font-size:15px;font-weight:600' }, `${m.sender || 'Contato'} · ${m.app || 'Mensagem'}`),
            el('span', { className: 'settings-sub', style: 'font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, m.text || '')
          ),
          el('button', { className: 'btn-outline', type: 'button', onClick: () => sendCommand('notif.read') }, 'Ler')
        )
      );
    }
  });
}

// ---------------------------------------------------------------------------
// Aba: Veículo
// ---------------------------------------------------------------------------

const OBD_TEXT = {
  CONNECTED: 'Conectado ao adaptador',
  CONNECTING: 'Conectando…',
  DISCONNECTED: 'Desconectado',
  SIMULATED: 'Simulador ativo'
};

function maintenanceTitle(item) {
  if (item === null || item === undefined) return '';
  if (typeof item !== 'object') return String(item);
  for (const k of ['name', 'title', 'label', 'nome', 'descricao', 'description', 'item']) {
    if (item[k]) return String(item[k]);
  }
  return 'Item de manutenção';
}

function maintenanceDetails(item) {
  if (!item || typeof item !== 'object') return '';
  const used = new Set(['name', 'title', 'label', 'nome', 'descricao', 'description', 'item', 'id']);
  const parts = [];
  for (const [k, v] of Object.entries(item)) {
    if (used.has(k) || v === null || v === undefined || v === '' || typeof v === 'object') continue;
    parts.push(`${k}: ${v}`);
  }
  return parts.join(' · ');
}

function buildVehicle(root) {
  // OBD
  const obdStatus = el('span', { style: 'font-size:16px;font-weight:600' }, '');
  const obdMsg = el('span', { className: 'settings-sub' }, '');
  const connectBtn = el('button', { className: 'btn-accent-sm', type: 'button', onClick: () => sendCommand('obd.connect') }, 'Conectar');
  const disconnectBtn = el('button', { className: 'btn-outline', type: 'button', onClick: () => sendCommand('obd.disconnect') }, 'Desconectar');
  const simToggle = toggleRow('Modo simulador (sem adaptador)', () => sendCommand('obd.setSimulator', { enabled: !lastState?.vehicle.simulator }));
  simToggle.node.setAttribute('data-test', 'settings-obd-simulator');
  const deviceSel = el('select', { className: 'hd-select', 'aria-label': 'Adaptador OBD pareado' });
  deviceSel.addEventListener('change', () => {
    if (deviceSel.value) sendCommand('obd.selectDevice', { address: deviceSel.value });
  });
  const pidsGrid = el('div', { className: 'pids-grid' });

  // Falhas
  const dtcList = el('div', { 'data-test': 'dtc-list', style: 'display:flex;flex-direction:column;gap:8px' });
  const readBtn = el('button', { className: 'btn-outline', type: 'button', 'data-test': 'dtc-read', onClick: () => sendCommand('obd.readDtc') }, 'Ler falhas');
  const clearBtn = confirmButton('Limpar falhas', 'Confirmar limpeza?', 'btn-danger', () => sendCommand('obd.clearDtc'), { idle: 'dtc-clear', confirm: 'dtc-confirm' });

  // Tanque
  const tankInfo = el('span', { style: "font-family:'Chakra Petch',sans-serif;font-size:20px;font-weight:600" }, '');
  const tankBtns = el(
    'div',
    { style: 'display:flex;gap:10px;flex-wrap:wrap' },
    el('button', { className: 'btn-outline', type: 'button', onClick: () => sendCommand('fuel.tank', { action: 'plus10' }) }, '+10 L'),
    el('button', { className: 'btn-outline', type: 'button', onClick: () => sendCommand('fuel.tank', { action: 'plus20' }) }, '+20 L'),
    el('button', { className: 'btn-outline', type: 'button', onClick: () => sendCommand('fuel.tank', { action: 'full' }) }, 'Cheio')
  );

  // Diário de abastecimento
  const kpiGrid = el('div', { className: 'summary-kpi-grid' });
  const fuelList = el('div', {
    className: 'fuel-log-list',
    'data-test': 'fuel-log-list',
    style: 'display:flex;flex-direction:column;gap:8px;max-height:230px;overflow-y:auto;overflow-x:hidden;touch-action:pan-y;overscroll-behavior:contain;-webkit-overflow-scrolling:touch'
  });
  const litersIn = textInput({ inputmode: 'decimal', placeholder: 'Litros', 'data-test': 'fuel-log-liters' });
  const priceIn = textInput({ inputmode: 'decimal', placeholder: 'R$/L (opcional)', 'data-test': 'fuel-log-price' });
  const kmIn = textInput({ inputmode: 'numeric', placeholder: 'Km do painel (opcional)', 'data-test': 'fuel-log-odometer' });
  let fullTank = true;
  const fullToggle = toggleRow('Tanque cheio', () => {
    fullTank = !fullTank;
    fullToggle.set(fullTank);
  });
  fullToggle.node.setAttribute('data-test', 'fuel-log-full');
  fullToggle.set(true);
  const logFuel = () => {
    const liters = parseDecimal(litersIn.value);
    if (!(liters > 0)) {
      showToast('Informe os litros abastecidos', 'error');
      litersIn.focus();
      return;
    }
    const args = { liters, full: fullTank };
    const price = parseDecimal(priceIn.value);
    if (price > 0) args.price = price;
    const km = parseDecimal(kmIn.value);
    if (km > 0) args.odometer = Math.round(km);
    sendCommand('fuel.log', args);
    litersIn.value = '';
    priceIn.value = '';
    kmIn.value = '';
  };

  // Manutenção
  const maintList = el('div', { 'data-test': 'settings-maintenance', style: 'display:flex;flex-direction:column;gap:8px' });

  root.append(
    header('Veículo', 'Adaptador OBD-II, diagnóstico, tanque, diário de abastecimento e manutenção.'),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('OBD-II'),
      el('div', { style: 'display:flex;flex-direction:column;gap:4px' }, obdStatus, obdMsg),
      el('div', { style: 'display:flex;gap:10px;flex-wrap:wrap' }, connectBtn, disconnectBtn),
      simToggle.node,
      field('Aparelho pareado', deviceSel),
      kicker('LEITURAS AO VIVO'),
      pidsGrid
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('FALHAS (DTC)'),
      dtcList,
      el('div', { style: 'display:flex;gap:10px;flex-wrap:wrap' }, readBtn, clearBtn)
    ),
    el('div', { className: 'settings-card-block' }, kicker('TANQUE'), tankInfo, tankBtns),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('DIÁRIO DE ABASTECIMENTO'),
      kpiGrid,
      fuelList,
      el('div', { className: 'form-grid-equal' }, field('Litros', litersIn), field('Preço por litro', priceIn)),
      el('div', { className: 'form-grid-equal' }, field('Quilometragem', kmIn), el('div', { style: 'display:flex;align-items:flex-end' }, fullToggle.node)),
      el('div', { style: 'display:flex;justify-content:flex-end' }, el('button', { className: 'btn-accent-sm', type: 'button', 'data-test': 'fuel-log-add', onClick: logFuel }, 'Registrar abastecimento'))
    ),
    el('div', { className: 'settings-card-block' }, kicker('MANUTENÇÃO'), maintList)
  );

  reg(tabUpdaters, (s) => `${s.status.obd}|${s.vehicle.obdMessage}|${s.vehicle.simulator}`, (s) => {
    const st = String(s.status.obd || 'DISCONNECTED').toUpperCase();
    obdStatus.textContent = OBD_TEXT[st] || st;
    obdMsg.textContent = s.vehicle.obdMessage || '';
    connectBtn.classList.toggle('hidden', st === 'CONNECTED' || st === 'SIMULATED');
    disconnectBtn.classList.toggle('hidden', st === 'DISCONNECTED');
    simToggle.set(Boolean(s.vehicle.simulator) || st === 'SIMULATED');
  });
  reg(tabUpdaters, (s) => `${jsonSig(s.settings.pairedDevices)}|${s.settings.obdDevice}`, (s) => {
    const opts = [el('option', { value: '' }, s.settings.pairedDevices.length ? 'Selecione um aparelho' : 'Nenhum aparelho pareado')];
    for (const d of s.settings.pairedDevices) opts.push(el('option', { value: d.address }, `${d.name || 'Dispositivo'} · ${d.address}`));
    deviceSel.replaceChildren(...opts);
    deviceSel.value = s.settings.obdDevice || '';
  });
  reg(tabUpdaters, (s) => jsonSig(s.vehicle.pids), (s) => {
    pidsGrid.replaceChildren();
    if (!s.vehicle.pids.length) pidsGrid.appendChild(el('span', { className: 'settings-sub' }, 'Sem leituras. Conecte o adaptador ou ligue o simulador.'));
    for (const p of s.vehicle.pids) {
      pidsGrid.appendChild(
        el('div', { className: 'pid-card' }, el('span', { className: 'pid-label' }, p.label || ''), el('span', { className: 'pid-value' }, String(p.value ?? '--'), p.unit ? el('span', { className: 'pid-unit' }, p.unit) : null))
      );
    }
  });
  reg(tabUpdaters, (s) => `${jsonSig(s.vehicle.dtcCodes)}|${s.vehicle.dtcBusy}`, (s) => {
    dtcList.replaceChildren();
    if (!s.vehicle.dtcCodes.length) dtcList.appendChild(el('span', { className: 'settings-sub' }, s.vehicle.dtcBusy ? 'Lendo a ECU…' : 'Nenhuma falha registrada.'));
    for (const d of s.vehicle.dtcCodes) {
      dtcList.appendChild(
        el(
          'div',
          { className: 'fav-list-row', style: 'padding:10px 14px' },
          el('span', { className: 'fav-ini-box', style: 'width:auto;padding:0 12px;color:var(--rose-soft);background:rgba(244,63,94,0.14)' }, d.code || '?'),
          el('span', { style: 'flex:1;font-size:14px' }, d.desc || '')
        )
      );
    }
    readBtn.disabled = Boolean(s.vehicle.dtcBusy);
    readBtn.textContent = s.vehicle.dtcBusy ? 'Lendo…' : 'Ler falhas';
    if (clearBtn.resetConfirm) clearBtn.resetConfirm();
  });
  reg(tabUpdaters, (s) => `${s.vehicle.tankLiters}|${s.vehicle.tankPct}`, (s) => {
    tankInfo.textContent = `${fmtNum(s.vehicle.tankLiters, 1)} L · ${Math.round(s.vehicle.tankPct)}%`;
  });
  reg(tabUpdaters, (s) => jsonSig(s.vehicle.fuelLog), (s) => {
    const sm = s.vehicle.fuelLog.summary;
    kpiGrid.replaceChildren();
    const kpis = [
      ['ÚLTIMO', `${fmtNum(sm.lastKmL, 1)} km/L`],
      ['MÉDIA', `${fmtNum(sm.avgKmL, 1)} km/L`],
      ['PREÇO MÉDIO', `R$ ${fmtNum(sm.avgPrice, 2)}`],
      ['GASTO 30 DIAS', `R$ ${fmtNum(sm.spent30d, 2)}`],
      ['CUSTO/KM', `R$ ${fmtNum(sm.costPerKm, 2)}`]
    ];
    for (const [label, val] of kpis) kpiGrid.appendChild(el('div', { className: 'kpi-box' }, el('span', { className: 'kpi-label' }, label), el('span', { className: 'kpi-val' }, val)));

    fuelList.replaceChildren();
    const entries = s.vehicle.fuelLog.entries;
    if (!entries.length) fuelList.appendChild(el('span', { className: 'settings-sub' }, 'Nenhum abastecimento registrado.'));
    entries.forEach((e, idx) => {
      fuelList.appendChild(
        el(
          'div',
          { className: 'fav-list-row', style: 'padding:8px 14px' },
          el(
            'span',
            { style: 'flex:1;display:flex;flex-direction:column;gap:2px' },
            el('span', { style: 'font-size:15px;font-weight:600' }, `${fmtDate(e.date)} · ${fmtNum(e.liters, 1)} L${e.full ? ' · cheio' : ''}`),
            el('span', { className: 'settings-sub', style: 'font-size:13px' }, `${e.price ? `R$ ${fmtNum(e.price, 2)}/L` : 'sem preço'}${e.odometer ? ` · ${Math.round(e.odometer).toLocaleString('pt-BR')} km` : ''}`)
          ),
          el('button', { className: 'icon-action-48 danger', type: 'button', 'data-test': `fuel-log-delete-${idx}`, 'aria-label': 'Excluir abastecimento', onClick: () => sendCommand('fuel.delete', { id: e.id }) }, iconSvg(ICON_TRASH))
        )
      );
    });
  });
  reg(tabUpdaters, (s) => jsonSig(s.vehicle.maintenance.items), (s) => {
    maintList.replaceChildren();
    const items = s.vehicle.maintenance.items;
    if (!items.length) maintList.appendChild(el('span', { className: 'settings-sub' }, 'Nenhum item'));
    for (const it of items) {
      const details = maintenanceDetails(it);
      maintList.appendChild(
        el(
          'div',
          { className: 'fav-list-row', style: 'padding:10px 14px' },
          el(
            'span',
            { style: 'flex:1;display:flex;flex-direction:column;gap:3px' },
            el('span', { style: 'font-size:15px;font-weight:600' }, maintenanceTitle(it)),
            details ? el('span', { className: 'settings-sub', style: 'font-size:13px' }, details) : null
          )
        )
      );
    }
  });
}

// ---------------------------------------------------------------------------
// Aba: Sistema
// ---------------------------------------------------------------------------

function buildSystem(root) {
  const ignSel = el('select', { className: 'hd-select', 'aria-label': 'Bluetooth de ignição' });
  ignSel.addEventListener('change', () => sendCommand('settings.set', { key: 'ignitionDevice', value: ignSel.value }));
  const defaultMapsRow = el('div', { className: 'default-apps-row', 'data-test': 'settings-default-maps' });
  const defaultMusicRow = el('div', { className: 'default-apps-row', 'data-test': 'settings-default-music' });
  const tabletAppsGrid = el('div', { className: 'apps-launcher-grid', 'data-test': 'settings-tablet-apps' });
  const version = el('span', { className: 'settings-sub' }, '');
  const offlineMapSub = el('span', { className: 'settings-sub', 'data-test': 'settings-offline-status' }, '');

  // Atualização do app (só na versão completo, instalada pelo USB)
  const updateMsg = el('span', { className: 'settings-sub', 'data-test': 'settings-update-status' }, '');
  const updateCheckBtn = el(
    'button',
    { className: 'btn-outline', type: 'button', 'data-test': 'settings-update-check', onClick: () => sendCommand('system.update.check') },
    'Procurar atualização'
  );
  const updateInstallBtn = el(
    'button',
    { className: 'btn-outline', type: 'button', 'data-test': 'settings-update-install', onClick: () => sendCommand('system.update.install') },
    'Atualizar agora'
  );
  const updateBlock = el(
    'div',
    { className: 'settings-card-block', 'data-test': 'settings-update' },
    kicker('ATUALIZAR APP'),
    updateMsg,
    el('div', { style: 'display:flex;gap:10px;flex-wrap:wrap' }, updateCheckBtn, updateInstallBtn)
  );

  // Controles da Câmera de Ré (padrão de vídeo + qualidade no SAA7113)
  const camStdButtons = [
    ['AUTO', 'Automático', 'camera-std-auto'],
    ['NTSC', 'NTSC', 'camera-std-ntsc'],
    ['PAL_M', 'PAL-M', 'camera-std-palm'],
    ['PAL_N', 'PAL-N', 'camera-std-paln'],
    ['PAL', 'PAL', 'camera-std-pal']
  ].map(([val, label, testId]) => {
    const btn = el(
      'button',
      {
        className: 'btn-outline',
        type: 'button',
        'data-test': testId,
        onClick: () => sendCommand('settings.set', { key: 'cameraStandardMode', value: val })
      },
      label
    );
    return { val, btn };
  });
  const camStatusSub = el('span', { className: 'settings-sub', 'data-test': 'camera-status-label' }, '');

  function camRangeRow(label, key, testId) {
    const valSpan = el('span', { style: "font-family:'Chakra Petch',sans-serif;font-size:14px;font-weight:600;min-width:38px;text-align:right;color:var(--text-primary)" }, '50');
    const input = el('input', {
      type: 'range',
      min: '0',
      max: '100',
      step: '1',
      value: '50',
      'aria-label': label,
      'data-test': testId,
      style: 'flex:1;height:36px;margin:0;accent-color:var(--accent);cursor:pointer'
    });
    input.addEventListener('input', () => {
      valSpan.textContent = input.value;
    });
    input.addEventListener('change', () => {
      const num = Math.max(0, Math.min(100, Math.round(Number(input.value))));
      valSpan.textContent = String(num);
      sendCommand('settings.set', { key, value: num });
    });
    const row = el(
      'div',
      { style: 'display:flex;align-items:center;gap:12px' },
      el('span', { style: 'min-width:90px;font-size:14px;color:var(--text-secondary)' }, label),
      input,
      valSpan
    );
    return {
      row,
      set(v) {
        const clamped = Math.max(0, Math.min(100, Math.round(Number(v ?? 50))));
        if (document.activeElement !== input) input.value = String(clamped);
        valSpan.textContent = String(clamped);
      }
    };
  }

  const camBri = camRangeRow('Brilho', 'cameraBrightness', 'camera-brightness');
  const camCon = camRangeRow('Contraste', 'cameraContrast', 'camera-contrast');
  const camSat = camRangeRow('Saturação', 'cameraSaturation', 'camera-saturation');
  const camResetBtn = el(
    'button',
    {
      className: 'btn-outline',
      type: 'button',
      'data-test': 'camera-quality-reset',
      onClick: () => {
        sendCommand('settings.set', { key: 'cameraBrightness', value: 50 });
        sendCommand('settings.set', { key: 'cameraContrast', value: 50 });
        sendCommand('settings.set', { key: 'cameraSaturation', value: 50 });
      }
    },
    'Restaurar imagem'
  );

  root.append(
    header('Sistema', 'Apps padrão, aplicativos instalados, Bluetooth de ignição, mapa offline e câmera de ré.'),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('APPS PADRÃO'),
      el('span', { className: 'settings-sub' }, 'Navegação externa'),
      defaultMapsRow,
      el('span', { className: 'settings-sub', style: 'margin-top:6px' }, 'Música'),
      defaultMusicRow
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('APLICATIVOS DO TABLET (ABRIR POR VOZ OU TOQUE)'),
      el('span', { className: 'settings-sub' }, 'Toque em qualquer aplicativo para abrir ou peça ao copiloto (ex.: “abre o Waze”).'),
      tabletAppsGrid
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('BLUETOOTH DE IGNIÇÃO'),
      field('Aparelho que liga/desliga o painel', ignSel),
      el('span', { className: 'settings-sub' }, 'Ao conectar este aparelho Bluetooth o painel entende que o carro foi ligado.')
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('MAPA OFFLINE'),
      offlineMapSub,
      el('span', { className: 'settings-sub' }, 'Fora de Goiás/DF o mapa usa a internet. O botão abaixo guarda também a região de Anápolis do mapa antigo (sem o 3D).'),
      el('div', { style: 'display:flex' }, el('button', { className: 'btn-outline', type: 'button', 'data-test': 'settings-offline-map', onClick: () => sendCommand('system.downloadOfflineMap') }, 'Baixar mapa offline'))
    ),
    el(
      'div',
      { className: 'settings-card-block' },
      kicker('CÂMERA DE RÉ'),
      el('span', { className: 'settings-sub' }, 'Padrão de vídeo analógico (EasyCap STK1160 / SAA7113). No Brasil, Automático detecta PAL-M, NTSC, PAL-N e PAL.'),
      el('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, ...camStdButtons.map((b) => b.btn)),
      camStatusSub,
      kicker('QUALIDADE DA IMAGEM'),
      el('div', { style: 'display:flex;flex-direction:column;gap:8px' }, camBri.row, camCon.row, camSat.row),
      el(
        'div',
        { style: 'display:flex;gap:10px;flex-wrap:wrap;margin-top:4px' },
        el('button', { className: 'btn-outline', type: 'button', 'data-test': 'settings-camera-test', onClick: () => sendCommand('camera.toggle') }, 'Testar câmera'),
        camResetBtn
      )
    ),
    updateBlock,
    el('div', { className: 'settings-card-block' }, kicker('VERSÃO'), version)
  );

  reg(tabUpdaters, (s) => jsonSig(s.map?.offline || {}), (s) => {
    const o = s.map?.offline || {};
    offlineMapSub.textContent = o.available
      ? `Mapa de Goiás e DF instalado no tablet (${o.sizeMb || '?'} MB): ruas e nomes funcionam sem internet.`
      : 'Mapa de Goiás e DF não instalado. Em casa, rode: .\\scripts\\instalar-usb.ps1 -Mapa (veja INSTALACAO.md).';
  });

  reg(tabUpdaters, (s) => jsonSig(s.settings?.update || {}), (s) => {
    const u = s.settings?.update || {};
    updateBlock.style.display = u.supported ? '' : 'none';
    const busy = u.status === 'CHECKING' || u.status === 'DOWNLOADING';
    updateCheckBtn.disabled = busy;
    updateInstallBtn.disabled = busy || !(u.status === 'AVAILABLE' || u.status === 'NEEDS_PERMISSION' || u.status === 'READY' || u.status === 'ERROR');
    updateInstallBtn.textContent = u.status === 'DOWNLOADING' ? `Baixando ${u.progressPct || 0}%` : 'Atualizar agora';
    const base = `Instalada: ${u.currentName || s.settings?.version || ''}`;
    const latest = u.latestName ? ` · No GitHub: ${u.latestName}` : '';
    updateMsg.textContent = `${base}${latest}${u.message ? ` — ${u.message}` : ''}`;
  });

  reg(tabUpdaters, (s) => `${jsonSig(s.apps?.mapApps || [])}|${s.apps?.preferredMapPackage || ''}`, (s) => {
    defaultMapsRow.replaceChildren();
    const pref = s.apps?.preferredMapPackage || 'com.google.android.apps.maps';
    const options = (s.apps?.mapApps || []).length
      ? s.apps.mapApps
      : [
          { packageName: 'com.google.android.apps.maps', label: 'Google Maps' },
          { packageName: 'com.waze', label: 'Waze' }
        ];
    for (const app of options) {
      const active = app.packageName === pref;
      const isInternal = app.packageName === 'INTERNAL_MAP';
      defaultMapsRow.appendChild(
        el(
          'button',
          {
            className: active ? 'btn-outline active default-app-btn' : 'btn-outline default-app-btn',
            type: 'button',
            onClick: () => sendCommand('settings.set', { key: 'preferredMapPackage', value: app.packageName })
          },
          isInternal
            ? null
            : createAppIconEl({ candidates: [{ source: 'tablet', pkg: app.packageName }, { source: 'phone', pkg: app.packageName }], size: 22, radius: 6, alt: app.label, fallbackText: (app.label || 'M')[0] }),
          el('span', null, app.label || app.packageName)
        )
      );
    }
  });

  reg(tabUpdaters, (s) => `${jsonSig(s.apps?.musicApps || [])}|${s.apps?.preferredMusicPackage || ''}`, (s) => {
    defaultMusicRow.replaceChildren();
    const pref = s.apps?.preferredMusicPackage || 'com.spotify.music';
    const options = (s.apps?.musicApps || []).length
      ? s.apps.musicApps
      : [{ packageName: 'com.spotify.music', label: 'Spotify' }];
    for (const app of options) {
      const active = app.packageName === pref;
      defaultMusicRow.appendChild(
        el(
          'button',
          {
            className: active ? 'btn-outline active default-app-btn' : 'btn-outline default-app-btn',
            type: 'button',
            onClick: () => sendCommand('settings.set', { key: 'preferredMusicPackage', value: app.packageName })
          },
          createAppIconEl({ candidates: [{ source: 'tablet', pkg: app.packageName }, { source: 'phone', pkg: app.packageName }], size: 22, radius: 6, alt: app.label, fallbackText: (app.label || 'S')[0] }),
          el('span', null, app.label || app.packageName)
        )
      );
    }
  });

  reg(tabUpdaters, (s) => jsonSig(s.apps?.tablet || []), (s) => {
    tabletAppsGrid.replaceChildren();
    const list = s.apps?.tablet || [];
    if (!list.length) {
      tabletAppsGrid.appendChild(el('span', { className: 'settings-sub' }, 'Nenhum aplicativo listado.'));
      return;
    }
    for (const app of list.slice(0, 24)) {
      tabletAppsGrid.appendChild(
        el(
          'button',
          {
            className: 'app-tile-btn',
            type: 'button',
            title: app.label || app.packageName,
            onClick: () => sendCommand('apps.open', { packageName: app.packageName, name: app.label, target: 'tablet' })
          },
          createAppIconEl({
            candidates: [{ source: 'tablet', pkg: app.packageName }],
            size: 40,
            radius: 11,
            alt: app.label || app.packageName,
            fallbackText: (app.label || '?')[0]
          }),
          el('span', { className: 'app-tile-label' }, app.label || app.packageName)
        )
      );
    }
  });

  reg(tabUpdaters, (s) => `${jsonSig(s.settings.pairedDevices)}|${s.settings.ignitionDevice}`, (s) => {
    const opts = [el('option', { value: '' }, 'Nenhum')];
    for (const d of s.settings.pairedDevices) opts.push(el('option', { value: d.address }, `${d.name || 'Dispositivo'} · ${d.address}`));
    ignSel.replaceChildren(...opts);
    ignSel.value = s.settings.ignitionDevice || '';
  });
  reg(
    tabUpdaters,
    (s) => `${s.settings.cameraStandardMode}|${s.settings.cameraDetectedLabel}|${s.settings.cameraStatusLabel}`,
    (s) => {
      const mode = String(s.settings.cameraStandardMode || 'AUTO').toUpperCase();
      for (const { val, btn } of camStdButtons) {
        btn.classList.toggle('active', val === mode);
      }
      const det = s.settings.cameraDetectedLabel || '';
      const hw = s.settings.cameraStatusLabel || '';
      camStatusSub.textContent = [det ? `Padrão ativo: ${det}` : '', hw].filter(Boolean).join(' · ');
    }
  );
  reg(
    tabUpdaters,
    (s) => `${s.settings.cameraBrightness}|${s.settings.cameraContrast}|${s.settings.cameraSaturation}`,
    (s) => {
      camBri.set(s.settings.cameraBrightness);
      camCon.set(s.settings.cameraContrast);
      camSat.set(s.settings.cameraSaturation);
    }
  );
  reg(tabUpdaters, (s) => s.settings.version, (s) => {
    version.textContent = `HondaDeck ${s.settings.version || ''} · layout Órbita`;
  });
}

// ---------------------------------------------------------------------------
// Aside rápido (cor + Spotify) — sempre visível
// ---------------------------------------------------------------------------

function buildAside() {
  const aside = document.getElementById('settings-quick-aside');
  aside.replaceChildren();
  asideUpdaters = [];

  const swatches = el('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' });
  const dot = el('span', { className: 'spotify-dot' });
  const spotifyAsideIcon = createAppIconEl({
    candidates: appCandidatesForKey('spotify'),
    size: 22,
    radius: 6,
    alt: 'Spotify',
    fallback: dot
  });
  const statusText = el('span', { style: 'font-size:15px' }, '');
  const { pl, rec } = buildSpotifyToggles();

  aside.append(
    el(
      'div',
      { className: 'aside-card' },
      kicker('LAYOUT DO PAINEL'),
      el(
        'div',
        { className: 'btn-outline active', style: 'justify-content:space-between;min-height:52px;padding:0 16px;font-size:15px;font-weight:600;color:var(--text-primary)' },
        'Órbita',
        iconSvg('M5 12l5 5 9-10', 18, 'var(--accent)', 2.4)
      ),
      kicker('COR DE DESTAQUE'),
      swatches
    ),
    el('div', { className: 'aside-card grow' }, kicker('SPOTIFY'), el('span', { style: 'display:flex;align-items:center;gap:10px' }, spotifyAsideIcon, statusText), pl.node, rec.node)
  );

  reg(asideUpdaters, (s) => `${s.accent}|${jsonSig(s.settings.accentOptions)}`, (s) => {
    swatches.replaceChildren();
    for (const c of s.settings.accentOptions) {
      const selected = String(c).toLowerCase() === String(s.accent).toLowerCase();
      const btn = el('button', {
        className: 'swatch-btn',
        type: 'button',
        'aria-label': `Cor ${SWATCH_NAMES[String(c).toUpperCase()] || c}`,
        onClick: () => onAccent && onAccent(c)
      });
      btn.style.width = '40px';
      btn.style.height = '40px';
      btn.style.background = c;
      btn.style.boxShadow = selected ? `0 0 0 3px #06090F, 0 0 0 5px ${c}` : 'none';
      swatches.appendChild(btn);
    }
  });
  reg(asideUpdaters, (s) => String(s.media.status), (s) => {
    statusText.textContent = spotifyStatusText(s);
    dot.style.background = String(s.media.status).toUpperCase() === 'CONNECTED' ? 'var(--spotify)' : 'var(--yellow)';
  });
  reg(asideUpdaters, (s) => `${s.settings.spotifyShowPlaylists}|${s.settings.spotifyShowRecent}`, (s) => {
    pl.set(s.settings.spotifyShowPlaylists !== false);
    rec.set(s.settings.spotifyShowRecent !== false);
  });
}

// ---------------------------------------------------------------------------
// API pública
// ---------------------------------------------------------------------------

const BUILDERS = {
  appearance: buildAppearance,
  favorites: buildFavorites,
  spotify: buildSpotify,
  copilot: buildCopilot,
  phone: buildPhone,
  vehicle: buildVehicle,
  system: buildSystem
};

export function showSettingsTab(tab) {
  const key = BUILDERS[tab] ? tab : 'favorites';
  currentTab = key;
  document.querySelectorAll('[data-settings-tab]').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.settingsTab === key);
  });
  const root = panel();
  root.replaceChildren();
  root.scrollTop = 0;
  tabUpdaters = [];
  BUILDERS[key](root);
  if (visible) runUpdaters(tabUpdaters);
}

export function initSettings({ onBack, onAccentPick }) {
  onClose = onBack;
  onAccent = onAccentPick;
  document.getElementById('settings-back-btn').addEventListener('click', () => onClose && onClose());
  document.querySelectorAll('[data-settings-tab]').forEach((btn) => {
    btn.addEventListener('click', () => showSettingsTab(btn.dataset.settingsTab));
  });
  buildAside();
  showSettingsTab(currentTab);
}

export function setSettingsVisible(isVisible) {
  visible = isVisible;
  if (visible && lastState) {
    // força nova avaliação ao entrar (estado pode ter mudado enquanto oculto)
    for (const u of tabUpdaters) u.sig = null;
    for (const u of asideUpdaters) u.sig = null;
    runUpdaters(asideUpdaters);
    runUpdaters(tabUpdaters);
  }
}

export function updateSettings(state) {
  lastState = state;
  if (!visible) return;
  runUpdaters(asideUpdaters);
  runUpdaters(tabUpdaters);
}
