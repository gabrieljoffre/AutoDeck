// Utilitários compartilhados do painel Órbita (escape seguro via DOM, cores, formatação e ícones)

const DIAS_PT = ['DOM', 'SEG', 'TER', 'QUA', 'QUI', 'SEX', 'SÁB'];
const MESES_PT = ['JAN', 'FEV', 'MAR', 'ABR', 'MAI', 'JUN', 'JUL', 'AGO', 'SET', 'OUT', 'NOV', 'DEZ'];

export function clamp(v, min, max) {
  const n = Number(v);
  if (Number.isNaN(n)) return min;
  return Math.max(min, Math.min(max, n));
}

export function hexToRgb(hex) {
  const clean = String(hex || '#22D3EE').trim().replace('#', '');
  const full = clean.length === 3
    ? clean.split('').map((c) => c + c).join('')
    : clean.padEnd(6, '0').slice(0, 6);
  const num = parseInt(full, 16);
  if (Number.isNaN(num)) return { r: 34, g: 211, b: 238 };
  return {
    r: (num >> 16) & 255,
    g: (num >> 8) & 255,
    b: num & 255
  };
}

export function hexA(hex, alpha) {
  const { r, g, b } = hexToRgb(hex);
  return `rgba(${r},${g},${b},${alpha})`;
}

export function applyAccentCssVars(accentHex) {
  const hex = /^#[0-9a-fA-F]{3,6}$/.test(String(accentHex || '').trim())
    ? String(accentHex).trim()
    : '#22D3EE';
  const { r, g, b } = hexToRgb(hex);
  const root = document.documentElement;
  root.style.setProperty('--accent', hex);
  root.style.setProperty('--accent-rgb', `${r}, ${g}, ${b}`);
  root.style.setProperty('--a10', `rgba(${r}, ${g}, ${b}, 0.10)`);
  root.style.setProperty('--a15', `rgba(${r}, ${g}, ${b}, 0.15)`);
  root.style.setProperty('--a20', `rgba(${r}, ${g}, ${b}, 0.22)`);
  root.style.setProperty('--a35', `rgba(${r}, ${g}, ${b}, 0.38)`);
  root.style.setProperty('--a50', `rgba(${r}, ${g}, ${b}, 0.50)`);
  return hex;
}

export function formatDurationMs(ms) {
  const totalSec = Math.max(0, Math.floor(Number(ms || 0) / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${String(sec).padStart(2, '0')}`;
}

export function formatDistanceM(meters) {
  const m = Number(meters);
  if (!Number.isFinite(m) || m < 0) return '--';
  if (m < 1000) return `${Math.round(m / 10) * 10 || Math.round(m)} m`;
  const km = m / 1000;
  return km >= 10
    ? `${Math.round(km)} km`
    : `${km.toFixed(1).replace('.', ',')} km`;
}

export function formatKm(km) {
  const n = Number(km);
  if (!Number.isFinite(n) || n < 0) return '';
  if (n < 1) return `${Math.max(50, Math.round(n * 1000))} m`;
  return n >= 10 ? `${Math.round(n)} km` : `${n.toFixed(1).replace('.', ',')} km`;
}

export function formatClockAndDate(epochMs) {
  const d = new Date(epochMs && epochMs > 1_000_000_000_000 ? epochMs : Date.now());
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const dia = DIAS_PT[d.getDay()] || 'HOJ';
  const num = String(d.getDate()).padStart(2, '0');
  const mes = MESES_PT[d.getMonth()] || '';
  return { time: `${hh}:${mm}`, date: `${dia}, ${num} ${mes}` };
}

export function formatEta(etaEpochMs, remainingS, baseNowMs) {
  let target = Number(etaEpochMs || 0);
  if (!(target > 1_000_000_000_000) && Number(remainingS) > 0) {
    target = (baseNowMs || Date.now()) + Number(remainingS) * 1000;
  }
  if (!(target > 1_000_000_000_000)) return '';
  const d = new Date(target);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function haversineKm(lat1, lon1, lat2, lon2) {
  if (![lat1, lon1, lat2, lon2].every((v) => Number.isFinite(Number(v)) && Number(v) !== 0)) {
    return null;
  }
  const toRad = (deg) => (Number(deg) * Math.PI) / 180;
  const R = 6371;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

export function debounce(fn, waitMs) {
  let t = null;
  const wrapped = (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), waitMs);
  };
  wrapped.cancel = () => clearTimeout(t);
  return wrapped;
}

export function throttle(fn, waitMs) {
  let last = 0;
  let timer = null;
  let pendingArgs = null;
  return (...args) => {
    const now = performance.now();
    const rem = waitMs - (now - last);
    if (rem <= 0) {
      clearTimeout(timer);
      timer = null;
      last = now;
      fn(...args);
    } else {
      pendingArgs = args;
      if (!timer) {
        timer = setTimeout(() => {
          last = performance.now();
          timer = null;
          if (pendingArgs) fn(...pendingArgs);
        }, rem);
      }
    }
  };
}

/** Construtor seguro de elementos HTML (texto sempre via textContent) */
export function el(tag, attrs = null, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'className') node.className = v;
      else if (k === 'dataset' && typeof v === 'object') {
        for (const [dk, dv] of Object.entries(v)) node.dataset[dk] = String(dv);
      } else if (k.startsWith('on') && typeof v === 'function') {
        node.addEventListener(k.slice(2).toLowerCase(), v);
      } else if (v === true) {
        node.setAttribute(k, '');
      } else {
        node.setAttribute(k, String(v));
      }
    }
  }
  for (const ch of children.flat()) {
    if (ch === null || ch === undefined || ch === false) continue;
    if (ch instanceof Node) node.appendChild(ch);
    else node.appendChild(document.createTextNode(String(ch)));
  }
  return node;
}

const SVG_NS = 'http://www.w3.org/2000/svg';

function makeSvg(size, stroke, strokeWidth = 2, paths = []) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', stroke);
  svg.setAttribute('stroke-width', String(strokeWidth));
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  for (const spec of paths) {
    const child = document.createElementNS(SVG_NS, spec.tag || 'path');
    for (const [ak, av] of Object.entries(spec)) {
      if (ak !== 'tag') child.setAttribute(ak, String(av));
    }
    svg.appendChild(child);
  }
  return svg;
}

export function createWeatherSvg(kind) {
  const k = String(kind || 'PARTLY_CLOUDY').toUpperCase();
  if (k.includes('CLEAR') || k.includes('SUN')) {
    return makeSvg(26, '#FACC15', 1.8, [
      { tag: 'circle', cx: 12, cy: 12, r: 4.5 },
      { d: 'M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M18.7 5.3l-1.6 1.6M6.9 17.1l-1.6 1.6' }
    ]);
  }
  if (k.includes('STORM') || k.includes('THUNDER')) {
    return makeSvg(26, '#AEB9C7', 1.7, [
      { d: 'M7 17h10a3.5 3.5 0 0 0 0-7 5 5 0 0 0-9.6 1.5A2.8 2.8 0 0 0 7 17z', fill: '#06090F' },
      { d: 'M13 13l-2.5 4.5h3L11 22', stroke: '#FACC15', 'stroke-width': 2 }
    ]);
  }
  if (k.includes('RAIN') || k.includes('DRIZZLE') || k.includes('SHOWER')) {
    return makeSvg(26, '#AEB9C7', 1.7, [
      { d: 'M7 16h10a3.5 3.5 0 0 0 0-7 5 5 0 0 0-9.6 1.5A2.8 2.8 0 0 0 7 16z', fill: '#06090F' },
      { d: 'M8.5 19l-1 2.5M12.5 19l-1 2.5M16.5 19l-1 2.5', stroke: '#38BDF8' }
    ]);
  }
  if (k.includes('CLOUD') && !k.includes('PART')) {
    return makeSvg(26, '#AEB9C7', 1.7, [
      { d: 'M7 19h10a3.8 3.8 0 0 0 0-7.6 5.4 5.4 0 0 0-10.3 1.6A3 3 0 0 0 7 19z', fill: '#06090F' }
    ]);
  }
  // Padrão: Parcialmente nublado (idêntico ao Main.dc.html)
  return makeSvg(26, '#AEB9C7', 1.7, [
    { tag: 'circle', cx: 9, cy: 9, r: 3.5, stroke: '#FACC15' },
    { d: 'M9 2.5v1.2M3.7 4.4l.8.8M2.5 9h1.2M14.3 4.4l-.8.8', stroke: '#FACC15' },
    { d: 'M8 20h9a3.5 3.5 0 0 0 0-7 5 5 0 0 0-9.6 1.5A2.8 2.8 0 0 0 8 20z', stroke: '#AEB9C7', fill: '#06090F' }
  ]);
}

export function createManeuverSvg(nextIcon, size = 22) {
  const k = String(nextIcon || 'STRAIGHT').toUpperCase();
  switch (k) {
    case 'LEFT':
      return makeSvg(size, 'currentColor', 2.2, [{ d: 'M16 20V10a4 4 0 0 0-4-4H4M8 2L4 6l4 4' }]);
    case 'RIGHT':
      return makeSvg(size, 'currentColor', 2.2, [{ d: 'M8 20V10a4 4 0 0 1 4-4h8M16 2l4 4-4 4' }]);
    case 'SLIGHT_LEFT':
      return makeSvg(size, 'currentColor', 2.2, [{ d: 'M14 20v-7l-7-7M7 12V6h6' }]);
    case 'SLIGHT_RIGHT':
      return makeSvg(size, 'currentColor', 2.2, [{ d: 'M10 20v-7l7-7M17 12V6h-6' }]);
    case 'UTURN':
      return makeSvg(size, 'currentColor', 2.2, [{ d: 'M9 20V9a4 4 0 0 1 8 0v8M14 14l3 3 3-3' }]);
    case 'ROUNDABOUT':
      return makeSvg(size, 'currentColor', 2.2, [
        { tag: 'circle', cx: 12, cy: 11, r: 4 },
        { d: 'M12 21v-6M12 7V3M9 5l3-2 3 2' }
      ]);
    case 'ARRIVE':
      return makeSvg(size, 'currentColor', 2.2, [
        { d: 'M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z' },
        { tag: 'circle', cx: 12, cy: 10, r: 2.5 }
      ]);
    case 'SEARCH':
      return makeSvg(size, 'currentColor', 2.2, [
        { tag: 'circle', cx: 11, cy: 11, r: 7 },
        { d: 'M20 20l-4-4' }
      ]);
    default: // STRAIGHT
      return makeSvg(size, 'currentColor', 2.2, [{ d: 'M12 20V4M7 9l5-5 5 5' }]);
  }
}

export function createFavIconSvg(icon, size = 20, stroke = 'var(--accent)') {
  const k = String(icon || 'star').toLowerCase();
  if (k === 'home') {
    return makeSvg(size, stroke, 2, [{ d: 'M3 11l9-7 9 7M5 10v10h14V10' }]);
  }
  if (k === 'work') {
    return makeSvg(size, stroke, 2, [
      { tag: 'rect', x: 3, y: 7, width: 18, height: 13, rx: 2 },
      { d: 'M9 7V4h6v3' }
    ]);
  }
  if (k === 'fuel') {
    return makeSvg(size, stroke, 2, [
      { d: 'M4 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16M3 21h12M14 8l3 2v8a1.5 1.5 0 0 0 3 0V9l-3-3' }
    ]);
  }
  return makeSvg(size, stroke, 2, [
    { d: 'M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z' }
  ]);
}

export function createPinSvg(size = 16, stroke = 'var(--accent)') {
  return makeSvg(size, stroke, 2, [
    { d: 'M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z' },
    { tag: 'circle', cx: 12, cy: 10, r: 2.5 }
  ]);
}

export function createClockSvg(size = 16, stroke = '#8A97A8') {
  return makeSvg(size, stroke, 2, [
    { tag: 'circle', cx: 12, cy: 12, r: 9 },
    { d: 'M12 7v5l3 2' }
  ]);
}

export function createNoteSvg(size = 18, stroke = 'var(--accent)') {
  return makeSvg(size, stroke, 1.8, [
    { d: 'M9 18V5l12-2v13' },
    { tag: 'circle', cx: 6, cy: 18, r: 3 },
    { tag: 'circle', cx: 18, cy: 16, r: 3 }
  ]);
}

/**
 * Emblema automotivo Honda fiel à geometria real (moldura trapezoidal arredondada
 * mais larga no topo + "H" com abas superiores alargadas e hastes afiladas).
 */
export function createHondaEmblemSvg(width = 46, height = 38, color = 'currentColor') {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('width', String(width));
  svg.setAttribute('height', String(height));
  svg.setAttribute('viewBox', '0 0 64 52');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('aria-hidden', 'true');

  // Moldura externa trapezoidal levemente abaulada
  const frame = document.createElementNS(SVG_NS, 'path');
  frame.setAttribute(
    'd',
    'M 13.5 4.8 C 25.5 3.3, 38.5 3.3, 50.5 4.8 C 57.2 5.6, 60.2 8.8, 59.3 15.8 L 56.8 36.8 C 55.8 43.6, 52.8 46.8, 46.2 47.5 C 36.2 48.6, 27.8 48.6, 17.8 47.5 C 11.2 46.8, 8.2 43.6, 7.2 36.8 L 4.7 15.8 C 3.8 8.8, 6.8 5.6, 13.5 4.8 Z'
  );
  frame.setAttribute('fill', 'rgba(255, 255, 255, 0.04)');
  frame.setAttribute('stroke', color);
  frame.setAttribute('stroke-width', '2.7');
  frame.setAttribute('stroke-linejoin', 'round');

  // Contorno fiel do "H" Honda
  const hMark = document.createElementNS(SVG_NS, 'path');
  hMark.setAttribute(
    'd',
    'M 13.8 10.6 L 23.0 10.6 C 23.9 18.0, 24.5 22.8, 25.8 24.1 C 27.4 25.0, 36.6 25.0, 38.2 24.1 C 39.5 22.8, 40.1 18.0, 41.0 10.6 L 50.2 10.6 C 49.4 21.8, 48.3 32.8, 46.8 41.4 L 40.6 41.4 C 39.9 35.8, 39.4 31.8, 38.4 30.8 C 36.6 29.8, 27.4 29.8, 25.6 30.8 C 24.6 31.8, 24.1 35.8, 23.4 41.4 L 17.2 41.4 C 15.7 32.8, 14.6 21.8, 13.8 10.6 Z'
  );
  hMark.setAttribute('fill', color);

  svg.append(frame, hMark);
  return svg;
}

// ---------------------------------------------------------------------------
// Ícones reais dos apps instalados no tablet (/appicon/) e celular (/phoneicon/)
// ---------------------------------------------------------------------------

let phoneIconsVersion = 0;
let lastStaticHydrateSig = '';

export function setPhoneIconsVersion(v) {
  const n = Number(v || 0);
  if (Number.isFinite(n) && n >= 0) phoneIconsVersion = n;
}

export function getPhoneIconsVersion() {
  return phoneIconsVersion;
}

export function buildAppIconUrl(source, packageName) {
  const pkg = String(packageName || '').trim();
  if (!pkg) return '';
  if (typeof window !== 'undefined' && typeof window.__hdMockIconUrl === 'function') {
    return window.__hdMockIconUrl(source, pkg);
  }
  const safe = encodeURIComponent(pkg);
  if (source === 'phone') {
    const v = phoneIconsVersion > 0 ? `?v=${phoneIconsVersion}` : '';
    return `/phoneicon/${safe}.png${v}`;
  }
  return `/appicon/${safe}.png`;
}

/**
 * Monta uma lista de URLs candidatas a partir de entradas `{ source: 'tablet'|'phone', pkg }`.
 */
export function resolveIconUrls(candidates = []) {
  const urls = [];
  const seen = new Set();
  for (const c of candidates) {
    if (!c || !c.pkg) continue;
    const url = buildAppIconUrl(c.source || 'tablet', c.pkg);
    if (url && !seen.has(url)) {
      seen.add(url);
      urls.push(url);
    }
  }
  return urls;
}

/**
 * Cria um elemento de ícone real de app com fallback discreto se o pacote não existir (404).
 */
export function createAppIconEl({
  candidates = [],
  size = 20,
  radius = 5,
  alt = '',
  className = '',
  fallback = null,
  fallbackText = ''
} = {}) {
  const wrap = el('span', {
    className: `app-icon-slot${className ? ' ' + className : ''}`,
    style: `width:${size}px;height:${size}px;border-radius:${radius}px`
  });

  const fbNode = fallback instanceof Node
    ? fallback
    : el('span', { className: 'app-icon-fallback-badge', 'aria-hidden': 'true' },
      String(fallbackText || alt || '•').trim().charAt(0).toUpperCase() || '•'
    );
  wrap.appendChild(fbNode);

  const urls = resolveIconUrls(candidates);
  if (!urls.length) return wrap;

  const img = el('img', {
    className: 'app-icon-img hidden',
    alt: alt || '',
    decoding: 'async'
  });
  let idx = 0;
  img.addEventListener('load', () => {
    img.classList.remove('hidden');
    fbNode.classList.add('hidden');
  });
  img.addEventListener('error', () => {
    idx += 1;
    if (idx < urls.length) {
      img.src = urls[idx];
    } else {
      img.classList.add('hidden');
      fbNode.classList.remove('hidden');
    }
  });
  img.src = urls[0];
  wrap.appendChild(img);
  return wrap;
}

/** Retorna a lista de candidatos para chaves conhecidas de apps (tablet + fallback celular). */
export function appCandidatesForKey(key, phoneRoles = {}) {
  switch (key) {
    case 'spotify':
      return [
        { source: 'tablet', pkg: 'com.spotify.music' },
        { source: 'phone', pkg: 'com.spotify.music' }
      ];
    case 'maps':
      return [
        { source: 'tablet', pkg: 'com.google.android.apps.maps' },
        { source: 'phone', pkg: 'com.google.android.apps.maps' }
      ];
    case 'waze':
      return [
        { source: 'tablet', pkg: 'com.waze' },
        { source: 'phone', pkg: 'com.waze' }
      ];
    case 'gemini':
      return [
        { source: 'tablet', pkg: 'com.google.android.apps.bard' },
        { source: 'phone', pkg: 'com.google.android.apps.bard' }
      ];
    case 'calendar':
      return [
        { source: 'tablet', pkg: 'com.google.android.calendar' },
        { source: 'tablet', pkg: 'com.samsung.android.calendar' },
        ...(phoneRoles.calendar ? [{ source: 'phone', pkg: phoneRoles.calendar }] : []),
        { source: 'phone', pkg: 'com.samsung.android.calendar' },
        { source: 'phone', pkg: 'com.google.android.calendar' }
      ];
    case 'whatsapp':
      return [
        ...(phoneRoles.whatsapp ? [{ source: 'phone', pkg: phoneRoles.whatsapp }] : []),
        { source: 'phone', pkg: 'com.whatsapp' },
        { source: 'phone', pkg: 'com.whatsapp.w4b' },
        { source: 'tablet', pkg: 'com.whatsapp' }
      ];
    case 'dialer':
      return [
        ...(phoneRoles.dialer ? [{ source: 'phone', pkg: phoneRoles.dialer }] : []),
        { source: 'phone', pkg: 'com.samsung.android.dialer' },
        { source: 'phone', pkg: 'com.google.android.dialer' },
        { source: 'phone', pkg: 'com.android.dialer' }
      ];
    case 'sms':
      return [
        ...(phoneRoles.sms ? [{ source: 'phone', pkg: phoneRoles.sms }] : []),
        { source: 'phone', pkg: 'com.samsung.android.messaging' },
        { source: 'phone', pkg: 'com.google.android.apps.messaging' }
      ];
    case 'ponte':
      return [
        ...(phoneRoles.ponte ? [{ source: 'phone', pkg: phoneRoles.ponte }] : []),
        { source: 'phone', pkg: 'com.autodeck.hondadeck.ponte' }
      ];
    default:
      return [
        { source: 'tablet', pkg: key },
        { source: 'phone', pkg: key }
      ];
  }
}

/**
 * Hidrata elementos estáticos do HTML com `[data-app-icon]` preservando o filho original como fallback.
 */
export function hydrateStaticAppIcons(phoneRoles = {}, iconsVer = 0) {
  setPhoneIconsVersion(iconsVer);
  const sig = `${iconsVer}|${jsonSig(phoneRoles)}`;
  if (sig === lastStaticHydrateSig) return;
  lastStaticHydrateSig = sig;

  document.querySelectorAll('[data-app-icon]').forEach((slot) => {
    const key = slot.dataset.appIcon;
    if (!key) return;
    const urls = resolveIconUrls(appCandidatesForKey(key, phoneRoles));
    if (!urls.length) return;

    let img = slot.querySelector(':scope > img.app-icon-img');
    const fallbackEl = Array.from(slot.children).find((ch) => ch !== img) || null;
    if (!img) {
      img = el('img', { className: 'app-icon-img hidden', alt: '', decoding: 'async' });
      slot.appendChild(img);
    }

    const urlsSig = urls.join('|');
    if (img.dataset.urlsSig === urlsSig && !img.classList.contains('hidden')) return;
    img.dataset.urlsSig = urlsSig;

    let idx = 0;
    img.onload = () => {
      img.classList.remove('hidden');
      if (fallbackEl) fallbackEl.classList.add('hidden');
    };
    img.onerror = () => {
      idx += 1;
      if (idx < urls.length) {
        img.src = urls[idx];
      } else {
        img.classList.add('hidden');
        if (fallbackEl) fallbackEl.classList.remove('hidden');
      }
    };
    img.src = urls[0];
  });
}


// ---------------------------------------------------------------------------
// Helpers de render incremental (só tocam o DOM quando o valor mudou)
// ---------------------------------------------------------------------------

export function setText(node, text) {
  if (!node) return;
  const next = String(text ?? '');
  if (node.textContent !== next) node.textContent = next;
}

export function setHidden(node, hidden) {
  if (!node) return;
  node.classList.toggle('hidden', Boolean(hidden));
}

/** Atualiza <img> + ícone de fallback conforme a URL (sem recarregar se não mudou). */
export function setCover(img, fallback, url) {
  if (!img) return;
  const next = String(url || '');
  if (!next) {
    img.removeAttribute('src');
    img.dataset.src = '';
    img.classList.add('hidden');
    if (fallback) fallback.classList.remove('hidden');
    return;
  }
  if (img.dataset.src !== next) {
    img.dataset.src = next;
    img.onerror = () => {
      img.classList.add('hidden');
      if (fallback) fallback.classList.remove('hidden');
    };
    img.onload = () => {
      img.classList.remove('hidden');
      if (fallback) fallback.classList.add('hidden');
    };
    img.src = next;
  }
}

export function setPlayIcon(svg, playing) {
  if (!svg) return;
  const path = svg?.querySelector('path');
  if (!path) return;
  path.setAttribute('d', playing ? 'M7 5h4v14H7zM13 5h4v14h-4z' : 'M8 5v14l11-7z');
}

export function jsonSig(value) {
  try {
    return JSON.stringify(value);
  } catch {
    return String(Math.random());
  }
}

/** Número em pt-BR (vírgula decimal). */
export function fmtNum(value, digits = 1) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '--';
  return n.toFixed(digits).replace('.', ',');
}

/** Lê número digitado (aceita vírgula). Retorna NaN se vazio/inválido. */
export function parseNum(text) {
  const t = String(text ?? '').trim().replace(/\./g, '').replace(',', '.');
  if (!t) return NaN;
  const n = Number(t);
  return Number.isFinite(n) ? n : NaN;
}

/** Aceita "1234,56" ou "1234.56" (preço/litros digitados sem separador de milhar). */
export function parseDecimal(text) {
  const t = String(text ?? '').trim().replace(',', '.');
  if (!t) return NaN;
  const n = Number(t);
  return Number.isFinite(n) ? n : NaN;
}
