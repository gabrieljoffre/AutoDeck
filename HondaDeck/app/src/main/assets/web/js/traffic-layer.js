// Trânsito ao vivo no mapa 3D (TomTom Traffic API — plano gratuito, chave em local.properties).
// - Fluxo: tiles vetoriais "relative" (velocidade atual / velocidade livre) pintados como as ruas
//   do mapa: vermelho parado, laranja lento, amarelo moderado; vias livres ficam sem cor.
// - Ocorrências: acidentes, obras, vias fechadas e congestionamentos (JSON incidentDetails),
//   atualizadas a cada 3 min só ao redor do carro e só com a camada ligada.

const FLOW_TILES = (key, stamp) =>
  `https://api.tomtom.com/traffic/map/4/tile/flow/relative/{z}/{x}/{y}.pbf?key=${encodeURIComponent(key)}&t=${stamp}`;
const INCIDENTS_URL = 'https://api.tomtom.com/traffic/services/5/incidentDetails';
const INCIDENT_FIELDS =
  '{incidents{type,geometry{type,coordinates},properties{iconCategory,magnitudeOfDelay,events{description},from,to,delay}}}';

const FLOW_REFRESH_MS = 5 * 60_000; // tiles de fluxo: TomTom atualiza ~1 min; 5 min economiza cota
const INCIDENT_REFRESH_MS = 3 * 60_000;
const INCIDENT_RADIUS_DEG = 0.12; // ~13 km ao redor do carro
const STORAGE_KEY = 'hd.traffic.enabled';

// iconCategory TomTom: 1 acidente, 6 congestionamento, 7 faixa fechada, 8 via fechada, 9 obras
const INCIDENT_COLOR = [
  'match', ['get', 'cat'],
  1, '#FF2D46',
  6, '#FF8A1F',
  7, '#E0245E',
  8, '#E0245E',
  9, '#FFB020',
  '#FACC15'
];

const EMPTY_FC = { type: 'FeatureCollection', features: [] };

export function createTrafficLayer({ map, getKey, getPosition, beforeLayerId = 'building-3d', onChange }) {
  let enabled = localStorage.getItem(STORAGE_KEY) !== '0';
  let incidents = EMPTY_FC;
  let flowStamp = Math.floor(Date.now() / FLOW_REFRESH_MS);
  let incidentTimer = null;
  let flowTimer = null;
  let lastFetchPos = null;
  let inflight = null;

  const key = () => String(getKey() || '').trim();
  const visibility = () => (enabled ? 'visible' : 'none');

  function addLayers() {
    const k = key();
    if (!k || !map.getStyle()?.layers || !map.getSource('openmaptiles')) return;
    try {
      addLayersUnsafe(k);
    } catch (err) {
      console.warn('[HondaDeck] Trânsito: camada não adicionada ainda:', err?.message || err);
    }
  }

  function addLayersUnsafe(k) {
    const before = map.getLayer(beforeLayerId) ? beforeLayerId : undefined;

    if (!map.getSource('tt-flow')) {
      map.addSource('tt-flow', {
        type: 'vector',
        tiles: [FLOW_TILES(k, flowStamp)],
        minzoom: 0,
        maxzoom: 22,
        attribution: '© TomTom Trânsito'
      });
    }
    if (!map.getLayer('tt-flow-line')) {
      map.addLayer(
        {
          id: 'tt-flow-line',
          type: 'line',
          source: 'tt-flow',
          'source-layer': 'Traffic flow',
          // Só onde há lentidão: vias livres (>= 85% da velocidade normal) não ganham cor
          filter: ['<', ['coalesce', ['get', 'traffic_level'], 1], 0.85],
          layout: { 'line-cap': 'round', 'line-join': 'round', visibility: visibility() },
          paint: {
            'line-color': [
              'interpolate', ['linear'], ['coalesce', ['get', 'traffic_level'], 1],
              0.0, '#B3001E',
              0.25, '#FF2D46',
              0.5, '#FF8A1F',
              0.7, '#FACC15',
              0.85, '#FACC15'
            ],
            'line-width': ['interpolate', ['exponential', 1.6], ['zoom'], 10, 1.5, 14, 4, 18, 14],
            'line-opacity': 0.9
          }
        },
        before
      );
    }

    if (!map.getSource('tt-inc')) map.addSource('tt-inc', { type: 'geojson', data: incidents });
    if (!map.getLayer('tt-inc-line')) {
      map.addLayer({
        id: 'tt-inc-line',
        type: 'line',
        source: 'tt-inc',
        filter: ['all', ['==', ['geometry-type'], 'LineString'], ['in', ['get', 'cat'], ['literal', [7, 8]]]],
        layout: { 'line-cap': 'round', visibility: visibility() },
        paint: { 'line-color': '#E0245E', 'line-width': 5, 'line-dasharray': [1.2, 1.2], 'line-opacity': 0.9 }
      });
    }
    if (!map.getLayer('tt-inc-dot')) {
      map.addLayer({
        id: 'tt-inc-dot',
        type: 'circle',
        source: 'tt-inc',
        filter: ['==', ['get', 'marker'], true],
        layout: { visibility: visibility() },
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 5, 16, 9],
          'circle-color': INCIDENT_COLOR,
          'circle-stroke-color': '#FFFFFF',
          'circle-stroke-width': 2,
          'circle-pitch-alignment': 'map'
        }
      });
    }
  }

  function setVisibility() {
    for (const id of ['tt-flow-line', 'tt-inc-line', 'tt-inc-dot']) {
      if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', visibility());
    }
  }

  // Converte a resposta TomTom em GeoJSON: a linha da ocorrência + um ponto (marcador) no início
  function toGeoJSON(json) {
    const features = [];
    for (const inc of json?.incidents || []) {
      const g = inc.geometry;
      const p = inc.properties || {};
      if (!g?.coordinates) continue;
      const props = {
        cat: Number(p.iconCategory) || 0,
        mag: Number(p.magnitudeOfDelay) || 0,
        desc: p.events?.[0]?.description || '',
        from: p.from || '',
        to: p.to || '',
        delay: Number(p.delay) || 0
      };
      features.push({ type: 'Feature', geometry: g, properties: { ...props, marker: g.type === 'Point' } });
      if (g.type === 'LineString' && g.coordinates.length) {
        features.push({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: g.coordinates[0] },
          properties: { ...props, marker: true }
        });
      }
    }
    return { type: 'FeatureCollection', features };
  }

  async function refreshIncidents(force = false) {
    const k = key();
    const pos = getPosition();
    if (!enabled || !k || !pos || inflight) return;
    if (!force && lastFetchPos && Math.abs(lastFetchPos.lat - pos.lat) < 0.03 && Math.abs(lastFetchPos.lon - pos.lon) < 0.03 &&
        Date.now() - lastFetchPos.at < INCIDENT_REFRESH_MS) {
      return;
    }
    const r = INCIDENT_RADIUS_DEG;
    const bbox = [pos.lon - r, pos.lat - r, pos.lon + r, pos.lat + r].map((v) => v.toFixed(4)).join(',');
    const url = `${INCIDENTS_URL}?key=${encodeURIComponent(k)}&bbox=${bbox}` +
      `&fields=${encodeURIComponent(INCIDENT_FIELDS)}&language=pt-PT&timeValidityFilter=present`; // TomTom não aceita pt-BR (400)
    inflight = fetch(url)
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`))))
      .then((json) => {
        incidents = toGeoJSON(json);
        lastFetchPos = { ...pos, at: Date.now() };
        map.getSource('tt-inc')?.setData(incidents);
        onChange?.({ enabled, incidents: incidents.features.filter((f) => f.properties.marker).length });
      })
      .catch((err) => console.warn('[HondaDeck] Trânsito TomTom:', err?.message || err))
      .finally(() => { inflight = null; });
  }

  function refreshFlow() {
    const k = key();
    if (!enabled || !k) return;
    flowStamp = Math.floor(Date.now() / FLOW_REFRESH_MS);
    map.getSource('tt-flow')?.setTiles?.([FLOW_TILES(k, flowStamp)]);
  }

  function startTimers() {
    stopTimers();
    if (!enabled) return;
    incidentTimer = setInterval(() => refreshIncidents(), 30_000); // checa a cada 30 s, busca a cada 3 min ou ao se afastar
    flowTimer = setInterval(refreshFlow, FLOW_REFRESH_MS);
  }
  function stopTimers() {
    clearInterval(incidentTimer);
    clearInterval(flowTimer);
    incidentTimer = flowTimer = null;
  }

  // Ao trocar claro/escuro o estilo é refeito: as camadas de trânsito voltam junto
  map.on('style.load', () => {
    addLayers();
    map.getSource('tt-inc')?.setData(incidents);
  });
  if (map.isStyleLoaded()) addLayers();

  const api = {
    get enabled() { return enabled; },
    get available() { return Boolean(key()); },
    setEnabled(next) {
      enabled = Boolean(next);
      localStorage.setItem(STORAGE_KEY, enabled ? '1' : '0');
      addLayers();
      setVisibility();
      if (enabled) {
        refreshFlow();
        refreshIncidents(true);
      }
      startTimers();
      onChange?.({ enabled, incidents: incidents.features.filter((f) => f.properties.marker).length });
    },
    toggle() { api.setEnabled(!enabled); },
    // Chamado quando a chave chega no estado (primeiro publishState) ou o carro anda
    sync() {
      if (!key()) return;
      addLayers();
      setVisibility();
      if (enabled && !incidentTimer) startTimers();
      refreshIncidents();
    }
  };
  return api;
}
