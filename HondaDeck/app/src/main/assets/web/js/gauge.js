// Velocímetro Honda estilo Ferrari:
// - Arco esquerdo: Conta-giros 0–8 ×1000 RPM com zonas (verde/âmbar/vermelho) e ponteiro radial
// - Arco direito (oposto ao conta-giros): Barra curva de combustível (E -> F)
// - Centro: Emblema Honda real, velocidade digital grande (KM/H) e marchas N-1-2-3-4-5
// - Interpola por frame via requestAnimationFrame sem recriar o SVG

import { clamp, hexA, el } from './util.js';

const CX = 190;
const CY = 190;
const DIAL_R = 152;
const RPM_A0 = 124; // ~7h08 (0 RPM)
const RPM_A1 = 316; // ~1h32 (8k RPM) — 192° de varredura
const FUEL_E = 70;  // ~5h20 (tanque vazio)
const FUEL_F = -18; // ~2h24 (tanque cheio) — 88° oposto ao conta-giros

const GEARS_ORDER = ['N', '1', '2', '3', '4', '5'];
const ZONE_COLORS = ['#22C55E', '#FACC15', '#F43F5E'];

let refs = null;
const gearNodes = new Map();
let compactRefs = {
  speeds: [],
  gears: [],
  rpmBars: [],
  rpmTexts: []
};

const target = {
  speed: 0,
  rpmK: 0,
  gear: 'N',
  fuelPct: 66
};

const cur = {
  speed: 0,
  rpmK: 0,
  fuelPct: 66
};

const rendered = {
  speedInt: -1,
  rpmQuant: -1,
  zone: -1,
  gear: '',
  fuelQuant: -1
};

let rafId = null;
let lastTs = 0;

function polar(deg, r, cx = CX, cy = CY) {
  const rad = (deg * Math.PI) / 180;
  return {
    x: cx + r * Math.cos(rad),
    y: cy + r * Math.sin(rad)
  };
}

function arcPath(r, a0, a1, sweepCW = 1) {
  const p0 = polar(a0, r);
  const p1 = polar(a1, r);
  const diff = sweepCW ? ((a1 - a0 + 360) % 360) : ((a0 - a1 + 360) % 360);
  const large = diff > 180 ? 1 : 0;
  return `M ${p0.x.toFixed(1)} ${p0.y.toFixed(1)} A ${r} ${r} 0 ${large} ${sweepCW} ${p1.x.toFixed(1)} ${p1.y.toFixed(1)}`;
}

function svgEl(tag, attrs = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) {
    node.setAttribute(k, String(v));
  }
  return node;
}

function normalizeRpmToThousands(rawRpm) {
  const n = Math.max(0, Number(rawRpm || 0));
  const k = n > 20 ? n / 1000 : n;
  return clamp(k, 0, 8);
}

function buildStaticDial() {
  const rpmSpan = RPM_A1 - RPM_A0;
  const aAmber = RPM_A0 + (2.5 / 8) * rpmSpan;
  const aRed = RPM_A0 + (5.5 / 8) * rpmSpan;

  refs.rpmTrack.setAttribute('d', arcPath(DIAL_R, RPM_A0, RPM_A1, 1));
  refs.rpmAmberZone.setAttribute('d', arcPath(DIAL_R, aAmber, aRed, 1));
  refs.rpmRedZone.setAttribute('d', arcPath(DIAL_R, aRed, RPM_A1, 1));
  refs.fuelTrack.setAttribute('d', arcPath(DIAL_R, FUEL_E, FUEL_F, 0));

  // Marcações (ticks) e rótulos 0..8 no arco esquerdo
  refs.rpmTicks.replaceChildren();
  refs.rpmLabels.replaceChildren();
  for (let i = 0; i <= 16; i++) {
    const ang = RPM_A0 + (i / 16) * rpmSpan;
    const major = i % 2 === 0;
    const pOut = polar(ang, 141);
    const pIn = polar(ang, major ? 131 : 135);
    refs.rpmTicks.appendChild(svgEl('line', {
      x1: pIn.x.toFixed(1),
      y1: pIn.y.toFixed(1),
      x2: pOut.x.toFixed(1),
      y2: pOut.y.toFixed(1),
      stroke: i >= 11 ? '#F43F5E' : (major ? 'rgba(232,238,246,0.9)' : 'rgba(232,238,246,0.36)'),
      'stroke-width': major ? 2.2 : 1.2,
      'stroke-linecap': 'round'
    }));

    if (major) {
      const val = i / 2;
      const pLbl = polar(ang, 117);
      const lbl = el('div', { className: 'gauge-rpm-lbl' }, String(val));
      lbl.style.left = `${((pLbl.x / 380) * 100).toFixed(2)}%`;
      lbl.style.top = `${((pLbl.y / 380) * 100).toFixed(2)}%`;
      lbl.style.color = val >= 6 ? '#FB7185' : val >= 3 ? '#FACC15' : '#4ADE80';
      refs.rpmLabels.appendChild(lbl);
    }
  }

  // Marcações e rótulos F / E na barra oposta de combustível
  refs.fuelTicks.replaceChildren();
  for (let i = 0; i <= 8; i++) {
    const ang = FUEL_E - (i / 8) * (FUEL_E - FUEL_F);
    const major = i % 2 === 0;
    const pOut = polar(ang, 141);
    const pIn = polar(ang, major ? 129 : 134);
    refs.fuelTicks.appendChild(svgEl('line', {
      x1: pIn.x.toFixed(1),
      y1: pIn.y.toFixed(1),
      x2: pOut.x.toFixed(1),
      y2: pOut.y.toFixed(1),
      stroke: i <= 1 ? '#F43F5E' : 'rgba(232,238,246,0.62)',
      'stroke-width': major ? 2 : 1.1,
      'stroke-linecap': 'round'
    }));
  }

  refs.fuelLabels.replaceChildren();
  const pF = polar(FUEL_F, 118);
  const pE = polar(FUEL_E, 118);
  const tF = svgEl('text', {
    x: pF.x.toFixed(1),
    y: (pF.y + 4).toFixed(1),
    fill: '#94A3B8',
    'font-size': 12,
    'font-weight': 700,
    'text-anchor': 'middle',
    class: 'gauge-svg-label'
  });
  tF.textContent = 'F';
  const tE = svgEl('text', {
    x: pE.x.toFixed(1),
    y: (pE.y + 4).toFixed(1),
    fill: '#FB7185',
    'font-size': 12,
    'font-weight': 700,
    'text-anchor': 'middle',
    class: 'gauge-svg-label'
  });
  tE.textContent = 'E';
  refs.fuelLabels.append(tF, tE);

  // Miolo limpo: só a velocidade centralizada (sem emblema ao lado)
  if (refs.hondaEmblem) refs.hondaEmblem.replaceChildren();
}

function applyFrame() {
  if (!refs) return;

  const speed = clamp(cur.speed, 0, 220);
  const rpm = clamp(cur.rpmK, 0, 8);
  const fuel = clamp(cur.fuelPct, 0, 100);

  // Velocidade digital
  const speedInt = Math.round(speed);
  if (speedInt !== rendered.speedInt) {
    rendered.speedInt = speedInt;
    const spStr = String(speedInt);
    refs.speedText.textContent = spStr;
    for (const node of compactRefs.speeds) node.textContent = spStr;
  }

  // Conta-giros (arco esquerdo em 3 zonas + ponteiro Ferrari)
  const rpmQuant = Math.round(rpm * 40) / 40;
  if (rpmQuant !== rendered.rpmQuant) {
    rendered.rpmQuant = rpmQuant;
    const rpmSpan = RPM_A1 - RPM_A0;
    const aCur = RPM_A0 + (Math.max(0.04, rpm) / 8) * rpmSpan;
    const aAmber = RPM_A0 + (2.5 / 8) * rpmSpan;
    const aRed = RPM_A0 + (5.5 / 8) * rpmSpan;

    refs.rpmGlow.setAttribute('d', rpm > 0.05 ? arcPath(DIAL_R, RPM_A0, aCur, 1) : '');
    refs.z1.setAttribute('d', rpm > 0.05 ? arcPath(DIAL_R, RPM_A0, Math.min(aCur, aAmber), 1) : '');
    refs.z2.setAttribute('d', aCur > aAmber ? arcPath(DIAL_R, aAmber, Math.min(aCur, aRed), 1) : '');
    refs.z3.setAttribute('d', aCur > aRed ? arcPath(DIAL_R, aRed, aCur, 1) : '');

    // Ponteiro aponta para cima (270°), então rotação = ang + 90
    const needleAng = RPM_A0 + (rpm / 8) * rpmSpan + 90;
    refs.needle.setAttribute('transform', `rotate(${needleAng.toFixed(1)} ${CX} ${CY})`);

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
    refs.rpmGlow.setAttribute('stroke', hexA(zoneColor, 0.28));
    refs.needleHalo.setAttribute('stroke', zoneColor);
    refs.rpmText.style.color = zoneColor;
  }

  // Marchas N 1 2 3 4 5
  const g = String(target.gear || 'N').toUpperCase();
  if (g !== rendered.gear) {
    rendered.gear = g;
    for (const [key, node] of gearNodes.entries()) {
      node.classList.toggle('active', key === g);
    }
    for (const node of compactRefs.gears) node.textContent = g;
  }

  // Combustível (arco oposto direito E -> F)
  const fuelQuant = Math.round(fuel * 2) / 2;
  if (fuelQuant !== rendered.fuelQuant) {
    rendered.fuelQuant = fuelQuant;
    const frac = Math.max(0.02, fuel / 100);
    const aFuel = FUEL_E - frac * (FUEL_E - FUEL_F);
    const dFuel = arcPath(DIAL_R, FUEL_E, aFuel, 0);
    refs.fuelArc.setAttribute('d', dFuel);
    refs.fuelGlow.setAttribute('d', dFuel);
    const low = fuel <= 15;
    refs.fuelGlow.setAttribute('stroke', low ? 'rgba(244,63,94,0.34)' : 'rgba(34,197,94,0.24)');
    if (refs.fuelPctText) refs.fuelPctText.textContent = `${Math.round(fuel)}%`;
    if (refs.fuelBadge) refs.fuelBadge.classList.toggle('low', low);
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
    rpmTrack: document.getElementById('gauge-rpm-track'),
    rpmAmberZone: document.getElementById('gauge-rpm-amberzone'),
    rpmRedZone: document.getElementById('gauge-rpm-redzone'),
    rpmGlow: document.getElementById('gauge-rpm-glow'),
    z1: document.getElementById('gauge-z1'),
    z2: document.getElementById('gauge-z2'),
    z3: document.getElementById('gauge-z3'),
    rpmTicks: document.getElementById('gauge-rpm-ticks'),
    rpmLabels: document.getElementById('gauge-rpm-labels'),
    needle: document.getElementById('gauge-rpm-needle'),
    needleHalo: document.getElementById('gauge-needle-halo'),
    hondaEmblem: document.getElementById('gauge-honda-emblem'),
    fuelTrack: document.getElementById('gauge-fuel-track'),
    fuelGlow: document.getElementById('gauge-fuel-glow'),
    fuelArc: document.getElementById('gauge-fuel-arc'),
    fuelTicks: document.getElementById('gauge-fuel-ticks'),
    fuelLabels: document.getElementById('gauge-fuel-labels'),
    fuelBadge: document.querySelector('.fuel-hit-badge'),
    fuelPctText: document.getElementById('fuel-pct-text'),
    speedText: document.getElementById('gauge-speed-text'),
    rpmText: document.getElementById('gauge-rpm-text'),
    gearsWrap: document.getElementById('gauge-gears')
  };

  buildStaticDial();

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
