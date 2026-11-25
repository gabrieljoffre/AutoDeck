// Velocímetro Órbita de dois anéis com ECG dinâmico, marchas N-1-2-3-4-5 e combustível
// Matemática fiel ao Component.renderVals() de Main.dc.html, interpolando por frame sem recriar o SVG.

import { clamp, hexA, el } from './util.js';

const GEARS_ORDER = ['N', '1', '2', '3', '4', '5'];
const ZONE_COLORS = ['#22C55E', '#FACC15', '#F43F5E'];
const WAVE_DURS = ['2.2s', '1.4s', '0.8s'];
const Z1 = 23.4375;
const Z2 = 51.5625;

let refs = null;
let gearNodes = new Map();
let compactRefs = {
  speeds: [],
  gears: [],
  rpmBars: [],
  rpmTexts: []
};

let target = {
  speed: 0,
  rpmK: 0,
  gear: 'N',
  fuelPct: 66
};

let cur = {
  speed: 0,
  rpmK: 0,
  fuelPct: 66
};

let rendered = {
  speedInt: -1,
  speedDash: '',
  rpmQuant: -1,
  zone: -1,
  gear: '',
  fuelQuant: -1
};

let rafId = null;
let lastTs = 0;

function pol(t, r, size = 440) {
  const a = ((135 + 270 * t) * Math.PI) / 180;
  const k = size / 440;
  return {
    x: ((220 + r * Math.cos(a)) * k).toFixed(1) + 'px',
    y: ((220 + r * Math.sin(a)) * k).toFixed(1) + 'px'
  };
}

function normalizeRpmToThousands(rawRpm) {
  const n = Math.max(0, Number(rawRpm || 0));
  // Suporta tanto RPM inteiro OBD-II (0..8000) quanto já em milhar (0.0..8.0)
  const k = n > 20 ? n / 1000 : n;
  return clamp(k, 0, 8);
}

function computeEcgPath(rpmK) {
  const amp = 1 + rpmK * 0.9;
  const steps = Math.max(2, Math.round(((270 * rpmK) / 8) * 2));
  let ecg = '';
  for (let i = 0; i <= steps; i++) {
    const u = (i % 10) / 10;
    const v = u < 0.3 ? 0 : u < 0.4 ? -0.35 : u < 0.5 ? 1 : u < 0.6 ? -0.6 : u < 0.7 ? 0.25 : 0;
    const a = ((135 + i * 0.5) * Math.PI) / 180;
    const r = 222 + amp * v;
    ecg += (i ? 'L' : 'M') + (220 + r * Math.cos(a)).toFixed(1) + ' ' + (220 + r * Math.sin(a)).toFixed(1);
  }
  return ecg;
}

function applyFrame() {
  if (!refs) return;

  const speed = clamp(cur.speed, 0, 220);
  const rpm = clamp(cur.rpmK, 0, 8);
  const fuel = clamp(cur.fuelPct, 0, 100);

  // Velocidade
  const speedInt = Math.round(speed);
  const speedDash = ((75 * speed) / 220).toFixed(2) + ' 100';
  if (speedDash !== rendered.speedDash) {
    rendered.speedDash = speedDash;
    refs.speedGlow.setAttribute('stroke-dasharray', speedDash);
    refs.speedArc.setAttribute('stroke-dasharray', speedDash);
  }
  if (speedInt !== rendered.speedInt) {
    rendered.speedInt = speedInt;
    const spStr = String(speedInt);
    refs.speedText.textContent = spStr;
    for (const node of compactRefs.speeds) node.textContent = spStr;
  }

  // Conta-giros + ECG + Zona
  const rpmQuant = Math.round(rpm * 40) / 40; // resolução de 25 RPM
  if (rpmQuant !== rendered.rpmQuant) {
    rendered.rpmQuant = rpmQuant;
    const T = (75 * rpm) / 8;
    const z1Dash = clamp(T, 0, Z1).toFixed(2) + ' 100';
    const z2Dash = `0 ${Z1} ${clamp(T - Z1, 0, Z2 - Z1).toFixed(2)} 100`;
    const z3Dash = `0 ${Z2} ${clamp(T - Z2, 0, 75 - Z2).toFixed(2)} 100`;
    const rpmDash = T.toFixed(2) + ' 100';

    refs.z1.setAttribute('stroke-dasharray', z1Dash);
    refs.z2.setAttribute('stroke-dasharray', z2Dash);
    refs.z3.setAttribute('stroke-dasharray', z3Dash);
    refs.rpmGlow.setAttribute('stroke-dasharray', rpmDash);

    const ecg = computeEcgPath(rpm);
    refs.ecgGlow.setAttribute('d', ecg);
    refs.ecgBase.setAttribute('d', ecg);
    refs.ecgPulse.setAttribute('d', ecg);

    const rpmLabel = `${rpm.toFixed(1)} ×1000 RPM`;
    refs.rpmText.textContent = rpmLabel;
    const rpmPct = `${((100 * rpm) / 8).toFixed(1)}%`;
    for (const bar of compactRefs.rpmBars) bar.style.width = rpmPct;
    for (const txt of compactRefs.rpmTexts) txt.textContent = rpmLabel;
  }

  const zone = rpm >= 5.5 ? 2 : rpm >= 2.5 ? 1 : 0;
  if (zone !== rendered.zone) {
    rendered.zone = zone;
    const zoneColor = ZONE_COLORS[zone];
    const zoneGlow = hexA(zoneColor, 0.28);
    refs.ecgGlow.setAttribute('stroke', zoneGlow);
    refs.ecgBase.setAttribute('stroke', zoneColor);
    refs.ecgPulse.setAttribute('stroke', zoneColor);
    refs.rpmGlow.setAttribute('stroke', zoneGlow);
    refs.ecgAnim.setAttribute('dur', WAVE_DURS[zone]);
    refs.rpmText.style.color = zoneColor;
  }

  // Marchas
  const g = String(target.gear || 'N').toUpperCase();
  if (g !== rendered.gear) {
    rendered.gear = g;
    for (const [key, node] of gearNodes.entries()) {
      node.classList.toggle('active', key === g);
    }
    for (const node of compactRefs.gears) node.textContent = g;
  }

  // Combustível
  const fuelQuant = Math.round(fuel * 2) / 2;
  if (fuelQuant !== rendered.fuelQuant) {
    rendered.fuelQuant = fuelQuant;
    const pctStr = `${fuelQuant}%`;
    refs.fuelMask.style.left = pctStr;
    refs.fuelThumb.style.left = pctStr;
  }
}

function tick(ts) {
  if (document.hidden) {
    rafId = null;
    return;
  }
  const dt = lastTs ? Math.min(0.08, (ts - lastTs) / 1000) : 1 / 60;
  lastTs = ts;

  const k = Math.min(1, dt * 11);
  cur.speed += (target.speed - cur.speed) * k;
  cur.rpmK += (target.rpmK - cur.rpmK) * k;
  cur.fuelPct += (target.fuelPct - cur.fuelPct) * Math.min(1, dt * 6);

  if (Math.abs(target.speed - cur.speed) < 0.04) cur.speed = target.speed;
  if (Math.abs(target.rpmK - cur.rpmK) < 0.006) cur.rpmK = target.rpmK;
  if (Math.abs(target.fuelPct - cur.fuelPct) < 0.08) cur.fuelPct = target.fuelPct;

  applyFrame();

  const settled =
    cur.speed === target.speed &&
    cur.rpmK === target.rpmK &&
    cur.fuelPct === target.fuelPct &&
    rendered.gear === String(target.gear || 'N').toUpperCase();

  if (settled) {
    rafId = null;
    lastTs = 0;
  } else {
    rafId = requestAnimationFrame(tick);
  }
}

function ensureAnimating() {
  if (rafId === null && !document.hidden) {
    lastTs = 0;
    rafId = requestAnimationFrame(tick);
  }
}

export function initGauge() {
  refs = {
    ecgGlow: document.getElementById('ecg-glow'),
    ecgBase: document.getElementById('ecg-base'),
    ecgPulse: document.getElementById('ecg-pulse'),
    ecgAnim: document.getElementById('ecg-anim'),
    rpmGlow: document.getElementById('gauge-rpm-glow'),
    z1: document.getElementById('gauge-z1'),
    z2: document.getElementById('gauge-z2'),
    z3: document.getElementById('gauge-z3'),
    minorTicks: document.getElementById('gauge-minor-ticks'),
    majorTicks: document.getElementById('gauge-major-ticks'),
    speedGlow: document.getElementById('gauge-speed-glow'),
    speedArc: document.getElementById('gauge-speed-arc'),
    speedLabels: document.getElementById('gauge-speed-labels'),
    rpmLabels: document.getElementById('gauge-rpm-labels'),
    speedText: document.getElementById('gauge-speed-text'),
    rpmText: document.getElementById('gauge-rpm-text'),
    gearsWrap: document.getElementById('gauge-gears'),
    fuelMask: document.getElementById('fuel-mask'),
    fuelThumb: document.getElementById('fuel-thumb')
  };

  // Ticks estáticos idênticos a Main.dc.html
  const minor = [];
  for (let i = 0; i < 16; i++) minor.push('0.7 15.8');
  minor.push('0.7 200');
  refs.minorTicks.setAttribute('stroke-dasharray', minor.join(' '));

  const major = [];
  for (let i = 0; i < 8; i++) major.push('1.2 31.8');
  major.push('1.2 200');
  refs.majorTicks.setAttribute('stroke-dasharray', major.join(' '));

  // Rótulos 0..220 km/h (raio 112)
  refs.speedLabels.replaceChildren();
  for (let v = 0; v <= 220; v += 20) {
    const p = pol(v / 220, 112, 440);
    const lbl = el('div', { className: 'gauge-speed-lbl' }, String(v));
    lbl.style.left = p.x;
    lbl.style.top = p.y;
    refs.speedLabels.appendChild(lbl);
  }

  // Rótulos 0..8 ×1000 RPM (raio 162)
  refs.rpmLabels.replaceChildren();
  for (let v = 0; v <= 8; v++) {
    const p = pol(v / 8, 162, 440);
    const color = v >= 6 ? '#FB7185' : v >= 3 ? '#FACC15' : '#4ADE80';
    const lbl = el('div', { className: 'gauge-rpm-lbl' }, String(v));
    lbl.style.left = p.x;
    lbl.style.top = p.y;
    lbl.style.color = color;
    refs.rpmLabels.appendChild(lbl);
  }

  // Marchas N 1 2 3 4 5
  refs.gearsWrap.replaceChildren();
  gearNodes.clear();
  for (const g of GEARS_ORDER) {
    const sp = el('span', { className: 'gear-item', 'data-test': `gear-${g}` }, g);
    refs.gearsWrap.appendChild(sp);
    gearNodes.set(g, sp);
  }

  compactRefs = {
    speeds: Array.from(document.querySelectorAll('[data-compact-speed]')),
    gears: Array.from(document.querySelectorAll('[data-compact-gear]')),
    rpmBars: Array.from(document.querySelectorAll('[data-compact-rpm-bar]')),
    rpmTexts: Array.from(document.querySelectorAll('[data-compact-rpm-text]'))
  };

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) ensureAnimating();
  });

  applyFrame();
}

export function updateGaugeTargets(telemetry) {
  if (!telemetry) return;
  if (telemetry.speedKmh !== undefined) {
    target.speed = clamp(telemetry.speedKmh, 0, 220);
  }
  if (telemetry.rpm !== undefined) {
    target.rpmK = normalizeRpmToThousands(telemetry.rpm);
  }
  if (telemetry.gear !== undefined && telemetry.gear !== null && telemetry.gear !== '') {
    target.gear = String(telemetry.gear).toUpperCase();
  }
  if (telemetry.fuelPct !== undefined) {
    target.fuelPct = clamp(telemetry.fuelPct, 0, 100);
  }
  ensureAnimating();
}
