// Mapa com suporte a dois provedores:
// 1) Google Maps JavaScript API (quando map.provider === 'google' e map.jsKey estiver configurada):
//    estilos claro/escuro, camada de trânsito, seta do carro, rota com polyline/Directions e
//    busca via Places Autocomplete (New) com viés na posição atual.
// 2) Leaflet + tiles Esri sem chave (padrão atual):
//    claro = World_Street_Map (/tiles/light), escuro = World_Dark_Gray_Base + Reference (/tiles + /labels),
//    com busca inteligente Photon + Nominatim + favoritos/recentes (debounce 300 ms).
// Compartilha o stage entre os slots Home, Mapa 2/3, Mapa Tela Cheia (mapFull) e Mini-mapa (Música).

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
import { buildMapStyle, routeToGeoJSON, destToGeoJSON, webglAvailable } from './map-style.js';

const ESRI_DARK_BASE = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}';
const ESRI_DARK_REF = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}';
const ESRI_LIGHT_STREET = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}';

const ANAPOLIS_DEFAULT = { lat: -16.3281, lon: -48.9534 };

// Estilo noturno inspirado no Google Maps Dark Mode oficial
const GOOGLE_DARK_STYLE = [
  { elementType: 'geometry', stylers: [{ color: '#172130' }] },
  { elementType: 'labels.text.stroke', stylers: [{ color: '#111926' }] },
  { elementType: 'labels.text.fill', stylers: [{ color: '#8fa3bf' }] },
  { featureType: 'administrative.locality', elementType: 'labels.text.fill', stylers: [{ color: '#d5e0f0' }] },
  { featureType: 'poi', elementType: 'labels.text.fill', stylers: [{ color: '#7d93b0' }] },
  { featureType: 'poi.park', elementType: 'geometry', stylers: [{ color: '#162b2b' }] },
  { featureType: 'poi.park', elementType: 'labels.text.fill', stylers: [{ color: '#5fa68c' }] },
  { featureType: 'road', elementType: 'geometry', stylers: [{ color: '#27364b' }] },
  { featureType: 'road', elementType: 'geometry.stroke', stylers: [{ color: '#1b2636' }] },
  { featureType: 'road', elementType: 'labels.text.fill', stylers: [{ color: '#9eb1cc' }] },
  { featureType: 'road.arterial', elementType: 'geometry', stylers: [{ color: '#31445e' }] },
  { featureType: 'road.highway', elementType: 'geometry', stylers: [{ color: '#3c5473' }] },
  { featureType: 'road.highway', elementType: 'geometry.stroke', stylers: [{ color: '#1e2c40' }] },
  { featureType: 'road.highway', elementType: 'labels.text.fill', stylers: [{ color: '#d8e4f5' }] },
  { featureType: 'transit', elementType: 'geometry', stylers: [{ color: '#233245' }] },
  { featureType: 'water', elementType: 'geometry', stylers: [{ color: '#0c1829' }] },
  { featureType: 'water', elementType: 'labels.text.fill', stylers: [{ color: '#53739c' }] }
];

// Estado Leaflet (Esri)
let leafletMap = null;
let darkBaseLayer = null;
let darkLabelsLayer = null;
let lightBaseLayer = null;
let carMarker = null;
let carRotatorEl = null;
let routeGlowLine = null;
let routeMainLine = null;
let destCircle = null;

// Estado MapLibre GL (vetorial OpenFreeMap, padrão quando há WebGL)
let mlMap = null;
let mlCarMarker = null;
let mlRouteData = { type: 'FeatureCollection', features: [] };
let mlDestData = { type: 'FeatureCollection', features: [] };

// Estado Google Maps JS API
let activeProvider = 'esri'; // 'maplibre' | 'esri' | 'google'
let gmapsLoadingKey = '';
let gmapsLoaded = false;
let gmap = null;
let gmapTrafficLayer = null;
let gmapCarMarker = null;
let gmapRouteGlow = null;
let gmapRouteMain = null;
let gmapDestMarker = null;
let gmapDirectionsService = null;
let gmapLastDirectionsSig = '';
let gmapSessionToken = null;
let gmapAutocompleteResults = [];
let gmapAutocompleteQuery = '';
let gmapAutocompleteLoading = false;

// Estado geral
let currentMode = 'home';
let followCar = true;
let lastMapState = null;
let lastAccent = '#22D3EE';
let lastClockMs = Date.now();
let configuredMapTheme = 'AUTO';
let effectiveTheme = null; // 'light' | 'dark' — null força aplicar as camadas na primeira vez
let polySignature = '';
let favsSignature = '';
let recentsSignature = '';
let smoothBearing = 0;
let bearingInitialized = false;

let onModeSwitch = null;
let onOpenFavoriteSettings = null;

function isMapInteractiveMode(mode) {
  return mode === 'map' || mode === 'mapFull';
}

function normalizeStr(s) {
  return String(s || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim();
}

function resolveEffectiveTheme(setting, clockEpochMs) {
  const mode = String(setting || 'AUTO').toUpperCase();
  if (mode === 'LIGHT') return 'light';
  if (mode === 'DARK') return 'dark';
  const d = new Date(clockEpochMs > 1_000_000_000_000 ? clockEpochMs : Date.now());
  const h = d.getHours();
  return h >= 6 && h < 18 ? 'light' : 'dark';
}

function applyEffectiveTheme(nextTheme) {
  if (nextTheme === effectiveTheme && (leafletMap || gmap || mlMap)) return;
  effectiveTheme = nextTheme;
  const isLight = effectiveTheme === 'light';

  const wrap = document.getElementById('map-stage-wrap');
  if (wrap) {
    wrap.classList.toggle('map-theme-light', isLight);
    wrap.classList.toggle('map-theme-dark', !isLight);
  }
  const expanded = document.getElementById('map-expanded-card');
  if (expanded) {
    expanded.classList.toggle('map-theme-light', isLight);
    expanded.classList.toggle('map-theme-dark', !isLight);
  }

  // Alterna camadas Leaflet Esri sem recriar o mapa
  if (leafletMap) {
    if (isLight) {
      if (darkBaseLayer && leafletMap.hasLayer(darkBaseLayer)) leafletMap.removeLayer(darkBaseLayer);
      if (darkLabelsLayer && leafletMap.hasLayer(darkLabelsLayer)) leafletMap.removeLayer(darkLabelsLayer);
      if (lightBaseLayer && !leafletMap.hasLayer(lightBaseLayer)) lightBaseLayer.addTo(leafletMap);
    } else {
      if (lightBaseLayer && leafletMap.hasLayer(lightBaseLayer)) leafletMap.removeLayer(lightBaseLayer);
      if (darkBaseLayer && !leafletMap.hasLayer(darkBaseLayer)) darkBaseLayer.addTo(leafletMap);
      if (darkLabelsLayer && !leafletMap.hasLayer(darkLabelsLayer)) darkLabelsLayer.addTo(leafletMap);
    }
  }

  // MapLibre: troca o estilo vetorial (claro/escuro) mantendo rota e destino
  if (mlMap) {
    mlMap.setStyle(buildMapStyle(effectiveTheme, lastAccent, mlRouteData, mlDestData));
  }

  // Atualiza estilo Google Maps se ativo
  if (gmap) {
    gmap.setOptions({
      styles: isLight ? [] : GOOGLE_DARK_STYLE,
      backgroundColor: isLight ? '#E8EAED' : '#172130'
    });
  }
}

// ---------------------------------------------------------------------------
// Leaflet (Esri)
// ---------------------------------------------------------------------------

function buildCarIcon() {
  const html = `
    <div class="hd-car-rotator">
      <svg width="58" height="58" viewBox="0 0 58 58" fill="none" aria-hidden="true">
        <circle cx="29" cy="31" r="22" fill="var(--a20)"></circle>
        <circle cx="29" cy="31" r="13" fill="var(--a35)"></circle>
        <path d="M29 9 L43 43 L29 35.5 L15 43 Z" fill="var(--accent)" stroke="#06090F" stroke-width="2.4" stroke-linejoin="round"></path>
        <path d="M29 12.5 L29 34 L18 40 Z" fill="rgba(255,255,255,0.28)"></path>
      </svg>
    </div>
  `;
  return window.L.divIcon({
    className: 'hd-car-marker',
    html,
    iconSize: [58, 58],
    iconAnchor: [29, 31]
  });
}

function initLeafletStage() {
  const stage = document.getElementById('leaflet-stage');
  if (!window.L || !stage || leafletMap) return;

  const mock = isMockBackend();
  const darkBaseUrl = mock ? ESRI_DARK_BASE : '/tiles/{z}/{y}/{x}.png';
  const darkLabelsUrl = mock ? ESRI_DARK_REF : '/labels/{z}/{y}/{x}.png';
  const lightUrl = mock ? ESRI_LIGHT_STREET : '/tiles/light/{z}/{y}/{x}.png';

  leafletMap = window.L.map(stage, {
    center: [ANAPOLIS_DEFAULT.lat, ANAPOLIS_DEFAULT.lon],
    zoom: 15,
    maxZoom: 18,
    minZoom: 4,
    zoomControl: false,
    attributionControl: false
  });

  darkBaseLayer = window.L.tileLayer(darkBaseUrl, {
    maxZoom: 18,
    maxNativeZoom: 16,
    className: 'hd-base-tiles'
  });

  const labelsPane = leafletMap.createPane('labelsPane');
  labelsPane.style.zIndex = '350';
  labelsPane.style.pointerEvents = 'none';

  darkLabelsLayer = window.L.tileLayer(darkLabelsUrl, {
    maxZoom: 18,
    maxNativeZoom: 16,
    pane: 'labelsPane',
    className: 'hd-label-tiles'
  });

  lightBaseLayer = window.L.tileLayer(lightUrl, {
    maxZoom: 18,
    maxNativeZoom: 17,
    className: 'hd-light-tiles'
  });

  routeGlowLine = window.L.polyline([], {
    color: lastAccent,
    weight: 14,
    opacity: 0.35,
    lineCap: 'round',
    lineJoin: 'round',
    interactive: false
  }).addTo(leafletMap);

  routeMainLine = window.L.polyline([], {
    color: lastAccent,
    weight: 5,
    opacity: 1,
    lineCap: 'round',
    lineJoin: 'round',
    interactive: false
  }).addTo(leafletMap);

  carMarker = window.L.marker([ANAPOLIS_DEFAULT.lat, ANAPOLIS_DEFAULT.lon], {
    icon: buildCarIcon(),
    interactive: false,
    keyboard: false
  }).addTo(leafletMap);

  leafletMap.on('dragstart', () => {
    if (isMapInteractiveMode(currentMode)) {
      followCar = false;
      updateRecenterUi();
      updateDroneCameraTransform();
    }
  });

  applyEffectiveTheme(resolveEffectiveTheme(configuredMapTheme, lastClockMs));
}

// ---------------------------------------------------------------------------
// MapLibre GL + OpenFreeMap (vetorial, 3D real, gratuito e sem chave)
// ---------------------------------------------------------------------------

function buildMlCarElement() {
  const node = document.createElement('div');
  node.className = 'hd-ml-car';
  node.innerHTML = `
    <svg width="92" height="92" viewBox="0 0 58 58" fill="none" aria-hidden="true">
      <circle cx="29" cy="31" r="24" fill="var(--a20)"></circle>
      <circle cx="29" cy="31" r="14" fill="var(--a35)"></circle>
      <path d="M29 9 L43 43 L29 35.5 L15 43 Z" fill="var(--accent)" stroke="#06090F" stroke-width="2.4" stroke-linejoin="round"></path>
      <path d="M29 12.5 L29 34 L18 40 Z" fill="rgba(255,255,255,0.28)"></path>
    </svg>`;
  return node;
}

function initMapLibreStage() {
  const stage = document.getElementById('maplibre-stage');
  if (!window.maplibregl || !stage || mlMap || !webglAvailable()) return false;
  try {
    const theme = resolveEffectiveTheme(configuredMapTheme, lastClockMs);
    effectiveTheme = theme;
    mlMap = new window.maplibregl.Map({
      container: stage,
      style: buildMapStyle(theme, lastAccent, mlRouteData, mlDestData),
      center: [ANAPOLIS_DEFAULT.lon, ANAPOLIS_DEFAULT.lat],
      zoom: 16.4,
      pitch: 62,
      maxPitch: 70,
      bearing: 0,
      attributionControl: { compact: true },
      interactive: true,
      dragRotate: false,
      pitchWithRotate: false,
      fadeDuration: 150,
      maxTileCacheSize: 120
    });
  } catch (err) {
    console.warn('[HondaDeck] MapLibre indisponível, usando Leaflet:', err);
    mlMap = null;
    return false;
  }

  activeProvider = 'maplibre';
  window.__hdMap = mlMap; // depuração via DevTools/CDP
  mlMap.touchZoomRotate.disableRotation(); // mapa sempre com a frente do carro para cima
  stage.classList.remove('hidden');
  document.getElementById('leaflet-stage')?.classList.add('hidden');
  document.getElementById('map-stage-wrap')?.classList.add('use-maplibre');

  mlCarMarker = new window.maplibregl.Marker({
    element: buildMlCarElement(),
    rotationAlignment: 'map',
    pitchAlignment: 'map'
  })
    .setLngLat([ANAPOLIS_DEFAULT.lon, ANAPOLIS_DEFAULT.lat])
    .addTo(mlMap);

  mlMap.on('dragstart', () => {
    if (isMapInteractiveMode(currentMode)) {
      followCar = false;
      updateRecenterUi();
    }
  });
  mlMap.on('error', (e) => console.warn('[HondaDeck] MapLibre:', e?.error?.message || e));
  // A rota pode chegar antes do estilo carregar: reaplica ao ficar pronto (e após trocar claro/escuro)
  mlMap.on('style.load', () => {
    mlMap.getSource('hd-route')?.setData(mlRouteData);
    mlMap.getSource('hd-dest')?.setData(mlDestData);
  });
  // Nunca mexer na câmera de dentro de um evento de render (o MapLibre trava com "already running")
  mlMap.once('load', () => setTimeout(() => mlFollowCamera({ immediate: true }), 0));
  return true;
}

// Onde o carro fica na tela em cada modo (proporção do painel 1333×800)
function mlCameraTarget() {
  const el = mlMap.getContainer();
  const W = el.clientWidth || 1333;
  const H = el.clientHeight || 800;
  let x = 693 / 1333;
  let y = 0.7;
  let pitch = 62;
  let zoom = 16.6;
  if (currentMode === 'music') x = 183 / 1333;
  else if (currentMode === 'vehicle') x = 1151 / 1333;
  else if (currentMode === 'map') {
    x = 0.5;
    y = 0.66;
    pitch = 52;
    zoom = null;
  } else if (currentMode === 'mapFull') {
    x = 0.5;
    y = 0.64;
    pitch = 48;
    zoom = null;
  }
  // Deslocamento do carro em relação ao centro da tela (offset do MapLibre)
  return { pitch, zoom, offset: [x * W - W / 2, y * H - H / 2] };
}

function mlFollowCamera({ immediate = false, duration = 700, linear = false } = {}) {
  if (!mlMap || !followCar) return;
  const lat = Number(lastMapState?.lat || ANAPOLIS_DEFAULT.lat);
  const lon = Number(lastMapState?.lon || ANAPOLIS_DEFAULT.lon);
  const t = mlCameraTarget();
  const opts = {
    center: [lon, lat],
    bearing: ((smoothBearing % 360) + 360) % 360,
    pitch: t.pitch,
    zoom: t.zoom ?? Math.max(mlMap.getZoom(), 15),
    padding: { left: 0, right: 0, top: 0, bottom: 0 },
    offset: t.offset
  };
  // jumpTo ignora "offset"; easeTo com duração 0 posiciona na hora respeitando o deslocamento
  if (immediate) mlMap.easeTo({ ...opts, duration: 0 });
  else if (linear) mlMap.easeTo({ ...opts, duration, easing: (k) => k, essential: true });
  else mlMap.easeTo({ ...opts, duration, essential: true });
}

// ---------------------------------------------------------------------------
// Google Maps JavaScript API (quando MAPS_JS_API_KEY está presente)
// ---------------------------------------------------------------------------

function loadGoogleMapsScript(apiKey) {
  if (!apiKey || gmapsLoaded || gmapsLoadingKey === apiKey) return;
  gmapsLoadingKey = apiKey;

  if (window.google && window.google.maps) {
    onGoogleMapsReady();
    return;
  }

  const cbName = '__hdGoogleMapsReady';
  window[cbName] = () => {
    delete window[cbName];
    onGoogleMapsReady();
  };

  const script = document.createElement('script');
  script.async = true;
  script.defer = true;
  script.src =
    `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(apiKey)}` +
    `&libraries=places,geometry&language=pt-BR&region=BR&callback=${cbName}`;
  script.onerror = () => {
    console.warn('[HondaDeck] Google Maps JS API indisponível; mantendo Esri/Leaflet.');
    gmapsLoadingKey = '';
  };
  document.head.appendChild(script);
}

function buildGoogleCarSymbol(bearingDeg) {
  if (!window.google?.maps) return null;
  return {
    path: 'M 0,-14 L 10,12 L 0,6 L -10,12 Z',
    fillColor: lastAccent,
    fillOpacity: 1,
    strokeColor: '#06090F',
    strokeWeight: 2,
    scale: 1.35,
    rotation: Number(bearingDeg || 0)
  };
}

function onGoogleMapsReady() {
  const gStage = document.getElementById('gmaps-stage');
  const lStage = document.getElementById('leaflet-stage');
  if (!gStage || !window.google?.maps) return;

  gmapsLoaded = true;
  activeProvider = 'google';
  gStage.classList.remove('hidden');
  if (lStage) lStage.classList.add('hidden');

  const lat = Number(lastMapState?.lat || ANAPOLIS_DEFAULT.lat);
  const lon = Number(lastMapState?.lon || ANAPOLIS_DEFAULT.lon);
  const isLight = effectiveTheme === 'light';

  gmap = new window.google.maps.Map(gStage, {
    center: { lat, lng: lon },
    zoom: 15,
    disableDefaultUI: true,
    clickableIcons: false,
    gestureHandling: isMapInteractiveMode(currentMode) ? 'greedy' : 'none',
    styles: isLight ? [] : GOOGLE_DARK_STYLE,
    backgroundColor: isLight ? '#E8EAED' : '#172130'
  });

  gmapTrafficLayer = new window.google.maps.TrafficLayer();
  gmapTrafficLayer.setMap(gmap);

  gmapDirectionsService = new window.google.maps.DirectionsService();

  gmapRouteGlow = new window.google.maps.Polyline({
    map: gmap,
    path: [],
    strokeColor: lastAccent,
    strokeOpacity: 0.35,
    strokeWeight: 14,
    clickable: false
  });

  gmapRouteMain = new window.google.maps.Polyline({
    map: gmap,
    path: [],
    strokeColor: lastAccent,
    strokeOpacity: 1,
    strokeWeight: 5,
    clickable: false
  });

  gmapCarMarker = new window.google.maps.Marker({
    map: gmap,
    position: { lat, lng: lon },
    icon: buildGoogleCarSymbol(lastMapState?.bearing || 0),
    clickable: false,
    zIndex: 999
  });

  gmap.addListener('dragstart', () => {
    if (isMapInteractiveMode(currentMode)) {
      followCar = false;
      updateRecenterUi();
      updateDroneCameraTransform();
    }
  });

  if (lastMapState) {
    syncGoogleMapState(lastMapState);
  }
}

function syncGoogleMapState(mapState) {
  if (!gmap || !window.google?.maps) return;
  const lat = Number(mapState.lat || ANAPOLIS_DEFAULT.lat);
  const lon = Number(mapState.lon || ANAPOLIS_DEFAULT.lon);
  const bearing = Number(mapState.bearing || 0);
  stepSmoothBearing(bearing);
  updateDroneCameraTransform();
  const pos = { lat, lng: lon };

  if (gmapCarMarker) {
    gmapCarMarker.setPosition(pos);
    gmapCarMarker.setIcon(buildGoogleCarSymbol(bearing));
  }
  if (followCar) {
    gmap.panTo(pos);
  }

  const rt = mapState.route || {};
  const pts = rt.active && Array.isArray(rt.polyline) ? rt.polyline : [];
  if (pts.length >= 2) {
    const destPt = pts[pts.length - 1];
    const dirSig = `${rt.destination}|${Number(destPt[0]).toFixed(4)},${Number(destPt[1]).toFixed(4)}`;
    if (dirSig !== gmapLastDirectionsSig && gmapDirectionsService) {
      gmapLastDirectionsSig = dirSig;
      // Tenta rota com trânsito via Google Directions; fallback imediato para a polyline do backend
      const fallbackPath = pts.map(([pLat, pLon]) => ({ lat: Number(pLat), lng: Number(pLon) }));
      applyGooglePolyline(fallbackPath, { lat: Number(destPt[0]), lng: Number(destPt[1]) });

      gmapDirectionsService.route(
        {
          origin: pos,
          destination: { lat: Number(destPt[0]), lng: Number(destPt[1]) },
          travelMode: window.google.maps.TravelMode.DRIVING,
          drivingOptions: {
            departureTime: new Date(),
            trafficModel: window.google.maps.TrafficModel.BEST_GUESS
          }
        },
        (res, status) => {
          if (status === 'OK' && res?.routes?.[0]?.overview_path?.length) {
            const gPath = res.routes[0].overview_path.map((ll) => ({ lat: ll.lat(), lng: ll.lng() }));
            applyGooglePolyline(gPath, gPath[gPath.length - 1]);
          }
        }
      );
    }
  } else {
    gmapLastDirectionsSig = '';
    applyGooglePolyline([], null);
  }
}

function applyGooglePolyline(path, destPos) {
  if (gmapRouteGlow) gmapRouteGlow.setPath(path);
  if (gmapRouteMain) gmapRouteMain.setPath(path);
  if (gmapDestMarker) {
    gmapDestMarker.setMap(null);
    gmapDestMarker = null;
  }
  if (destPos && window.google?.maps && gmap) {
    gmapDestMarker = new window.google.maps.Marker({
      map: gmap,
      position: destPos,
      clickable: false,
      icon: {
        path: window.google.maps.SymbolPath.CIRCLE,
        scale: 7,
        fillColor: '#0A1019',
        fillOpacity: 1,
        strokeColor: lastAccent,
        strokeWeight: 3
      }
    });
  }
}

async function runGooglePlacesAutocomplete(query) {
  const q = String(query || '').trim();
  gmapAutocompleteQuery = q;
  if (!q || q.length < 2 || !window.google?.maps?.places) {
    gmapAutocompleteResults = [];
    gmapAutocompleteLoading = false;
    return false;
  }

  gmapAutocompleteLoading = true;
  renderActiveSearchUIs();

  const lat = Number(lastMapState?.lat || ANAPOLIS_DEFAULT.lat);
  const lon = Number(lastMapState?.lon || ANAPOLIS_DEFAULT.lon);

  try {
    // 1) Places Autocomplete (New) com sessão e viés na posição atual/Anápolis
    if (window.google.maps.places.AutocompleteSuggestion?.fetchAutocompleteSuggestions) {
      if (!gmapSessionToken && window.google.maps.places.AutocompleteSessionToken) {
        gmapSessionToken = new window.google.maps.places.AutocompleteSessionToken();
      }
      const req = {
        input: q,
        sessionToken: gmapSessionToken || undefined,
        includedRegionCodes: ['br'],
        language: 'pt-BR',
        locationBias: {
          center: { lat, lng: lon },
          radius: 35000
        }
      };
      const { suggestions } = await window.google.maps.places.AutocompleteSuggestion.fetchAutocompleteSuggestions(req);
      if (gmapAutocompleteQuery !== q) return true;
      gmapAutocompleteResults = (suggestions || [])
        .filter((s) => s.placePrediction)
        .slice(0, 6)
        .map((s) => {
          const pred = s.placePrediction;
          return {
            name: pred.mainText?.text || pred.text?.text || q,
            address: pred.secondaryText?.text || '',
            lat: null,
            lon: null,
            distanceKm: pred.distanceMeters != null ? +(pred.distanceMeters / 1000).toFixed(1) : null,
            _prediction: pred
          };
        });
      gmapAutocompleteLoading = false;
      renderActiveSearchUIs();
      return true;
    }

    // 2) Fallback: AutocompleteService clássico
    if (window.google.maps.places.AutocompleteService) {
      if (!gmapSessionToken && window.google.maps.places.AutocompleteSessionToken) {
        gmapSessionToken = new window.google.maps.places.AutocompleteSessionToken();
      }
      const svc = new window.google.maps.places.AutocompleteService();
      const predictions = await new Promise((resolve) => {
        svc.getPlacePredictions(
          {
            input: q,
            sessionToken: gmapSessionToken || undefined,
            componentRestrictions: { country: 'br' },
            locationBias: new window.google.maps.Circle({ center: { lat, lng: lon }, radius: 35000 })
          },
          (preds, status) => resolve(status === 'OK' && preds ? preds : [])
        );
      });
      if (gmapAutocompleteQuery !== q) return true;
      gmapAutocompleteResults = predictions.slice(0, 6).map((p) => ({
        name: p.structured_formatting?.main_text || p.description || q,
        address: p.structured_formatting?.secondary_text || '',
        lat: null,
        lon: null,
        distanceKm: null,
        _placeId: p.place_id
      }));
      gmapAutocompleteLoading = false;
      renderActiveSearchUIs();
      return true;
    }
  } catch (err) {
    console.warn('[HondaDeck] Places Autocomplete falhou, usando busca do backend:', err);
  }

  gmapAutocompleteLoading = false;
  return false;
}

async function selectGooglePlaceResult(item) {
  try {
    if (item._prediction?.toPlace) {
      const place = item._prediction.toPlace();
      await place.fetchFields({ fields: ['location', 'displayName', 'formattedAddress'] });
      gmapSessionToken = null; // encerra sessão de autocomplete
      if (place.location) {
        startRoute(place.displayName || item.name, place.location.lat(), place.location.lng());
        return;
      }
    } else if (item._placeId && gmap && window.google?.maps?.places?.PlacesService) {
      const svc = new window.google.maps.places.PlacesService(gmap);
      const det = await new Promise((resolve) => {
        svc.getDetails(
          { placeId: item._placeId, fields: ['geometry.location', 'name'], sessionToken: gmapSessionToken || undefined },
          (res, status) => resolve(status === 'OK' ? res : null)
        );
      });
      gmapSessionToken = null;
      if (det?.geometry?.location) {
        startRoute(det.name || item.name, det.geometry.location.lat(), det.geometry.location.lng());
        return;
      }
    }
  } catch (err) {
    console.warn('[HondaDeck] Falha ao obter detalhes do Place:', err);
  }
  startRoute(item.address ? `${item.name}, ${item.address}` : item.name);
}

// ---------------------------------------------------------------------------
// Interatividade, slots e tela cheia
// ---------------------------------------------------------------------------

function setMapInteractive(interactive) {
  if (leafletMap) {
    const action = interactive ? 'enable' : 'disable';
    leafletMap.dragging[action]();
    leafletMap.touchZoom[action]();
    leafletMap.doubleClickZoom[action]();
    leafletMap.scrollWheelZoom[action]();
    leafletMap.boxZoom[action]();
    leafletMap.keyboard[action]();
  }
  if (gmap) {
    gmap.setOptions({ gestureHandling: interactive ? 'greedy' : 'none' });
  }
  if (mlMap) {
    const action = interactive ? 'enable' : 'disable';
    mlMap.dragPan[action]();
    mlMap.scrollZoom[action]();
    mlMap.touchZoomRotate[action]();
    mlMap.doubleClickZoom[action]();
    mlMap.keyboard[action]();
  }
}

function updateRecenterUi() {
  const btn = document.getElementById('map-recenter-btn');
  if (btn) btn.classList.toggle('active', followCar);
}

function updateFullscreenBtnUi() {
  const btn = document.getElementById('map-fullscreen-btn');
  const icon = document.getElementById('map-fullscreen-icon');
  if (!btn) return;
  const isFull = currentMode === 'mapFull';
  btn.classList.toggle('active', isFull);
  btn.setAttribute('aria-label', isFull ? 'Sair da tela cheia (modo 2/3)' : 'Mapa em tela cheia');
  btn.title = isFull ? 'Voltar ao modo 2/3' : 'Tela cheia';
  if (icon) {
    const path = icon.querySelector('path');
    if (path) {
      path.setAttribute(
        'd',
        isFull
          ? 'M4 14h6v6M20 10h-6V4M14 10l7-7M10 14l-7 7'
          : 'M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7'
      );
    }
  }
}

function stepSmoothBearing(targetDeg) {
  const tgt = Number(targetDeg || 0);
  if (!bearingInitialized) {
    bearingInitialized = true;
    smoothBearing = tgt;
    return smoothBearing;
  }
  const delta = (((tgt - smoothBearing) % 360) + 540) % 360 - 180;
  smoothBearing += delta;
  return smoothBearing;
}

function updateDroneCameraTransform() {
  const wrap = document.getElementById('map-stage-wrap');
  const viewport = document.getElementById('map-drone-viewport');
  const plane = document.getElementById('map-camera-plane');
  if (!wrap || !viewport || !plane) return;

  const isMini = false;
  const isQuarter = currentMode === 'music' || currentMode === 'vehicle';
  const isHome = currentMode === 'home' || isQuarter;
  const isFull = currentMode === 'mapFull';
  const isMap = currentMode === 'map';

  wrap.classList.toggle('is-mini-map', isMini);
  wrap.classList.toggle('is-quarter', isQuarter);
  wrap.classList.toggle('is-drone-home', isHome);
  wrap.classList.toggle('is-drone-map', isMap || isFull);

  // MapLibre inclina a câmera de verdade: nada de transformação CSS no plano
  if (mlMap) {
    viewport.style.perspective = 'none';
    plane.style.transform = 'none';
    return;
  }

  if (isMini || currentMode === 'settings') {
    viewport.style.perspective = 'none';
    plane.style.transform = 'none';
    return;
  }

  if (isHome) {
    // Câmera de drone baixa atrás do carro: inclinação forte, carro no terço inferior
    // e deslocado +27px para ficar no eixo da coluna central (388px | mapa | 334px).
    // Música: mapa no 1/4 esquerdo (centro x≈183); Veículo: 1/4 direito (x≈1151)
    const dx = currentMode === 'music' ? -484 : currentMode === 'vehicle' ? 484 : 27;
    const originX = ((666.5 + dx) / 13.33).toFixed(1);
    viewport.style.perspective = '760px';
    viewport.style.perspectiveOrigin = `${originX}% 26%`;
    plane.style.transform = `translate(${dx}px, 96px) rotateX(58deg) scale(1.55) rotateZ(${(-smoothBearing).toFixed(1)}deg)`;
    return;
  }

  // Modo Mapa / Tela Cheia: mantém inclinação drone suave ao seguir o carro e aplana ao arrastar livremente
  if (followCar) {
    const tilt = isFull ? 22 : 26;
    const scale = isFull ? 1.15 : 1.18;
    viewport.style.perspective = '1420px';
    viewport.style.perspectiveOrigin = '50% 44%';
    plane.style.transform = `rotateX(${tilt}deg) scale(${scale}) rotateZ(0deg)`;
  } else {
    viewport.style.perspective = '1420px';
    viewport.style.perspectiveOrigin = '50% 50%';
    plane.style.transform = 'rotateX(0deg) scale(1) rotateZ(0deg)';
  }
}

function resizeActiveMap() {
  if (mlMap) {
    requestAnimationFrame(() => {
      mlMap.resize();
      mlFollowCamera({ immediate: true });
    });
  }
  if (leafletMap) {
    leafletMap.invalidateSize({ animate: false });
    if (lastMapState && followCar) {
      const z = currentMode === 'home' ? 16 : isMapInteractiveMode(currentMode) ? Math.max(leafletMap.getZoom(), 15) : 15;
      leafletMap.setView([lastMapState.lat, lastMapState.lon], z, { animate: false });
    }
  }
  if (gmap && window.google?.maps) {
    window.google.maps.event.trigger(gmap, 'resize');
    if (lastMapState && followCar) {
      gmap.setCenter({ lat: Number(lastMapState.lat), lng: Number(lastMapState.lon) });
      if (currentMode === 'home') gmap.setZoom(16);
    }
  }
}

function attachStageToSlot(mode) {
  const stageWrap = document.getElementById('map-stage-wrap');
  let targetSlot = null;
  // Música e Veículo usam o mapa de fundo visível em 1/4 da tela
  if (mode === 'home' || mode === 'map' || mode === 'mapFull' || mode === 'music' || mode === 'vehicle') {
    targetSlot = document.getElementById('global-map-slot');
  } else targetSlot = document.getElementById('leaflet-holding');

  const moved = Boolean(targetSlot && stageWrap && stageWrap.parentElement !== targetSlot);
  if (moved) {
    targetSlot.appendChild(stageWrap);
  }
  const interactive = isMapInteractiveMode(mode);
  setMapInteractive(interactive);
  if (!interactive) {
    followCar = true;
    updateRecenterUi();
  }
  updateFullscreenBtnUi();
  updateDroneCameraTransform();
  // Troca de modo: a câmera desliza até o novo enquadramento (carro no 1/4, no centro…)
  if (mlMap && !moved) mlFollowCamera({ duration: 650 });

  if (moved) {
    requestAnimationFrame(() => resizeActiveMap());
    setTimeout(() => resizeActiveMap(), 370);
  } else if (followCar && lastMapState) {
    if (leafletMap) {
      const z = mode === 'home' ? 16 : Math.max(leafletMap.getZoom(), 15);
      leafletMap.setView([lastMapState.lat, lastMapState.lon], z, { animate: true });
    } else if (gmap) {
      gmap.panTo({ lat: Number(lastMapState.lat), lng: Number(lastMapState.lon) });
    }
  }
}

// ---------------------------------------------------------------------------
// Busca inteligente (debounce 300 ms, Places ou Photon/Nominatim + fav/recentes)
// ---------------------------------------------------------------------------

const debouncedSearch = debounce(async (q) => {
  if (activeProvider === 'google' && gmapsLoaded) {
    const handled = await runGooglePlacesAutocomplete(q);
    if (handled) return;
  }
  sendCommand('map.search', { query: q });
}, 300);

function startRoute(name, lat, lon) {
  const args = { name: String(name || 'Destino') };
  if (Number.isFinite(Number(lat)) && Number.isFinite(Number(lon)) && (Number(lat) !== 0 || Number(lon) !== 0)) {
    args.lat = Number(lat);
    args.lon = Number(lon);
  }
  sendCommand('map.route', args);
}

function getMergedSearchSuggestions(q, maxRemote = 6) {
  const ql = normalizeStr(q);
  const mp = lastMapState || { favorites: [], recentPlaces: [], search: { results: [], loading: false }, lat: ANAPOLIS_DEFAULT.lat, lon: ANAPOLIS_DEFAULT.lon };

  const seen = new Set();
  const dedupKey = (name, lat, lon) =>
    `${normalizeStr(name)}|${Number.isFinite(Number(lat)) ? Math.round(Number(lat) * 1000) : 'n'}|${Number.isFinite(Number(lon)) ? Math.round(Number(lon) * 1000) : 'n'}`;

  // 1) Favoritos correspondentes (tolerante a acentos)
  const favHits = (mp.favorites || []).filter(
    (f) => normalizeStr(f.name).includes(ql) || normalizeStr(f.address).includes(ql)
  );
  favHits.forEach((f) => seen.add(dedupKey(f.name, f.lat, f.lon)));

  // 2) Recentes correspondentes que não estão nos favoritos
  const recentHits = (mp.recentPlaces || []).filter((p) => {
    if (!normalizeStr(p.name).includes(ql) && !normalizeStr(p.address).includes(ql)) return false;
    const k = dedupKey(p.name, p.lat, p.lon);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });

  // 3) Resultados remotos (Google Places quando ativo, ou Photon/Nominatim do backend)
  const useGoogleList = activeProvider === 'google' && gmapsLoaded && gmapAutocompleteQuery === q;
  const rawRemote = useGoogleList ? gmapAutocompleteResults : (mp.search?.results || []);
  const remoteHits = [];
  for (const r of rawRemote) {
    const k = dedupKey(r.name, r.lat, r.lon);
    if (seen.has(k)) continue;
    seen.add(k);
    remoteHits.push(r);
    if (remoteHits.length >= maxRemote) break;
  }

  const loading = useGoogleList ? gmapAutocompleteLoading : Boolean(mp.search?.loading);
  return { mp, favHits, recentHits, remoteHits, loading };
}

let lastHomeDropSig = '';
let lastFullSearchSig = '';

function renderActiveSearchUIs() {
  if (document.getElementById('home-map-input')?.value.trim()) {
    renderHomeDropdown();
  }
  if (document.getElementById('full-map-input')?.value.trim()) {
    renderFullSearchResults();
  }
}

function renderHomeDropdown() {
  const input = document.getElementById('home-map-input');
  const clearBtn = document.getElementById('home-map-clear');
  const dropdown = document.getElementById('home-map-dropdown');
  const q = (input.value || '').trim();
  clearBtn.classList.toggle('hidden', q.length === 0);

  if (!q) {
    lastHomeDropSig = '';
    dropdown.classList.add('hidden');
    dropdown.replaceChildren();
    return;
  }

  const { mp, favHits, recentHits, remoteHits } = getMergedSearchSuggestions(q, 5);
  const sig = JSON.stringify({
    q,
    favs: favHits.slice(0, 3).map((f) => [f.id, f.name, f.lat, f.lon]),
    recs: recentHits.slice(0, 3).map((r) => [r.name, r.lat, r.lon]),
    rem: remoteHits.map((r) => [r.name, r.address, r._placeId, r.lat, r.lon])
  });
  if (sig === lastHomeDropSig && !dropdown.classList.contains('hidden')) {
    return;
  }
  lastHomeDropSig = sig;
  const prevScrollTop = dropdown.scrollTop;
  dropdown.replaceChildren();

  // 1) Favoritos
  for (const fav of favHits.slice(0, 3)) {
    const distKm = haversineKm(mp.lat, mp.lon, fav.lat, fav.lon);
    dropdown.appendChild(
      el(
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
      )
    );
  }

  // 2) Recentes
  for (const rec of recentHits.slice(0, 2)) {
    const distKm = haversineKm(mp.lat, mp.lon, rec.lat, rec.lon);
    dropdown.appendChild(
      el(
        'button',
        {
          className: 'dropdown-item',
          type: 'button',
          onClick: () => {
            input.value = '';
            renderHomeDropdown();
            startRoute(rec.name, rec.lat, rec.lon);
          }
        },
        createClockSvg(16, '#8A97A8'),
        el(
          'span',
          { className: 'dropdown-item-texts' },
          el('span', { className: 'dropdown-item-title' }, rec.name),
          rec.address ? el('span', { className: 'dropdown-item-sub' }, rec.address) : null
        ),
        el('span', { className: 'dropdown-item-meta' }, distKm !== null ? formatKm(distKm) : '')
      )
    );
  }

  // 3) Resultados remotos (Places ou Photon/Nominatim)
  remoteHits.slice(0, 4).forEach((r, idx) => {
    const dist = r.distanceKm != null
      ? formatKm(r.distanceKm)
      : (() => {
          const d = r.lat != null && r.lon != null ? haversineKm(mp.lat, mp.lon, r.lat, r.lon) : null;
          return d !== null ? formatKm(d) : '';
        })();
    dropdown.appendChild(
      el(
        'button',
        {
          className: 'dropdown-item',
          type: 'button',
          'data-test': `map-result-${idx}`,
          onClick: () => {
            input.value = '';
            renderHomeDropdown();
            if (r._prediction || r._placeId) selectGooglePlaceResult(r);
            else startRoute(r.name, r.lat, r.lon);
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
      )
    );
  });

  // 4) Fallback direto
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
  if (prevScrollTop > 0) dropdown.scrollTop = prevScrollTop;
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
    lastFullSearchSig = '';
    defaultBox.classList.remove('hidden');
    resultsBox.classList.add('hidden');
    return;
  }

  defaultBox.classList.add('hidden');
  resultsBox.classList.remove('hidden');

  const { mp, favHits, recentHits, remoteHits, loading } = getMergedSearchSuggestions(q, 8);
  const sig = JSON.stringify({
    q,
    loading,
    favs: favHits.map((f) => [f.id, f.name, f.lat, f.lon]),
    recs: recentHits.map((r) => [r.name, r.lat, r.lon]),
    rem: remoteHits.map((r) => [r.name, r.address, r._placeId, r.lat, r.lon])
  });
  if (sig === lastFullSearchSig) {
    return;
  }
  lastFullSearchSig = sig;
  const prevScrollTop = listEl.scrollTop;
  listEl.replaceChildren();

  // Favoritos filtrados
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

  // Recentes filtrados
  for (const rec of recentHits) {
    const distKm = haversineKm(mp.lat, mp.lon, rec.lat, rec.lon);
    listEl.appendChild(
      el(
        'button',
        {
          className: 'place-row-btn',
          type: 'button',
          onClick: () => {
            input.value = '';
            renderFullSearchResults();
            startRoute(rec.name, rec.lat, rec.lon);
          }
        },
        createClockSvg(18, '#8A97A8'),
        el(
          'span',
          { className: 'place-row-texts' },
          el('span', { className: 'place-row-name' }, rec.name),
          rec.address ? el('span', { className: 'place-row-addr' }, rec.address) : null
        ),
        el('span', { className: 'place-row-dist' }, distKm !== null ? formatKm(distKm) : '')
      )
    );
  }

  if (loading) {
    listEl.appendChild(el('div', { className: 'settings-sub', style: 'padding: 12px 4px' }, 'Buscando locais próximos…'));
  }

  remoteHits.forEach((r, idx) => {
    const dist = r.distanceKm != null
      ? formatKm(r.distanceKm)
      : (() => {
          const d = r.lat != null && r.lon != null ? haversineKm(mp.lat, mp.lon, r.lat, r.lon) : null;
          return d !== null ? formatKm(d) : '';
        })();
    listEl.appendChild(
      el(
        'button',
        {
          className: 'place-row-btn',
          type: 'button',
          'data-test': `map-result-${idx}`,
          onClick: () => {
            input.value = '';
            renderFullSearchResults();
            if (r._prediction || r._placeId) selectGooglePlaceResult(r);
            else startRoute(r.name, r.lat, r.lon);
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
  });

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
  if (prevScrollTop > 0) listEl.scrollTop = prevScrollTop;
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

// ---------------------------------------------------------------------------
// API pública do módulo
// ---------------------------------------------------------------------------

export function initMap({ switchMode, openFavoriteSettings }) {
  onModeSwitch = switchMode;
  onOpenFavoriteSettings = openFavoriteSettings;

  // MapLibre (vetorial/3D) é o padrão; sem WebGL cai para o Leaflet com imagens Esri
  if (!initMapLibreStage()) initLeafletStage();

  const stageWrap = document.getElementById('map-stage-wrap');
  if (stageWrap && typeof ResizeObserver !== 'undefined') {
    const ro = new ResizeObserver(() => resizeActiveMap());
    ro.observe(stageWrap);
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
  document.getElementById('map-fullscreen-btn')?.addEventListener('click', () => {
    onModeSwitch(currentMode === 'mapFull' ? 'map' : 'mapFull');
  });

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
    updateDroneCameraTransform();
    if (mlMap) mlFollowCamera({ duration: 600 });
    if (lastMapState) {
      if (activeProvider === 'google' && gmap) {
        gmap.panTo({ lat: Number(lastMapState.lat), lng: Number(lastMapState.lon) });
        gmap.setZoom(16);
      } else if (leafletMap) {
        leafletMap.setView([lastMapState.lat, lastMapState.lon], 16, { animate: true });
      }
    }
  });
  document.getElementById('map-zoom-in-btn').addEventListener('click', () => {
    if (mlMap) mlMap.zoomIn();
    else if (activeProvider === 'google' && gmap) gmap.setZoom((gmap.getZoom() || 15) + 1);
    else if (leafletMap) leafletMap.zoomIn();
  });
  document.getElementById('map-zoom-out-btn').addEventListener('click', () => {
    if (mlMap) mlMap.zoomOut();
    else if (activeProvider === 'google' && gmap) gmap.setZoom(Math.max(4, (gmap.getZoom() || 15) - 1));
    else if (leafletMap) leafletMap.zoomOut();
  });

  document.getElementById('map-stop-route-btn').addEventListener('click', () => {
    sendCommand('map.stopRoute');
  });
  document.getElementById('map-external-btn').addEventListener('click', () => {
    const dest = lastMapState?.route?.destination || lastMapState?.route?.nextInstruction || 'Destino';
    sendCommand('map.openExternal', { name: dest, package: 'com.google.android.apps.maps' });
  });
  document.getElementById('map-waze-btn')?.addEventListener('click', () => {
    const dest = lastMapState?.route?.destination || lastMapState?.route?.nextInstruction || 'Destino';
    sendCommand('map.openExternal', { name: dest, package: 'com.waze' });
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
  if (mlMap && mlMap.isStyleLoaded()) {
    for (const id of ['hd-route-glow', 'hd-route-main']) {
      if (mlMap.getLayer(id)) mlMap.setPaintProperty(id, 'line-color', hex);
    }
    if (mlMap.getLayer('hd-dest')) mlMap.setPaintProperty('hd-dest', 'circle-stroke-color', hex);
  }
  if (gmapRouteGlow) gmapRouteGlow.setOptions({ strokeColor: hex });
  if (gmapRouteMain) gmapRouteMain.setOptions({ strokeColor: hex });
  if (gmapCarMarker && lastMapState) gmapCarMarker.setIcon(buildGoogleCarSymbol(lastMapState.bearing || 0));
}

export function updateMapState(mapState, clockEpochMs, options = {}) {
  if (!mapState) return;
  lastMapState = mapState;
  if (clockEpochMs > 1_000_000_000_000) lastClockMs = clockEpochMs;

  if (options.mapTheme) {
    configuredMapTheme = String(options.mapTheme).toUpperCase();
  }
  applyEffectiveTheme(resolveEffectiveTheme(configuredMapTheme, lastClockMs));

  // Se houver chave oficial do Google Maps JS API, carrega sob demanda
  if (mapState.provider === 'google' && mapState.jsKey) {
    loadGoogleMapsScript(String(mapState.jsKey));
  }

  const lat = Number(mapState.lat || ANAPOLIS_DEFAULT.lat);
  const lon = Number(mapState.lon || ANAPOLIS_DEFAULT.lon);
  const bearing = Number(mapState.bearing || 0);
  stepSmoothBearing(bearing);
  updateDroneCameraTransform();

  if (activeProvider === 'google' && gmap) {
    syncGoogleMapState(mapState);
  }

  if (mlMap) {
    mlCarMarker?.setLngLat([lon, lat]).setRotation(bearing);
    const rt = mapState.route || {};
    const pts = rt.active && Array.isArray(rt.polyline) ? rt.polyline : [];
    const nextPolySig = pts.length >= 2 ? JSON.stringify(pts) : '';
    if (nextPolySig !== polySignature) {
      polySignature = nextPolySig;
      mlRouteData = routeToGeoJSON(pts);
      mlDestData = destToGeoJSON(pts);
      mlMap.getSource('hd-route')?.setData(mlRouteData);
      mlMap.getSource('hd-dest')?.setData(mlDestData);
    }
    // Segue o carro suavemente entre uma leitura de GPS e a próxima
    mlFollowCamera({ duration: 900, linear: true });
  }

  if (leafletMap && carMarker) {
    carMarker.setLatLng([lat, lon]);
    if (!carRotatorEl) {
      carRotatorEl = carMarker.getElement()?.querySelector('.hd-car-rotator') || null;
    }
    if (carRotatorEl) {
      carRotatorEl.style.transform = `rotate(${bearing.toFixed(1)}deg)`;
    }
    if (followCar) {
      leafletMap.panTo([lat, lon], { animate: false });
    }

    // Polyline da rota no Leaflet
    const rt = mapState.route || {};
    const pts = rt.active && Array.isArray(rt.polyline) ? rt.polyline : [];
    const nextPolySig = pts.length >= 2 ? JSON.stringify(pts) : '';
    if (nextPolySig !== polySignature) {
      polySignature = nextPolySig;
      routeGlowLine.setLatLngs(pts);
      routeMainLine.setLatLngs(pts);
      if (destCircle) {
        leafletMap.removeLayer(destCircle);
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
        }).addTo(leafletMap);
      }
    }
  }

  renderRouteHud(mapState);
  renderDrawerLists(mapState);
  renderActiveSearchUIs();
}
