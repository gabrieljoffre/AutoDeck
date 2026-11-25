// Mapa Leaflet real (camadas /tiles + /labels no app; Esri Dark Gray no mock)
// Compartilha uma única instância Leaflet entre os slots Home, Mapa 2/3 e Mini-mapa (modo Música)

import { sendCommand, isMockBackend } from './bridge.js';
import {
  el,
  debounce,
  formatDistanceM,
  formatKm,
  formatEta,
  haversineKm,
  createManeuverSvg,
  createFavIconSvg,
  createPinSvg,
  createClockSvg
} from './util.js';

const ESRI_BASE = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}';
const ESRI_REF = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}';

let mapInstance = null;
let carMarker = null;
let carRotatorEl = null;
let routeGlowLine = null;
let routeMainLine = null;
let destCircle = null;

let currentMode = 'home';
let followCar = true;
let lastMapState = null;
let lastAccent = '#22D3EE';
let lastClockMs = Date.now();
let polySignature = '';
let favsSignature = '';
let recentsSignature = '';

let onModeSwitch = null;
let onOpenFavoriteSettings = null;

function buildCarIcon() {
  const html = `
    <div class="hd-car-rotator">
      <svg width="52" height="52" viewBox="0 0 52 52" fill="none" aria-hidden="true">
        <circle cx="26" cy="26" r="22" fill="var(--a20)"></circle>
        <path d="M26 10 L38 38 L26 31.5 L14 38 Z" fill="var(--accent)" stroke="#06090F" stroke-width="2" stroke-linejoin="round"></path>
      </svg>
    </div>
  `;
  return window.L.divIcon({
    className: 'hd-car-marker',
    html,
    iconSize: [52, 52],
    iconAnchor: [26, 26]
  });
}

function setMapInteractive(interactive) {
  if (!mapInstance) return;
  const action = interactive ? 'enable' : 'disable';
  mapInstance.dragging[action]();
  mapInstance.touchZoom[action]();
  mapInstance.doubleClickZoom[action]();
  mapInstance.scrollWheelZoom[action]();
  mapInstance.boxZoom[action]();
  mapInstance.keyboard[action]();
}

function updateRecenterUi() {
  const btn = document.getElementById('map-recenter-btn');
  if (btn) btn.classList.toggle('active', followCar);
}

function attachStageToSlot(mode) {
  const stage = document.getElementById('leaflet-stage');
  let targetSlot = null;
  if (mode === 'home') targetSlot = document.getElementById('home-map-slot');
  else if (mode === 'map') targetSlot = document.getElementById('full-map-slot');
  else if (mode === 'music') targetSlot = document.getElementById('mini-map-slot');
  else targetSlot = document.getElementById('leaflet-holding');

  if (targetSlot && stage && stage.parentElement !== targetSlot) {
    targetSlot.appendChild(stage);
  }
  setMapInteractive(mode === 'map');
  if (mode !== 'map') {
    followCar = true;
    updateRecenterUi();
  }
  if (mapInstance) {
    requestAnimationFrame(() => {
      mapInstance.invalidateSize({ animate: false });
      if (lastMapState && followCar) {
        const z = mode === 'map' ? Math.max(mapInstance.getZoom(), 15) : 15;
        mapInstance.setView([lastMapState.lat, lastMapState.lon], z, { animate: false });
      }
    });
    setTimeout(() => {
      if (mapInstance) mapInstance.invalidateSize({ animate: false });
    }, 370);
  }
}

const debouncedSearch = debounce((q) => {
  sendCommand('map.search', { query: q });
}, 600);

function startRoute(name, lat, lon) {
  const args = { name: String(name || 'Destino') };
  if (Number.isFinite(Number(lat)) && Number.isFinite(Number(lon)) && (Number(lat) !== 0 || Number(lon) !== 0)) {
    args.lat = Number(lat);
    args.lon = Number(lon);
  }
  sendCommand('map.route', args);
}

function renderHomeDropdown() {
  const input = document.getElementById('home-map-input');
  const clearBtn = document.getElementById('home-map-clear');
  const dropdown = document.getElementById('home-map-dropdown');
  const q = (input.value || '').trim();
  clearBtn.classList.toggle('hidden', q.length === 0);

  if (!q) {
    dropdown.classList.add('hidden');
    dropdown.replaceChildren();
    return;
  }

  dropdown.replaceChildren();
  const ql = q.toLowerCase();
  const mp = lastMapState || { favorites: [], search: { results: [] }, lat: 0, lon: 0 };

  // 1) Favoritos correspondentes primeiro
  const favHits = (mp.favorites || []).filter(
    (f) => String(f.name || '').toLowerCase().includes(ql) || String(f.address || '').toLowerCase().includes(ql)
  );
  for (const fav of favHits.slice(0, 3)) {
    const distKm = haversineKm(mp.lat, mp.lon, fav.lat, fav.lon);
    const btn = el(
      'button',
      {
        className: 'dropdown-item',
        type: 'button',
        onClick: () => {
          input.value = '';
          renderHomeDropdown();
          startRoute(fav.name, fav.lat, fav.lon);
        }
      },
      createFavIconSvg(fav.icon, 16, 'var(--accent)'),
      el(
        'span',
        { className: 'dropdown-item-texts' },
        el('span', { className: 'dropdown-item-title' }, fav.name),
        fav.address ? el('span', { className: 'dropdown-item-sub' }, fav.address) : null
      ),
      el('span', { className: 'dropdown-item-meta' }, distKm !== null ? formatKm(distKm) : 'FAV')
    );
    dropdown.appendChild(btn);
  }

  // 2) Resultados de map.search.results
  const results = mp.search?.results || [];
  for (const r of results.slice(0, 4)) {
    const dist = r.distanceKm != null
      ? formatKm(r.distanceKm)
      : (() => {
          const d = haversineKm(mp.lat, mp.lon, r.lat, r.lon);
          return d !== null ? formatKm(d) : '';
        })();
    const btn = el(
      'button',
      {
        className: 'dropdown-item',
        type: 'button',
        onClick: () => {
          input.value = '';
          renderHomeDropdown();
          startRoute(r.name, r.lat, r.lon);
        }
      },
      createPinSvg(16, 'var(--accent)'),
      el(
        'span',
        { className: 'dropdown-item-texts' },
        el('span', { className: 'dropdown-item-title' }, r.name),
        r.address ? el('span', { className: 'dropdown-item-sub' }, r.address) : null
      ),
      el('span', { className: 'dropdown-item-meta' }, dist)
    );
    dropdown.appendChild(btn);
  }

  // 3) Fallback: Buscar "x" no mapa -> map.route
  dropdown.appendChild(
    el(
      'button',
      {
        className: 'dropdown-item',
        type: 'button',
        onClick: () => {
          const targetQ = q;
          input.value = '';
          renderHomeDropdown();
          startRoute(targetQ);
        }
      },
      createPinSvg(16, 'var(--accent)'),
      el('span', { className: 'dropdown-item-texts' }, el('span', { className: 'dropdown-item-title' }, `Buscar “${q}” no mapa`))
    )
  );

  dropdown.classList.remove('hidden');
}

function renderFullSearchResults() {
  const input = document.getElementById('full-map-input');
  const clearBtn = document.getElementById('full-map-clear');
  const defaultBox = document.getElementById('map-drawer-default');
  const resultsBox = document.getElementById('map-drawer-results');
  const listEl = document.getElementById('map-search-results-list');

  const q = (input.value || '').trim();
  clearBtn.classList.toggle('hidden', q.length === 0);

  if (!q) {
    defaultBox.classList.remove('hidden');
    resultsBox.classList.add('hidden');
    return;
  }

  defaultBox.classList.add('hidden');
  resultsBox.classList.remove('hidden');
  listEl.replaceChildren();

  const ql = q.toLowerCase();
  const mp = lastMapState || { favorites: [], search: { results: [], loading: false }, lat: 0, lon: 0 };

  // Favoritos filtrados
  const favHits = (mp.favorites || []).filter(
    (f) => String(f.name || '').toLowerCase().includes(ql) || String(f.address || '').toLowerCase().includes(ql)
  );
  for (const fav of favHits) {
    const distKm = haversineKm(mp.lat, mp.lon, fav.lat, fav.lon);
    listEl.appendChild(
      el(
        'button',
        {
          className: 'place-row-btn',
          type: 'button',
          onClick: () => {
            input.value = '';
            renderFullSearchResults();
            startRoute(fav.name, fav.lat, fav.lon);
          }
        },
        createFavIconSvg(fav.icon, 18, 'var(--accent)'),
        el(
          'span',
          { className: 'place-row-texts' },
          el('span', { className: 'place-row-name' }, fav.name),
          fav.address ? el('span', { className: 'place-row-addr' }, fav.address) : null
        ),
        el('span', { className: 'place-row-dist' }, distKm !== null ? formatKm(distKm) : 'FAV')
      )
    );
  }

  if (mp.search?.loading) {
    listEl.appendChild(el('div', { className: 'settings-sub', style: 'padding: 12px 4px' }, 'Buscando locais próximos…'));
  }

  for (const r of mp.search?.results || []) {
    const dist = r.distanceKm != null
      ? formatKm(r.distanceKm)
      : (() => {
          const d = haversineKm(mp.lat, mp.lon, r.lat, r.lon);
          return d !== null ? formatKm(d) : '';
        })();
    listEl.appendChild(
      el(
        'button',
        {
          className: 'place-row-btn',
          type: 'button',
          onClick: () => {
            input.value = '';
            renderFullSearchResults();
            startRoute(r.name, r.lat, r.lon);
          }
        },
        createPinSvg(18, 'var(--accent)'),
        el(
          'span',
          { className: 'place-row-texts' },
          el('span', { className: 'place-row-name' }, r.name),
          r.address ? el('span', { className: 'place-row-addr' }, r.address) : null
        ),
        el('span', { className: 'place-row-dist' }, dist)
      )
    );
  }

  // Ação direta para rotear pelo texto digitado
  listEl.appendChild(
    el(
      'button',
      {
        className: 'place-row-btn',
        type: 'button',
        onClick: () => {
          const destName = q;
          input.value = '';
          renderFullSearchResults();
          startRoute(destName);
        }
      },
      createPinSvg(18, 'var(--accent)'),
      el(
        'span',
        { className: 'place-row-texts' },
        el('span', { className: 'place-row-name' }, `Buscar “${q}” no mapa`),
        el('span', { className: 'place-row-addr' }, 'Traçar rota para este endereço')
      )
    )
  );
}

function renderDrawerLists(mp) {
  const favGrid = document.getElementById('map-favs-grid');
  const recList = document.getElementById('map-recents-list');

  const nextFavSig = JSON.stringify(mp.favorites || []);
  if (nextFavSig !== favsSignature) {
    favsSignature = nextFavSig;
    favGrid.replaceChildren();
    for (const fav of (mp.favorites || []).slice(0, 5)) {
      favGrid.appendChild(
        el(
          'button',
          {
            className: 'fav-tile-btn',
            type: 'button',
            onClick: () => startRoute(fav.name, fav.lat, fav.lon)
          },
          createFavIconSvg(fav.icon, 20, 'var(--accent)'),
          el('span', null, fav.name || 'Favorito')
        )
      );
    }
    favGrid.appendChild(
      el(
        'button',
        {
          className: 'fav-tile-btn add',
          type: 'button',
          onClick: () => {
            if (onOpenFavoriteSettings) onOpenFavoriteSettings();
          }
        },
        el('span', { style: 'font-size:20px;line-height:1' }, '+'),
        el('span', null, 'Adicionar')
      )
    );
  }

  const nextRecSig = JSON.stringify({ r: mp.recentPlaces || [], lat: +(mp.lat || 0).toFixed(2), lon: +(mp.lon || 0).toFixed(2) });
  if (nextRecSig !== recentsSignature) {
    recentsSignature = nextRecSig;
    recList.replaceChildren();
    const recents = mp.recentPlaces || [];
    if (!recents.length) {
      recList.appendChild(el('span', { className: 'settings-sub', style: 'padding: 10px 4px' }, 'Nenhuma pesquisa recente.'));
    } else {
      for (const p of recents.slice(0, 6)) {
        const dKm = haversineKm(mp.lat, mp.lon, p.lat, p.lon);
        recList.appendChild(
          el(
            'button',
            {
              className: 'place-row-btn',
              type: 'button',
              onClick: () => startRoute(p.name, p.lat, p.lon)
            },
            createClockSvg(18, '#8A97A8'),
            el(
              'span',
              { className: 'place-row-texts' },
              el('span', { className: 'place-row-name' }, p.name),
              p.address ? el('span', { className: 'place-row-addr' }, p.address) : null
            ),
            el('span', { className: 'place-row-dist' }, dKm !== null ? formatKm(dKm) : '')
          )
        );
      }
    }
  }
}

function renderRouteHud(mp) {
  const rt = mp.route || {};
  const homeBadge = document.getElementById('home-maneuver-box');
  const homeDist = document.getElementById('home-route-dist');
  const homeSub = document.getElementById('home-route-sub');

  const fullHud = document.getElementById('map-route-hud');
  const fullIcon = document.getElementById('full-maneuver-icon');
  const fullDist = document.getElementById('full-route-dist');
  const fullSub = document.getElementById('full-route-sub');

  const miniDist = document.getElementById('mini-map-dist');
  const miniInstr = document.getElementById('mini-map-instr');

  if (rt.loading) {
    homeBadge.replaceChildren(createManeuverSvg('STRAIGHT', 22));
    homeDist.textContent = 'Calculando…';
    homeSub.textContent = rt.destination ? `Rota para ${rt.destination}` : 'Obtendo melhor trajeto';
    fullHud.classList.remove('hidden');
    fullIcon.replaceChildren(createManeuverSvg('STRAIGHT', 30));
    fullDist.textContent = 'Calculando…';
    fullSub.textContent = homeSub.textContent;
    miniDist.textContent = 'Rota';
    miniInstr.textContent = 'calculando…';
    return;
  }

  if (rt.active) {
    const nextDistStr = formatDistanceM(rt.distanceToNextM);
    const remDistStr = formatDistanceM(rt.remainingM);
    const etaStr = formatEta(rt.etaEpochMs, rt.remainingS, lastClockMs);
    const instr = rt.nextInstruction || (rt.destination ? `Rota para ${rt.destination}` : 'Siga em frente');

    homeBadge.replaceChildren(createManeuverSvg(rt.nextIcon, 22));
    homeDist.textContent = rt.arrived ? 'Chegou!' : nextDistStr;
    homeSub.textContent = etaStr ? `${instr} · chegada ${etaStr}` : instr;

    fullHud.classList.remove('hidden');
    fullIcon.replaceChildren(createManeuverSvg(rt.nextIcon, 30));
    fullDist.textContent = rt.arrived ? 'Destino alcançado' : nextDistStr;
    const summaryParts = [];
    if (instr) summaryParts.push(instr);
    if (etaStr) summaryParts.push(`Chegada ${etaStr}`);
    if (rt.remainingM > 0) summaryParts.push(remDistStr);
    fullSub.textContent = summaryParts.join(' · ');

    miniDist.textContent = nextDistStr;
    miniInstr.textContent = instr;
  } else {
    homeBadge.replaceChildren(createManeuverSvg('SEARCH', 20));
    homeDist.textContent = 'Sem rota';
    homeSub.textContent = rt.error ? String(rt.error) : 'Sem rota · toque para buscar';

    fullHud.classList.add('hidden');
    miniDist.textContent = 'Mapa';
    miniInstr.textContent = 'Sem rota · toque para abrir';
  }
}

export function initMap({ switchMode, openFavoriteSettings }) {
  onModeSwitch = switchMode;
  onOpenFavoriteSettings = openFavoriteSettings;

  const stage = document.getElementById('leaflet-stage');
  if (window.L && stage) {
    const mock = isMockBackend();
    const baseUrl = mock ? ESRI_BASE : '/tiles/{z}/{y}/{x}.png';
    const labelsUrl = mock ? ESRI_REF : '/labels/{z}/{y}/{x}.png';

    mapInstance = window.L.map(stage, {
      center: [-16.3281, -48.9534],
      zoom: 15,
      maxZoom: 16,
      minZoom: 4,
      zoomControl: false,
      attributionControl: false
    });

    window.L.tileLayer(baseUrl, {
      maxZoom: 16,
      className: 'hd-base-tiles'
    }).addTo(mapInstance);

    const labelsPane = mapInstance.createPane('labelsPane');
    labelsPane.style.zIndex = '350';
    labelsPane.style.pointerEvents = 'none';

    window.L.tileLayer(labelsUrl, {
      maxZoom: 16,
      pane: 'labelsPane',
      className: 'hd-label-tiles'
    }).addTo(mapInstance);

    routeGlowLine = window.L.polyline([], {
      color: lastAccent,
      weight: 14,
      opacity: 0.35,
      lineCap: 'round',
      lineJoin: 'round',
      interactive: false
    }).addTo(mapInstance);

    routeMainLine = window.L.polyline([], {
      color: lastAccent,
      weight: 5,
      opacity: 1,
      lineCap: 'round',
      lineJoin: 'round',
      interactive: false
    }).addTo(mapInstance);

    carMarker = window.L.marker([-16.3281, -48.9534], {
      icon: buildCarIcon(),
      interactive: false,
      keyboard: false
    }).addTo(mapInstance);

    mapInstance.on('dragstart', () => {
      if (currentMode === 'map') {
        followCar = false;
        updateRecenterUi();
      }
    });

    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(() => {
        if (mapInstance) mapInstance.invalidateSize({ pan: false, animate: false });
      });
      ro.observe(stage);
    }
  }

  // Eventos de UI do Mapa
  document.getElementById('home-map-tap').addEventListener('click', () => onModeSwitch('map'));
  document.getElementById('home-map-expand-btn').addEventListener('click', () => onModeSwitch('map'));
  document.getElementById('home-map-footer').addEventListener('click', () => {
    onModeSwitch('map');
    setTimeout(() => document.getElementById('full-map-input')?.focus(), 120);
  });
  document.getElementById('mini-map-tap').addEventListener('click', () => onModeSwitch('map'));
  document.getElementById('map-close-btn').addEventListener('click', () => onModeSwitch('home'));

  const homeInput = document.getElementById('home-map-input');
  homeInput.addEventListener('input', () => {
    const q = homeInput.value.trim();
    renderHomeDropdown();
    if (q) debouncedSearch(q);
  });
  homeInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && homeInput.value.trim()) {
      const q = homeInput.value.trim();
      homeInput.value = '';
      renderHomeDropdown();
      startRoute(q);
    }
  });
  document.getElementById('home-map-clear').addEventListener('click', () => {
    homeInput.value = '';
    renderHomeDropdown();
  });

  const fullInput = document.getElementById('full-map-input');
  fullInput.addEventListener('input', () => {
    const q = fullInput.value.trim();
    renderFullSearchResults();
    if (q) debouncedSearch(q);
  });
  fullInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && fullInput.value.trim()) {
      const q = fullInput.value.trim();
      fullInput.value = '';
      renderFullSearchResults();
      startRoute(q);
    }
  });
  document.getElementById('full-map-clear').addEventListener('click', () => {
    fullInput.value = '';
    renderFullSearchResults();
  });

  // Fecha dropdown do cartão ao clicar fora
  document.addEventListener('pointerdown', (e) => {
    const wrap = document.querySelector('.map-home-searchbar');
    if (wrap && !wrap.contains(e.target)) {
      document.getElementById('home-map-dropdown')?.classList.add('hidden');
    }
  });

  document.getElementById('map-recenter-btn').addEventListener('click', () => {
    followCar = true;
    updateRecenterUi();
    if (mapInstance && lastMapState) {
      mapInstance.setView([lastMapState.lat, lastMapState.lon], 16, { animate: true });
    }
  });
  document.getElementById('map-zoom-in-btn').addEventListener('click', () => {
    if (mapInstance) mapInstance.zoomIn();
  });
  document.getElementById('map-zoom-out-btn').addEventListener('click', () => {
    if (mapInstance) mapInstance.zoomOut();
  });

  document.getElementById('map-stop-route-btn').addEventListener('click', () => {
    sendCommand('map.stopRoute');
  });
  document.getElementById('map-external-btn').addEventListener('click', () => {
    const dest = lastMapState?.route?.destination || lastMapState?.route?.nextInstruction || 'Destino';
    sendCommand('map.openExternal', { name: dest });
  });

  attachStageToSlot('home');
}

export function setMapMode(mode) {
  currentMode = mode;
  attachStageToSlot(mode);
}

export function setMapAccent(hex) {
  lastAccent = hex;
  if (routeGlowLine) routeGlowLine.setStyle({ color: hex });
  if (routeMainLine) routeMainLine.setStyle({ color: hex });
  if (destCircle) destCircle.setStyle({ color: hex });
}

export function updateMapState(mapState, clockEpochMs) {
  if (!mapState) return;
  lastMapState = mapState;
  if (clockEpochMs > 1_000_000_000_000) lastClockMs = clockEpochMs;

  const lat = Number(mapState.lat || -16.3281);
  const lon = Number(mapState.lon || -48.9534);
  const bearing = Number(mapState.bearing || 0);

  if (mapInstance && carMarker) {
    carMarker.setLatLng([lat, lon]);
    if (!carRotatorEl) {
      carRotatorEl = carMarker.getElement()?.querySelector('.hd-car-rotator') || null;
    }
    if (carRotatorEl) {
      carRotatorEl.style.transform = `rotate(${bearing.toFixed(1)}deg)`;
    }
    if (followCar) {
      mapInstance.panTo([lat, lon], { animate: false });
    }

    // Polyline da rota
    const rt = mapState.route || {};
    const pts = rt.active && Array.isArray(rt.polyline) ? rt.polyline : [];
    const nextPolySig = pts.length >= 2 ? JSON.stringify(pts) : '';
    if (nextPolySig !== polySignature) {
      polySignature = nextPolySig;
      routeGlowLine.setLatLngs(pts);
      routeMainLine.setLatLngs(pts);
      if (destCircle) {
        mapInstance.removeLayer(destCircle);
        destCircle = null;
      }
      if (pts.length >= 2) {
        const lastPt = pts[pts.length - 1];
        destCircle = window.L.circleMarker(lastPt, {
          radius: 7,
          fillColor: '#0A1019',
          fillOpacity: 1,
          color: lastAccent,
          weight: 3,
          interactive: false
        }).addTo(mapInstance);
      }
    }
  }

  renderRouteHud(mapState);
  renderDrawerLists(mapState);
  if (document.getElementById('home-map-input')?.value.trim()) {
    renderHomeDropdown();
  }
  if (document.getElementById('full-map-input')?.value.trim()) {
    renderFullSearchResults();
  }
}
