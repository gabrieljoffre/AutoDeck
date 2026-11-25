// Painel do veículo (OBD-II) em 3/4 da tela, aberto ao tocar no velocímetro.
// Velocidade, rotação e marcha vêm do gauge.js (atributos data-compact-*);
// aqui ficam combustível, leituras ao vivo (PIDs), consumo/autonomia e falhas (DTC).

import { sendCommand } from './bridge.js';
import { el } from './util.js';

const $ = (id) => document.getElementById(id);
let lastPidsSig = '';
let lastDtcSig = '';

// Nível de alerta por leitura conhecida (0 normal … 1 crítico)
function pidSeverity(label, value) {
  const v = Number(String(value).replace(',', '.'));
  if (!Number.isFinite(v)) return 0;
  const l = String(label || '').toLowerCase();
  if (l.includes('arrefec')) return clamp01((v - 95) / 13);
  if (l.includes('bateria') || l.includes('tens')) return v < 12.6 ? clamp01((12.6 - v) / 1) : v > 14.8 ? clamp01((v - 14.8) / 0.8) : 0;
  if (l.includes('carga')) return clamp01((v - 75) / 20);
  if (l.includes('admiss')) return clamp01((v - 55) / 20);
  return 0;
}

function clamp01(x) {
  return Math.max(0, Math.min(1, x));
}

function fmt(n, digits = 1) {
  return Number(n).toFixed(digits).replace('.', ',');
}

export function initVehicle({ switchMode }) {
  $('gauge-open-btn')?.addEventListener('click', () => switchMode('vehicle'));
  $('vehicle-close-btn')?.addEventListener('click', () => switchMode('home'));
  $('vehicle-map-tap')?.addEventListener('click', () => switchMode('map'));
  $('vehicle-dtc-read')?.addEventListener('click', () => sendCommand('obd.readDtc'));
}

export function updateVehicle(state) {
  if (!state) return;
  const vh = state.vehicle || {};
  const t = state.telemetry || {};
  const obd = String(state.status?.obd || 'DISCONNECTED').toUpperCase();

  const msg = $('vehicle-obd-msg');
  if (msg) msg.textContent = vh.obdMessage || (obd === 'DISCONNECTED' ? 'Adaptador OBD-II desconectado' : '');

  // Combustível
  const pct = Number(t.fuelPct);
  const hasFuel = Number.isFinite(pct) && pct > 0;
  $('vehicle-fuel-pct').textContent = hasFuel ? `${Math.round(pct)}%` : '--%';
  const liters = Number(vh.tankLiters);
  $('vehicle-fuel-liters').textContent = liters > 0 ? ` · ${fmt(liters)} L` : '';
  const bar = $('vehicle-fuel-bar');
  if (bar) {
    bar.style.width = hasFuel ? `${Math.min(100, pct)}%` : '0%';
    bar.style.background = pct <= 15 ? '#F43F5E' : pct <= 35 ? '#FACC15' : '#22C55E';
  }

  // Consumo e autonomia
  const sum = vh.fuelLog?.summary || {};
  const avg = Number(sum.avgKmL);
  const last = Number(sum.lastKmL);
  $('vehicle-avg-kml').textContent = avg > 0 ? fmt(avg) : '--';
  $('vehicle-last-kml').textContent = last > 0 ? fmt(last) : '--';
  $('vehicle-range-km').textContent = avg > 0 && liters > 0 ? String(Math.round(avg * liters)) : '--';

  // Leituras ao vivo
  const pids = Array.isArray(vh.pids) ? vh.pids : [];
  const sig = JSON.stringify(pids);
  if (sig !== lastPidsSig) {
    lastPidsSig = sig;
    const grid = $('vehicle-pids-grid');
    grid.replaceChildren();
    if (!pids.length) {
      grid.appendChild(el('span', { className: 'vehicle-empty' }, 'Sem leituras. Conecte o adaptador OBD-II ou ligue o simulador em Ajustes › Veículo.'));
    }
    for (const p of pids) {
      const sev = pidSeverity(p.label, p.value);
      grid.appendChild(
        el(
          'div',
          { className: `vehicle-pid-card${sev >= 0.85 ? ' crit' : sev > 0 ? ' warn' : ''}` },
          el('span', { className: 'vp-lbl' }, p.label || ''),
          el('span', { className: 'vp-val' }, String(p.value ?? '--'), p.unit ? el('small', {}, ` ${p.unit}`) : null)
        )
      );
    }
  }

  // Falhas (DTC)
  const codes = Array.isArray(vh.dtcCodes) ? vh.dtcCodes : [];
  const dsig = `${JSON.stringify(codes)}|${vh.dtcBusy}`;
  if (dsig !== lastDtcSig) {
    lastDtcSig = dsig;
    const list = $('vehicle-dtc-list');
    list.replaceChildren();
    if (!codes.length) {
      list.appendChild(el('span', { className: 'vehicle-dtc-ok' }, vh.dtcBusy ? 'Lendo a ECU…' : 'Nenhuma falha registrada'));
    }
    for (const d of codes) {
      list.appendChild(el('div', { className: 'vehicle-dtc-row' }, el('span', { className: 'vd-code' }, d.code || ''), el('span', { className: 'vd-desc' }, d.desc || '')));
    }
  }
}
