// Ponto de entrada do painel Órbita: liga ponte, modos, topo, monitoramento e módulos

import { setupBridge, signalReady, sendCommand } from './bridge.js';
import {
  applyAccentCssVars,
  formatClockAndDate,
  createWeatherSvg,
  setText,
  setHidden,
  setPhoneIconsVersion,
  hydrateStaticAppIcons
} from './util.js';
import { initIntro, updateBrandAndIntroMode } from './intro.js';
import { initBackground, setBackgroundAccent, setBackgroundSpeed, setBackgroundMode } from './background.js';
import { initGauge, updateGaugeTargets } from './gauge.js';
import { initMap, setMapMode, setMapAccent, updateMapState } from './map.js';
import { initMusic, updateMusic, focusFullMusicSearch } from './music.js';
import { initDock, updateDock, updateNotification, updateCallBanner, updateVoiceNotePill, setDockActiveMode, showToast } from './dock.js';
import { initCopilot, updateCopilot, updateCopilotLevel, onAgentReply } from './copilot.js';
import { initSettings, updateSettings, setSettingsVisible, showSettingsTab } from './settings.js';
import { initCalendarCard, updateCalendarCard, openCalendarCard, closeCalendarCard } from './calendar-card.js';
import { initWeatherCard, updateWeatherCard, openWeatherCard, closeWeatherCard } from './weather-card.js';
import { initVehicle, updateVehicle } from './vehicle.js';

const MODES = ['home', 'map', 'mapFull', 'music', 'vehicle', 'settings'];

let state = null;
let mode = 'home';
let accent = '';
let clockOffsetMs = 0;
let weatherKind = '';
let lastAlerts = new Set();

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// Modos
// ---------------------------------------------------------------------------

// Painéis 3/4 que entram pela lateral e saem pelo mesmo lado ao fechar
const SIDE_PANELS = { music: 'view-music', vehicle: 'view-vehicle' };
const PANEL_OUT_MS = 340;
let closingTimer = null;

function switchMode(next, opts = {}) {
  if (!MODES.includes(next)) return;
  if (closingTimer) return; // ignora toques durante a animação de saída
  const panelId = SIDE_PANELS[mode];
  if (panelId && next !== mode && !opts.immediate) {
    // Anima a saída do painel 3/4 e só então troca de modo
    const view = $(panelId);
    view?.classList.add('is-closing');
    $('app-shell').classList.add('side-panel-closing');
    closingTimer = setTimeout(() => {
      closingTimer = null;
      view?.classList.remove('is-closing');
      $('app-shell').classList.remove('side-panel-closing');
      applyMode(next, opts);
    }, PANEL_OUT_MS);
    return;
  }
  applyMode(next, opts);
}

function applyMode(next, { silent = false } = {}) {
  const changed = next !== mode;
  mode = next;

  const isHome = mode === 'home';
  const isMap = mode === 'map' || mode === 'mapFull';
  const isMusic = mode === 'music';
  const isVehicle = mode === 'vehicle';
  const isSettings = mode === 'settings';

  // #view-home fica montado (exceto em Ajustes) para que o velocímetro e o Spotify
  // saiam e voltem deslizando pelas laterais em Mapa cheio, Música e Veículo.
  setHidden($('view-home'), isSettings);
  setHidden($('view-map'), !isMap);
  setHidden($('view-music'), !isMusic);
  setHidden($('view-vehicle'), !isVehicle);
  setHidden($('view-settings'), !isSettings);

  const shell = $('app-shell');
  shell.classList.toggle('mode-home-active', isHome);
  shell.classList.toggle('mode-map-active', mode === 'map');
  shell.classList.toggle('mode-map-full', mode === 'mapFull');
  shell.classList.toggle('mode-music-active', isMusic);
  shell.classList.toggle('mode-vehicle-active', isVehicle);
  shell.classList.toggle('mode-settings-active', isSettings);

  const globalMap = $('global-map-layer');
  if (globalMap) {
    globalMap.classList.toggle('hidden', isSettings);
    globalMap.classList.toggle('map-expanded', isMap);
    globalMap.classList.toggle('map-fullscreen', mode === 'mapFull');
    globalMap.classList.toggle('map-quarter-left', isMusic);
    globalMap.classList.toggle('map-quarter-right', isVehicle);
  }

  $('view-map')?.classList.toggle('map-full-active', mode === 'mapFull');
  setDockActiveMode(isMap ? 'map' : isVehicle ? 'home' : mode);
  setMapMode(mode);
  setBackgroundMode(isMap ? 'map' : isVehicle ? 'home' : mode);
  setSettingsVisible(isSettings);

  if (changed && !silent) sendCommand('ui.mode', { mode });
}

function applyAccent(color) {
  const hex = applyAccentCssVars(color);
  accent = hex;
  setBackgroundAccent(hex);
  setMapAccent(hex);
}

function pickAccent(color) {
  applyAccent(color);
  sendCommand('ui.setAccent', { color });
}

// ---------------------------------------------------------------------------
// Topo
// ---------------------------------------------------------------------------

function nowMs() {
  return Date.now() + clockOffsetMs;
}

function renderClock() {
  const now = nowMs();
  const { time, date } = formatClockAndDate(now);
  setText($('clock-time'), time);
  setText($('clock-date'), date);
  if (state?.phone?.call?.status === 'ACTIVE') {
    updateCallBanner(state.phone, now);
  }
}

function renderWeather(w) {
  const available = Boolean(w.available);
  setText($('weather-temp'), available ? `${Math.round(Number(w.tempC))}°C` : '--°C');
  setText($('weather-range'), available ? `máx ${Math.round(Number(w.maxC))}° · mín ${Math.round(Number(w.minC))}°` : 'máx --° · mín --°');
  const kind = available ? String(w.kind || 'PARTLY_CLOUDY') : 'NONE';
  if (kind !== weatherKind) {
    weatherKind = kind;
    $('weather-icon').replaceChildren(createWeatherSvg(available ? kind : 'CLOUD'));
  }
  const box = $('weather-box');
  const title = available ? `${w.city || ''} · ${w.summary || ''}`.replace(/^ · | · $/g, '') : 'Clima indisponível';
  if (box.title !== title) box.title = title;
  box.style.opacity = available ? '1' : '0.5';
}

function renderStatus(st) {
  const phonePill = $('status-phone');
  if (phonePill) {
    const connected = Boolean(st.phone);
    const pState = String(st.phoneState || 'DISCONNECTED').toUpperCase();
    phonePill.classList.toggle('on', connected);
    phonePill.classList.toggle('connecting', !connected && (pState === 'CONNECTING' || pState === 'DISCOVERING' || pState === 'PAIRING'));
    const pLabel = connected
      ? `Celular conectado: ${st.phoneName || 'HondaDeck Ponte'}`
      : 'Celular desconectado · toque para parear';
    if (phonePill.getAttribute('aria-label') !== pLabel) phonePill.setAttribute('aria-label', pLabel);
    if (phonePill.title !== pLabel) phonePill.title = pLabel;
  }

  $('status-wifi').classList.toggle('on', Boolean(st.wifi));
  const bt = $('status-bt');
  bt.classList.toggle('on', Boolean(st.bluetooth));
  const btLabel = st.bluetooth && st.btName ? `Bluetooth: ${st.btName}` : 'Bluetooth';
  if (bt.getAttribute('aria-label') !== btLabel) bt.setAttribute('aria-label', btLabel);

  const obd = String(st.obd || 'DISCONNECTED').toUpperCase();
  const pill = $('status-obd');
  pill.classList.toggle('on', obd === 'CONNECTED' || obd === 'SIMULATED');
  pill.classList.toggle('connecting', obd === 'CONNECTING');
  setHidden($('status-obd-tag'), obd !== 'SIMULATED');
}

function monitorLevel(kind, value) {
  if (!Number.isFinite(value) || value <= 0) return '';
  if (kind === 'voltage') return value < 12 || value > 15 ? 'crit' : value < 12.4 || value > 14.8 ? 'warn' : '';
  if (kind === 'coolant') return value > 100 ? 'crit' : value > 95 ? 'warn' : '';
  if (kind === 'load') return value > 85 ? 'crit' : value > 75 ? 'warn' : '';
  return '';
}

// Cor progressiva: 0 = cor de destaque, 0.5 = amarelo, 1 = vermelho vivo
function mixHex(a, b, t) {
  const pa = parseInt(a.slice(1), 16);
  const pb = parseInt(b.slice(1), 16);
  const ch = (s) => Math.round(((pa >> s) & 255) + (((pb >> s) & 255) - ((pa >> s) & 255)) * t);
  return `#${((1 << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)).toString(16).slice(1)}`;
}

function severityColor(t) {
  const base = accent || '#22D3EE';
  if (t <= 0) return '';
  if (t < 0.5) return mixHex(base, '#FACC15', t / 0.5);
  return mixHex('#FACC15', '#FF1F3D', Math.min(1, (t - 0.5) / 0.5));
}

// Quão longe do normal está o valor (0 normal … 1 crítico)
function severity(kind, v) {
  if (!Number.isFinite(v) || v <= 0) return 0;
  if (kind === 'voltage') {
    if (v < 12.6) return clamp01((12.6 - v) / (12.6 - 11.6));
    if (v > 14.8) return clamp01((v - 14.8) / (15.6 - 14.8));
    return 0;
  }
  if (kind === 'coolant') return clamp01((v - 95) / (108 - 95));
  return 0;
}

function clamp01(x) {
  return Math.max(0, Math.min(1, x));
}

function renderMonitor(telemetry, obdStatus) {
  const offline = String(obdStatus || '').toUpperCase() === 'DISCONNECTED';
  const items = {
    voltage: { v: Number(telemetry.voltage), fmt: (v) => `${v.toFixed(1)}V`, empty: '--.-V' },
    coolant: { v: Number(telemetry.coolantC), fmt: (v) => `${Math.round(v)}°C`, empty: '--°C' },
    load: { v: Number(telemetry.loadPct), fmt: (v) => `${Math.round(v)}%`, empty: '--%' }
  };
  for (const [kind, cfg] of Object.entries(items)) {
    const has = !offline && Number.isFinite(cfg.v) && cfg.v > 0;
    const text = has ? cfg.fmt(cfg.v) : cfg.empty;
    const level = has ? monitorLevel(kind, cfg.v) : '';
    document.querySelectorAll(`[data-mon-val="${kind}"]`).forEach((node) => {
      setText(node, text);
      const cell = node.closest('.monitor-cell, .vital-duo-cell');
      if (cell) {
        cell.classList.toggle('warn', level === 'warn');
        cell.classList.toggle('crit', level === 'crit');
        // Bateria e motor mudam de cor aos poucos até o vermelho vivo
        if (cell.classList.contains('vital-duo-cell')) {
          const t = has ? severity(kind, cfg.v) : 0;
          const col = severityColor(t);
          cell.style.color = col;
          node.style.color = col;
          cell.style.textShadow = t >= 0.85 ? `0 0 12px ${col}` : '';
        }
      }
    });
  }
}

function surfaceAlerts(alerts) {
  const current = new Set((alerts || []).map(String));
  for (const a of current) {
    if (!lastAlerts.has(a)) showToast(a, 'error');
  }
  lastAlerts = current;
}

// ---------------------------------------------------------------------------
// Callbacks da ponte
// ---------------------------------------------------------------------------

function onState(next) {
  const firstState = state === null;
  state = next;

  if (next.accent !== accent) applyAccent(next.accent);

  const epoch = Number(next.clock?.epochMs || 0);
  clockOffsetMs = epoch > 1_000_000_000_000 ? epoch - Date.now() : 0;
  renderClock();

  updateBrandAndIntroMode(next.brand, next.settings.introMode);
  renderWeather(next.weather);
  renderStatus(next.status);
  renderMonitor(next.telemetry, next.status.obd);
  updateGaugeTargets(next.telemetry);
  setBackgroundSpeed(next.telemetry.speedKmh);
  surfaceAlerts(next.telemetry.alerts);

  setPhoneIconsVersion(next.phone?.iconsVersion || 0);
  hydrateStaticAppIcons(next.phone?.roles || {}, next.phone?.iconsVersion || 0);

  updateMapState(next.map, nowMs(), {
    mapTheme: next.settings?.mapTheme || 'AUTO'
  });
  updateMusic(next);
  updateDock(next);
  updateCopilot(next);
  updateNotification(next.notification, next.phone);
  updateCallBanner(next.phone, nowMs());
  updateVoiceNotePill(next.phone);
  updateSettings(next);
  updateCalendarCard(next, nowMs());
  updateWeatherCard(next.weather);
  updateVehicle(next);

  if (firstState) document.documentElement.dataset.ready = '1';
}

function onTelemetry(t) {
  updateGaugeTargets(t);
  if (t.speedKmh !== undefined) setBackgroundSpeed(t.speedKmh);
  if (t.copilotLevel !== undefined) updateCopilotLevel(t.copilotLevel);
}

function onEvent(ev) {
  switch (ev.name) {
    case 'toast':
      showToast(ev.text);
      break;
    case 'error':
      showToast(ev.text || 'Ocorreu um erro', 'error');
      break;
    case 'agentReply':
      onAgentReply(ev.text);
      break;
    case 'routeArrived':
      showToast(ev.text || 'Você chegou ao destino');
      break;
    case 'openMode':
      if (MODES.includes(ev.mode)) switchMode(ev.mode);
      break;
    default:
      break;
  }
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

async function boot() {
  await setupBridge({ onState, onTelemetry, onEvent });
  applyAccent('#22D3EE');
  initBackground();
  initGauge();
  initIntro();
  initMap({
    switchMode: (m) => switchMode(m),
    openFavoriteSettings: () => {
      switchMode('settings');
      showSettingsTab('favorites');
    }
  });
  initMusic({ switchMode: (m) => switchMode(m) });
  initDock({ switchMode: (m) => switchMode(m) });
  initCopilot();
  initSettings({ onBack: () => switchMode('home'), onAccentPick: pickAccent });
  initCalendarCard();
  initWeatherCard();
  initVehicle({ switchMode: (m) => switchMode(m) });

  $('clock-date')?.addEventListener('click', () => {
    closeWeatherCard();
    openCalendarCard();
  });
  $('weather-box')?.addEventListener('click', () => {
    closeCalendarCard();
    openWeatherCard();
  });
  $('status-phone')?.addEventListener('click', () => {
    closeCalendarCard();
    closeWeatherCard();
    switchMode('settings');
    showSettingsTab('phone');
  });

  switchMode('home', { silent: true, immediate: true });
  renderClock();
  setInterval(renderClock, 1000);

  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      closeCalendarCard();
      closeWeatherCard();
      if (mode !== 'home') switchMode('home');
    }
  });

  signalReady();
}

window.addEventListener('error', (e) => console.error('[HondaDeck] erro não tratado:', e.message));
window.addEventListener('unhandledrejection', (e) => console.error('[HondaDeck] promessa rejeitada:', e.reason));

boot().catch((err) => console.error('[HondaDeck] falha ao iniciar:', err));

window.__hdDebug = { switchMode, getState: () => state, focusFullMusicSearch };
