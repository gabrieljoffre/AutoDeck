// Estilo vetorial do mapa (MapLibre GL + OpenFreeMap / OpenMapTiles), sem chave e gratuito.
// Visual "cockpit": fundo escuro, ruas em azul-aço, prédios 3D extrudados e rota neon na cor de destaque.

export const OFM_TILEJSON = 'https://tiles.openfreemap.org/planet';
export const OFM_GLYPHS = 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf';
export const OFM_ATTRIBUTION =
  '<a href="https://openfreemap.org" target="_blank" rel="noopener">OpenFreeMap</a> © <a href="https://www.openmaptiles.org/" target="_blank" rel="noopener">OpenMapTiles</a> Dados do <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>';

const FONT = ['Noto Sans Regular'];

const PALETTES = {
  dark: {
    background: '#080D16',
    water: '#0B1C31',
    waterLine: '#15304D',
    park: '#0C1D1A',
    landuse: '#0A111C',
    minor: '#223047',
    secondary: '#2D405C',
    primary: '#3A5375',
    motorway: '#4C6C94',
    building: '#16233A',
    buildingTop: '#26406A',
    label: '#A9BBD3',
    labelStrong: '#DCE6F4',
    halo: '#080D16'
  },
  // Dia: tons médios (não branco estourado) para combinar com os cards escuros e manter as ruas legíveis
  light: {
    background: '#BCC6D2',
    water: '#7FA6CC',
    waterLine: '#6D96BF',
    park: '#A9C6B2',
    landuse: '#C3CCD7',
    minor: '#EEF2F6',
    secondary: '#FFFFFF',
    primary: '#FFE09A',
    motorway: '#FFC266',
    building: '#9EAABA',
    buildingTop: '#8794A7',
    label: '#2C3746',
    labelStrong: '#16202C',
    halo: '#E4E9EF'
  }
};

const EMPTY_FC = { type: 'FeatureCollection', features: [] };

// Largura das ruas cresce com o zoom (padrão de mapas de navegação)
function roadWidth(base) {
  return ['interpolate', ['exponential', 1.6], ['zoom'], 10, base * 0.25, 14, base, 18, base * 5];
}

export function buildMapStyle(theme, accent, routeData = EMPTY_FC, destData = EMPTY_FC) {
  const p = PALETTES[theme === 'light' ? 'light' : 'dark'];
  const isDark = theme !== 'light';

  return {
    version: 8,
    name: 'HondaDeck Drone',
    glyphs: OFM_GLYPHS,
    sources: {
      openmaptiles: { type: 'vector', url: OFM_TILEJSON, attribution: OFM_ATTRIBUTION },
      'hd-route': { type: 'geojson', data: routeData },
      'hd-dest': { type: 'geojson', data: destData }
    },
    layers: [
      { id: 'background', type: 'background', paint: { 'background-color': p.background } },
      {
        id: 'landuse',
        type: 'fill',
        source: 'openmaptiles',
        'source-layer': 'landuse',
        paint: { 'fill-color': p.landuse, 'fill-opacity': 0.7 }
      },
      {
        id: 'park',
        type: 'fill',
        source: 'openmaptiles',
        'source-layer': 'park',
        paint: { 'fill-color': p.park, 'fill-opacity': 0.9 }
      },
      {
        id: 'landcover',
        type: 'fill',
        source: 'openmaptiles',
        'source-layer': 'landcover',
        filter: ['in', ['get', 'class'], ['literal', ['grass', 'wood', 'farmland']]],
        paint: { 'fill-color': p.park, 'fill-opacity': 0.55 }
      },
      {
        id: 'water',
        type: 'fill',
        source: 'openmaptiles',
        'source-layer': 'water',
        paint: { 'fill-color': p.water }
      },
      {
        id: 'waterway',
        type: 'line',
        source: 'openmaptiles',
        'source-layer': 'waterway',
        paint: { 'line-color': p.waterLine, 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 0.5, 16, 3] }
      },
      {
        id: 'road-minor',
        type: 'line',
        source: 'openmaptiles',
        'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', ['minor', 'service', 'track']]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': p.minor, 'line-width': roadWidth(1.6) }
      },
      {
        id: 'road-secondary',
        type: 'line',
        source: 'openmaptiles',
        'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', ['secondary', 'tertiary']]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': p.secondary, 'line-width': roadWidth(2.4) }
      },
      {
        id: 'road-primary',
        type: 'line',
        source: 'openmaptiles',
        'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', ['primary', 'trunk']]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': p.primary, 'line-width': roadWidth(3.2) }
      },
      {
        id: 'road-motorway',
        type: 'line',
        source: 'openmaptiles',
        'source-layer': 'transportation',
        filter: ['==', ['get', 'class'], 'motorway'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': p.motorway, 'line-width': roadWidth(4) }
      },
      // Prédios 3D (efeito vidro escuro)
      {
        id: 'building-3d',
        type: 'fill-extrusion',
        source: 'openmaptiles',
        'source-layer': 'building',
        minzoom: 14,
        paint: {
          'fill-extrusion-color': ['interpolate', ['linear'], ['coalesce', ['get', 'render_height'], 6], 0, p.building, 40, p.buildingTop],
          'fill-extrusion-height': ['interpolate', ['linear'], ['zoom'], 14, 0, 15.2, ['coalesce', ['get', 'render_height'], 6]],
          'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0],
          'fill-extrusion-opacity': isDark ? 0.82 : 0.9,
          'fill-extrusion-vertical-gradient': true
        }
      },
      // Rota neon por cima dos prédios: brilho largo + linha principal + núcleo claro
      {
        id: 'hd-route-glow',
        type: 'line',
        source: 'hd-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': accent, 'line-opacity': 0.32, 'line-blur': 6, 'line-width': roadWidth(9) }
      },
      {
        id: 'hd-route-main',
        type: 'line',
        source: 'hd-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': accent, 'line-width': roadWidth(3.2) }
      },
      {
        id: 'hd-route-core',
        type: 'line',
        source: 'hd-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#E8FBFF', 'line-opacity': 0.55, 'line-width': roadWidth(0.8) }
      },
      {
        id: 'hd-dest',
        type: 'circle',
        source: 'hd-dest',
        paint: {
          'circle-radius': 8,
          'circle-color': p.background,
          'circle-stroke-color': accent,
          'circle-stroke-width': 3,
          'circle-pitch-alignment': 'map'
        }
      },
      {
        id: 'road-label',
        type: 'symbol',
        source: 'openmaptiles',
        'source-layer': 'transportation_name',
        minzoom: 14,
        layout: {
          'symbol-placement': 'line',
          'text-field': ['coalesce', ['get', 'name:pt'], ['get', 'name']],
          'text-font': FONT,
          'text-size': ['interpolate', ['linear'], ['zoom'], 14, 11, 18, 14],
          'text-max-angle': 30,
          'symbol-spacing': 320
        },
        paint: { 'text-color': p.label, 'text-halo-color': p.halo, 'text-halo-width': 1.4 }
      },
      {
        id: 'place-label',
        type: 'symbol',
        source: 'openmaptiles',
        'source-layer': 'place',
        // Só com o mapa afastado; e o map.js esconde quando a câmera está inclinada (horizonte = barra superior)
        maxzoom: 15.2,
        filter: ['in', ['get', 'class'], ['literal', ['city', 'town', 'suburb', 'neighbourhood']]],
        layout: {
          'text-field': ['coalesce', ['get', 'name:pt'], ['get', 'name']],
          'text-font': FONT,
          'text-size': ['match', ['get', 'class'], 'city', 16, 'town', 14, 12],
          'text-transform': 'uppercase',
          'text-letter-spacing': 0.12,
          'text-max-width': 8
        },
        paint: { 'text-color': p.labelStrong, 'text-halo-color': p.halo, 'text-halo-width': 1.6, 'text-opacity': 0.85 }
      }
    ]
  };
}

// Mapa offline de Goiás + DF (arquivo PMTiles no tablet, esquema Protomaps Basemap v4).
// Mesmo visual e mesmos ids de camada do estilo online; só troca a fonte e os filtros.
const OFFLINE_LAYERS = {
  landuse: { sl: 'landuse', filter: ['in', ['get', 'kind'], ['literal', ['residential', 'commercial', 'industrial', 'retail', 'school', 'university', 'hospital', 'military', 'railway', 'parking']]] },
  park: { sl: 'landuse', filter: ['in', ['get', 'kind'], ['literal', ['park', 'nature_reserve', 'golf_course', 'cemetery', 'garden', 'playground', 'pitch', 'protected_area', 'national_park']]] },
  landcover: { sl: 'landuse', filter: ['in', ['get', 'kind'], ['literal', ['wood', 'forest', 'farmland', 'meadow', 'grassland', 'grass', 'scrub']]] },
  water: { sl: 'water', filter: ['==', ['geometry-type'], 'Polygon'] },
  waterway: { sl: 'water', filter: ['==', ['geometry-type'], 'LineString'] },
  'road-minor': { sl: 'roads', filter: ['in', ['get', 'kind'], ['literal', ['minor_road', 'other']]] },
  'road-secondary': { sl: 'roads', filter: ['==', ['get', 'kind'], 'medium_road'] },
  'road-primary': { sl: 'roads', filter: ['==', ['get', 'kind'], 'major_road'] },
  'road-motorway': { sl: 'roads', filter: ['==', ['get', 'kind'], 'highway'] },
  'building-3d': { sl: 'buildings' },
  'road-label': { sl: 'roads', filter: ['has', 'name'] },
  'place-label': { sl: 'places', filter: ['in', ['get', 'kind'], ['literal', ['locality', 'neighbourhood', 'macrohood']]] }
};

/**
 * Converte o estilo online (OpenFreeMap) para o arquivo offline.
 * offline = { tiles: '<origem>/offline/{z}/{x}/{y}.pbf', maxzoom, bounds: [minLon, minLat, maxLon, maxLat] }
 */
export function toOfflineStyle(style, offline) {
  const s = JSON.parse(JSON.stringify(style));
  s.sources = {
    offline: {
      type: 'vector',
      tiles: [offline.tiles],
      minzoom: 0,
      maxzoom: offline.maxzoom || 15,
      bounds: offline.bounds,
      attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> · Protomaps'
    },
    'hd-route': s.sources['hd-route'],
    'hd-dest': s.sources['hd-dest']
  };
  s.layers = s.layers.map((l) => {
    if (l.source !== 'openmaptiles') return l;
    const m = OFFLINE_LAYERS[l.id];
    if (!m) return null;
    const out = { ...l, source: 'offline', 'source-layer': m.sl };
    if (m.filter) out.filter = m.filter;
    else delete out.filter;
    if (l.id === 'building-3d') {
      out.paint = {
        ...l.paint,
        'fill-extrusion-color': ['interpolate', ['linear'], ['coalesce', ['get', 'height'], 6], 0, l.paint['fill-extrusion-color'][4], 40, l.paint['fill-extrusion-color'][6]],
        'fill-extrusion-height': ['interpolate', ['linear'], ['zoom'], 14, 0, 15.2, ['coalesce', ['get', 'height'], 6]],
        'fill-extrusion-base': ['coalesce', ['get', 'min_height'], 0]
      };
    }
    if (l.id === 'place-label') {
      out.layout = { ...l.layout, 'text-size': ['match', ['get', 'kind'], 'locality', 16, 12] };
    }
    return out;
  }).filter(Boolean);
  return s;
}

export function routeToGeoJSON(points) {
  if (!Array.isArray(points) || points.length < 2) return EMPTY_FC;
  return {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        properties: {},
        geometry: { type: 'LineString', coordinates: points.map(([lat, lon]) => [Number(lon), Number(lat)]) }
      }
    ]
  };
}

export function destToGeoJSON(points) {
  if (!Array.isArray(points) || points.length < 2) return EMPTY_FC;
  const [lat, lon] = points[points.length - 1];
  return {
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [Number(lon), Number(lat)] } }]
  };
}

export function webglAvailable() {
  try {
    const c = document.createElement('canvas');
    return Boolean(c.getContext('webgl2') || c.getContext('webgl'));
  } catch {
    return false;
  }
}
