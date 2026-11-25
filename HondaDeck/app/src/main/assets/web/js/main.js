// Ponto de entrada do painel Órbita: liga ponte, modos, topo, monitoramento e módulos

import { setupBridge, signalReady, sendCommand } from './bridge.js';
import {
  applyAccentCssVars,
  formatClockAndDate,
  createWeatherSvg,
  setText,
  setHidden
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

const MODES = ['home', 'map', 'mapFull', 'music', 'settings'];
const VIEW_IDS = ['home', 'map', 'music', 'settings'];

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

function switchMode(next, { silent = false } = {}) {
  if (!MODES.includes(next)) return;
  const changed = next !== mode;
  mode = next;

  const activeView = mode === 'mapFull' ? 'map' : mode;
  for (const m of VIEW_IDS) setHidden($(`view-${m}`), m !== activeView);
  $('app-shell').classList.toggle('mode-settings-active', mode === 'settings');
  $('app-shell').classList.toggle('mode-map-full', mode === 'mapFull');
  $('view-map')?.classList.toggle('map-full-active', mode === 'mapFull');
  setDockActiveMode(activeView);
  setMapMode(mode);
  setBackgroundMode(activeView);
  setSettingsVisible(mode === 'settings');

  if (changed && !silent) sendCommand('ui.mode', { mode });
}

function applyAccent(color) {
  const hex = applyAccentCssVars(color);
  accent = hex;
  setBackgroundAccent(hex);
  setMapAccent(hex);
}

function pickAccent(color) {
  applyAccent(color); // otimista: muda tudo ao vivo; o backend confirma no próximo estado
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
      const cell = node.closest('.monitor-cell');
      if (cell) {
        cell.classList.toggle('warn', level === 'warn');
        cell.classList.toggle('crit', level === 'crit');
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

  updateMapState(next.map, nowMs(), {
    mapTheme: next.settings?.mapTheme || 'AUTO'
  });
  updateMusic(next);
  updateDock(next);
  updateCopilot(next);
  updateNotification(next.notification);
  updateCallBanner(next.phone, nowMs());
  updateVoiceNotePill(next.phone);
  updateSettings(next);
  updateCalendarCard(next, nowMs());
  updateWeatherCard(next.weather);

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
  // A ponte (e o mock, se não houver HondaBridge) precisa existir antes do mapa escolher as camadas de tiles
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

  switchMode('home', { silent: true });
  renderClock();
  setInterval(renderClock, 1000);

  // Foco no campo ao abrir o modo correspondente via teclado/ação direta
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

// Reexporta para depuração no console do WebView (não faz parte do contrato)
window.__hdDebug = { switchMode, getState: () => state, focusFullMusicSearch };
