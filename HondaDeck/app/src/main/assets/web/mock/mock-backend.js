// Mock completo e realista do backend Kotlin para desenvolvimento e testes no PC
// Localização: Anápolis-GO (-16.3281, -48.9534)

function makeCoverDataUri(title, subtitle, c1, c2) {
  const safeT = String(title).slice(0, 16).replace(/[&<>"]/g, '');
  const safeS = String(subtitle).slice(0, 18).replace(/[&<>"]/g, '');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
    <defs>
      <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="${c1}"/>
        <stop offset="100%" stop-color="${c2}"/>
      </linearGradient>
    </defs>
    <rect width="200" height="200" rx="18" fill="url(#g)"/>
    <circle cx="155" cy="45" r="56" fill="rgba(255,255,255,0.08)"/>
    <circle cx="42" cy="162" r="44" fill="rgba(0,0,0,0.22)"/>
    <text x="20" y="138" fill="#FFFFFF" font-family="sans-serif" font-weight="700" font-size="22">${safeT}</text>
    <text x="20" y="166" fill="rgba(255,255,255,0.78)" font-family="sans-serif" font-weight="500" font-size="14">${safeS}</text>
  </svg>`;
  return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
}

const CATALOG = [
  {
    title: 'Tempo Perdido',
    artist: 'Legião Urbana',
    album: 'Dois',
    context: 'Honda Estrada',
    durationMs: 302000,
    uri: 'spotify:track:tempo_perdido',
    coverUrl: makeCoverDataUri('Legião Urbana', 'Honda Estrada', '#0E7490', '#1E1B4B')
  },
  {
    title: 'Midnight City',
    artist: 'M83',
    album: 'Hurry Up, We’re Dreaming',
    context: 'Civic',
    durationMs: 243000,
    uri: 'spotify:track:midnight_city',
    coverUrl: makeCoverDataUri('Midnight City', 'M83 · Civic', '#6D28D9', '#0F172A')
  },
  {
    title: ' O Segundo Sol',
    artist: 'Cássia Eller',
    album: 'Acústico',
    context: 'Cunhads',
    durationMs: 258000,
    uri: 'spotify:track:segundo_sol',
    coverUrl: makeCoverDataUri('Segundo Sol', 'Cássia Eller', '#C2410C', '#1C1917')
  },
  {
    title: 'Yellow',
    artist: 'Coldplay',
    album: 'Parachutes',
    context: 'Honda Estrada',
    durationMs: 269000,
    uri: 'spotify:track:yellow_coldplay',
    coverUrl: makeCoverDataUri('Yellow', 'Coldplay', '#CA8A04', '#0F172A')
  },
  {
    title: 'Mulher de Fases',
    artist: 'Raimundos',
    album: 'Só no Forevis',
    context: 'Cunhads',
    durationMs: 212000,
    uri: 'spotify:track:mulher_fases',
    coverUrl: makeCoverDataUri('Raimundos', 'Cunhads', '#BE123C', '#111827')
  }
];

const ANAPOLIS_PLACES = [
  { name: 'Brasil Park Shopping', address: 'Av. Brasil Norte, 505 - Centro, Anápolis - GO', lat: -16.3194, lon: -48.9522, distanceKm: 1.1 },
  { name: 'Parque Ipiranga', address: 'Av. Profa. Zenaide Campos Roriz - Jundiaí, Anápolis - GO', lat: -16.3342, lon: -48.9416, distanceKm: 1.5 },
  { name: 'Posto Petrobras Av. Brasil', address: 'Av. Brasil Sul, 1420 - Batista, Anápolis - GO', lat: -16.3395, lon: -48.9560, distanceKm: 1.3 },
  { name: 'UniEVANGÉLICA', address: 'Av. Universitária, km 3,5 - Cidade Universitária, Anápolis - GO', lat: -16.2940, lon: -48.9441, distanceKm: 3.9 },
  { name: 'DAIA — Distrito Agroindustrial', address: 'Rod. BR-153, Sul - Anápolis - GO', lat: -16.3950, lon: -48.9475, distanceKm: 7.4 },
  { name: 'Praça Bom Jesus', address: 'Centro, Anápolis - GO', lat: -16.3266, lon: -48.9530, distanceKm: 0.3 }
];

const ROUTE_AV_BRASIL = [
  [-16.3281, -48.9534],
  [-16.3262, -48.9531],
  [-16.3240, -48.9528],
  [-16.3218, -48.9525],
  [-16.3194, -48.9522]
];

export function installMockBackend() {
  let trackIdx = 0;
  let simPhase = 0;

  const state = {
    v: 1,
    accent: '#22D3EE',
    brand: 'HONDA',
    clock: { epochMs: Date.now() },
    weather: {
      available: true,
      tempC: 26,
      maxC: 29,
      minC: 18,
      kind: 'PARTLY_CLOUDY',
      summary: 'Parcialmente nublado',
      city: 'Anápolis'
    },
    status: {
      wifi: true,
      bluetooth: true,
      btName: 'Taramps',
      obd: 'SIMULATED',
      gps: true
    },
    telemetry: {
      speedKmh: 62,
      rpm: 2450,
      gear: '4',
      fuelPct: 68,
      voltage: 14.1,
      coolantC: 89,
      loadPct: 26,
      alerts: []
    },
    media: {
      status: 'CONNECTED',
      playing: true,
      title: CATALOG[0].title.trim(),
      artist: CATALOG[0].artist,
      album: CATALOG[0].album,
      context: CATALOG[0].context,
      positionMs: 84000,
      durationMs: CATALOG[0].durationMs,
      shuffle: false,
      repeat: 'off',
      coverUrl: CATALOG[0].coverUrl,
      liked: true
    },
    spotify: {
      playlists: [
        {
          kind: 'PLAYLIST',
          name: 'Honda Estrada',
          subtitle: '48 faixas · Viagem BR-060',
          uri: 'spotify:playlist:honda_estrada',
          imageUrl: makeCoverDataUri('ESTRADA', 'Honda Civic', '#0891B2', '#0F172A'),
          honda: true
        },
        {
          kind: 'PLAYLIST',
          name: 'Civic',
          subtitle: '36 faixas · Painel Honda',
          uri: 'spotify:playlist:civic_mix',
          imageUrl: makeCoverDataUri('CIVIC', 'Seleção Honda', '#059669', '#090D16'),
          honda: true
        },
        {
          kind: 'PLAYLIST',
          name: 'Cunhads',
          subtitle: '62 faixas · Churrasco & Rock',
          uri: 'spotify:playlist:cunhads',
          imageUrl: makeCoverDataUri('CUNHADS', 'Galera', '#D97706', '#1E1B4B'),
          honda: false
        },
        {
          kind: 'PLAYLIST',
          name: 'Rock Nacional',
          subtitle: '50 faixas',
          uri: 'spotify:playlist:rock_nacional',
          imageUrl: makeCoverDataUri('ROCK BR', 'Clássicos', '#E11D48', '#111827'),
          honda: false
        },
        {
          kind: 'PLAYLIST',
          name: 'Noite Synthwave',
          subtitle: '32 faixas',
          uri: 'spotify:playlist:synthwave',
          imageUrl: makeCoverDataUri('SYNTH', 'Night Drive', '#7C3AED', '#090D16'),
          honda: false
        },
        {
          kind: 'PLAYLIST',
          name: 'Minhas Curtidas',
          subtitle: '214 faixas',
          uri: 'spotify:playlist:liked',
          imageUrl: makeCoverDataUri('CURTIDAS', 'Favoritas', '#2563EB', '#0F172A'),
          honda: false
        }
      ],
      recent: [
        {
          kind: 'TRACK',
          name: 'Midnight City',
          subtitle: 'M83',
          uri: 'spotify:track:midnight_city',
          imageUrl: CATALOG[1].coverUrl
        },
        {
          kind: 'TRACK',
          name: 'Yellow',
          subtitle: 'Coldplay',
          uri: 'spotify:track:yellow_coldplay',
          imageUrl: CATALOG[3].coverUrl
        },
        {
          kind: 'PLAYLIST',
          name: 'Cunhads',
          subtitle: 'Playlist',
          uri: 'spotify:playlist:cunhads',
          imageUrl: makeCoverDataUri('CUNHADS', 'Galera', '#D97706', '#1E1B4B')
        }
      ],
      recentSearches: ['Legião Urbana', 'Coldplay', 'Raimundos', 'M83'],
      search: {
        query: '',
        loading: false,
        error: null,
        tracks: [],
        artists: [],
        albums: [],
        playlists: []
      }
    },
    map: {
      lat: -16.3281,
      lon: -48.9534,
      bearing: 14,
      hasFix: true,
      favorites: [
        {
          id: 'casa',
          name: 'Casa',
          address: 'Bairro Jundiaí, Anápolis - GO',
          lat: -16.3338,
          lon: -48.9435,
          voice: 'ir para casa',
          icon: 'home'
        },
        {
          id: 'trabalho',
          name: 'Trabalho',
          address: 'DAIA - Rod. BR-153, Anápolis - GO',
          lat: -16.3950,
          lon: -48.9475,
          voice: 'ir para o trabalho',
          icon: 'work'
        },
        {
          id: 'posto',
          name: 'Posto Brasil',
          address: 'Av. Brasil Sul, 1420 - Anápolis - GO',
          lat: -16.3395,
          lon: -48.9560,
          voice: 'abastecer',
          icon: 'fuel'
        }
      ],
      recentPlaces: [
        { name: 'Brasil Park Shopping', address: 'Av. Brasil Norte, 505', lat: -16.3194, lon: -48.9522 },
        { name: 'Parque Ipiranga', address: 'Bairro Jundiaí, Anápolis', lat: -16.3342, lon: -48.9416 },
        { name: 'Posto Petrobras Av. Brasil', address: 'Av. Brasil Sul, 1420', lat: -16.3395, lon: -48.9560 },
        { name: 'UniEVANGÉLICA', address: 'Av. Universitária, km 3,5', lat: -16.2940, lon: -48.9441 }
      ],
      search: { query: '', loading: false, results: [] },
      route: {
        active: true,
        loading: false,
        error: null,
        destination: 'Brasil Park Shopping',
        polyline: ROUTE_AV_BRASIL,
        nextInstruction: 'Siga pela Av. Brasil Norte até Brasil Park Shopping',
        nextIcon: 'RIGHT',
        distanceToNextM: 320,
        remainingM: 1200,
        remainingS: 180,
        etaEpochMs: Date.now() + 180000,
        arrived: false
      }
    },
    copilot: {
      listening: false,
      thinking: false,
      online: true,
      model: 'gemini-flash-lite-latest',
      status: 'Aguardando "ok honda"',
      lastUser: '',
      lastReply: '',
      lastAction: ''
    },
    audio: {
      volumePct: 64,
      muted: false,
      route: 'Bluetooth · Taramps'
    },
    notification: null,
    vehicle: {
      obdMessage: 'Simulador OBD-II ativo (Honda Civic 1.8 i-VTEC)',
      simulator: true,
      dtcCodes: [],
      dtcBusy: false,
      tankLiters: 34.0,
      tankPct: 68,
      pids: [
        { label: 'Rotação', value: '2450', unit: 'RPM' },
        { label: 'Velocidade', value: '62', unit: 'km/h' },
        { label: 'Arrefecimento', value: '89', unit: '°C' },
        { label: 'Bateria', value: '14.1', unit: 'V' },
        { label: 'Carga do motor', value: '26', unit: '%' },
        { label: 'Admissão (IAT)', value: '34', unit: '°C' },
        { label: 'Borboleta (TPS)', value: '19', unit: '%' },
        { label: 'Avanço ignição', value: '14.5', unit: '°' }
      ],
      fuelLog: {
        entries: [
          { id: 'f3', date: '2026-11-22', liters: 36.4, price: 6.19, odometer: 128420, full: true },
          { id: 'f2', date: '2026-11-11', liters: 38.0, price: 6.15, odometer: 128012, full: true },
          { id: 'f1', date: '2026-10-30', liters: 35.2, price: 6.09, odometer: 127598, full: true }
        ],
        summary: {
          lastKmL: 11.2,
          avgKmL: 11.0,
          avgPrice: 6.14,
          spent30d: 673.4,
          costPerKm: 0.56
        }
      },
      maintenance: {
        items: []
      }
    },
    settings: {
      accentOptions: ['#22D3EE', '#A3E635', '#FB923C', '#A78BFA', '#F472B6'],
      introMode: 'ANIMATION',
      spotifyShowPlaylists: true,
      spotifyShowRecent: true,
      geminiKeySet: true,
      geminiModel: 'gemini-flash-lite-latest',
      wakeWord: 'ok honda',
      wakeWordEnabled: true,
      obdDevice: '00:1D:A5:68:98:8B',
      pairedDevices: [
        { name: 'OBDII ELM327', address: '00:1D:A5:68:98:8B' },
        { name: 'Taramps Som', address: 'AA:BB:CC:11:22:33' }
      ],
      ignitionDevice: 'AA:BB:CC:11:22:33',
      version: '3.0.0'
    }
  };

  function recomputeFuelSummary() {
    const entries = state.vehicle.fuelLog.entries;
    if (!entries.length) {
      state.vehicle.fuelLog.summary = { lastKmL: 0, avgKmL: 0, avgPrice: 0, spent30d: 0, costPerKm: 0 };
      return;
    }
    const sorted = [...entries].sort((a, b) => (b.odometer || 0) - (a.odometer || 0));
    let totalPrice = 0;
    let priceCount = 0;
    let spent = 0;
    for (const e of sorted) {
      if (e.price > 0) {
        totalPrice += e.price;
        priceCount++;
        spent += e.liters * e.price;
      }
    }
    const avgPrice = priceCount ? +(totalPrice / priceCount).toFixed(2) : 0;
    const kmLs = [];
    for (let i = 0; i < sorted.length - 1; i++) {
      const dist = (sorted[i].odometer || 0) - (sorted[i + 1].odometer || 0);
      if (dist > 0 && sorted[i].liters > 0) {
        kmLs.push(dist / sorted[i].liters);
      }
    }
    const lastKmL = kmLs.length ? +kmLs[0].toFixed(1) : 11.2;
    const avgKmL = kmLs.length ? +(kmLs.reduce((a, b) => a + b, 0) / kmLs.length).toFixed(1) : lastKmL;
    const costPerKm = avgKmL > 0 && avgPrice > 0 ? +(avgPrice / avgKmL).toFixed(2) : 0;
    state.vehicle.fuelLog.summary = {
      lastKmL,
      avgKmL,
      avgPrice,
      spent30d: +spent.toFixed(2),
      costPerKm
    };
  }

  function emitState() {
    state.clock.epochMs = Date.now();
    if (window.HD && typeof window.HD.receive === 'function') {
      window.HD.receive({ type: 'state', payload: structuredClone(state) });
    }
  }

  function emitTelemetry() {
    if (window.HD && typeof window.HD.receive === 'function') {
      window.HD.receive({
        type: 'telemetry',
        payload: {
          speedKmh: state.telemetry.speedKmh,
          rpm: state.telemetry.rpm,
          gear: state.telemetry.gear,
          fuelPct: state.telemetry.fuelPct
        }
      });
    }
  }

  function emitEvent(name, extra = {}) {
    if (window.HD && typeof window.HD.receive === 'function') {
      window.HD.receive({ type: 'event', payload: { name, ...extra } });
    }
  }

  function loadTrack(idx, contextOverride) {
    trackIdx = ((idx % CATALOG.length) + CATALOG.length) % CATALOG.length;
    const t = CATALOG[trackIdx];
    state.media.title = t.title.trim();
    state.media.artist = t.artist;
    state.media.album = t.album;
    state.media.context = contextOverride || t.context;
    state.media.positionMs = 0;
    state.media.durationMs = t.durationMs;
    state.media.coverUrl = t.coverUrl;
    state.media.playing = true;
  }

  function buildRouteTo(name, destLat, destLon) {
    const lat = Number.isFinite(Number(destLat)) && Number(destLat) !== 0
      ? Number(destLat)
      : state.map.lat + 0.012;
    const lon = Number.isFinite(Number(destLon)) && Number(destLon) !== 0
      ? Number(destLon)
      : state.map.lon + 0.008;
    const sLat = state.map.lat;
    const sLon = state.map.lon;
    const mid1 = [+(sLat + (lat - sLat) * 0.35).toFixed(5), +(sLon + (lon - sLon) * 0.2).toFixed(5)];
    const mid2 = [+(sLat + (lat - sLat) * 0.72).toFixed(5), +(sLon + (lon - sLon) * 0.78).toFixed(5)];
    const distKm = Math.max(0.6, Math.hypot(lat - sLat, lon - sLon) * 111);
    const remM = Math.round(distKm * 1000);
    const remS = Math.max(90, Math.round((distKm / 42) * 3600));
    state.map.route = {
      active: true,
      loading: false,
      error: null,
      destination: name,
      polyline: [[sLat, sLon], mid1, mid2, [lat, lon]],
      nextInstruction: `Siga pela Av. Brasil em direção a ${name}`,
      nextIcon: lon >= sLon ? 'RIGHT' : 'LEFT',
      distanceToNextM: Math.min(450, Math.round(remM * 0.28)),
      remainingM: remM,
      remainingS: remS,
      etaEpochMs: Date.now() + remS * 1000,
      arrived: false
    };
    const existingIdx = state.map.recentPlaces.findIndex((p) => p.name.toLowerCase() === String(name).toLowerCase());
    if (existingIdx >= 0) state.map.recentPlaces.splice(existingIdx, 1);
    state.map.recentPlaces.unshift({ name, address: 'Anápolis - GO', lat, lon });
    state.map.recentPlaces = state.map.recentPlaces.slice(0, 8);
  }

  let listenTimer = null;

  function answerCopilot(question) {
    const q = String(question || '').trim();
    const low = q.toLowerCase();
    state.copilot.listening = false;
    state.copilot.thinking = true;
    state.copilot.lastUser = q;
    emitState();

    setTimeout(() => {
      state.copilot.thinking = false;
      let reply = `Tudo certo no Civic: motor a ${state.telemetry.coolantC}°C, bateria em ${state.telemetry.voltage.toFixed(1)}V e ${state.telemetry.fuelPct}% de combustível. Clima em Anápolis: ${state.weather.tempC}°C.`;
      if (low.includes('casa')) {
        const casa = state.map.favorites.find((f) => f.id === 'casa') || state.map.favorites[0];
        if (casa) buildRouteTo(casa.name, casa.lat, casa.lon);
        reply = 'Rota traçada para Casa pelo caminho mais rápido em Anápolis.';
        emitEvent('openMode', { mode: 'map' });
      } else if (low.includes('playlist') || low.includes('tocar') || low.includes('música') || low.includes('musica')) {
        loadTrack(1, 'Honda Estrada');
        reply = `Tocando ${state.media.title} de ${state.media.artist} na playlist Honda Estrada.`;
        emitEvent('openMode', { mode: 'music' });
      } else if (low.includes('temperatura') || low.includes('motor')) {
        reply = `Temperatura do motor em ${state.telemetry.coolantC}°C (normal), bateria em ${state.telemetry.voltage.toFixed(1)}V e uso do motor em ${state.telemetry.loadPct}%.`;
      } else if (low.includes('tempo') || low.includes('clima')) {
        reply = `Em Anápolis faz ${state.weather.tempC}°C (${state.weather.summary}), com máxima de ${state.weather.maxC}° e mínima de ${state.weather.minC}°.`;
      }
      state.copilot.lastReply = reply;
      emitState();
      emitEvent('agentReply', { text: reply });
    }, 550);
  }

  window.HondaBridge = {
    send(rawJson) {
      let parsed;
      try {
        parsed = JSON.parse(rawJson);
      } catch {
        return;
      }
      const { cmd, args = {} } = parsed || {};
      switch (cmd) {
        case 'ui.ready':
          setTimeout(emitState, 20);
          break;
        case 'ui.setAccent':
          if (args.color) {
            state.accent = String(args.color);
            emitState();
          }
          break;
        case 'ui.mode':
          break;
        case 'audio.setVolume':
          state.audio.volumePct = Math.max(0, Math.min(100, Math.round(Number(args.pct ?? 60))));
          if (state.audio.volumePct > 0) state.audio.muted = false;
          emitState();
          break;
        case 'audio.toggleMute':
          state.audio.muted = !state.audio.muted;
          emitState();
          break;
        case 'music.toggle':
          state.media.playing = !state.media.playing;
          emitState();
          break;
        case 'music.next':
          loadTrack(trackIdx + 1);
          emitState();
          break;
        case 'music.prev':
          loadTrack(trackIdx - 1);
          emitState();
          break;
        case 'music.shuffle':
          state.media.shuffle = !state.media.shuffle;
          emitState();
          break;
        case 'music.repeat': {
          const order = ['off', 'all', 'one'];
          const idx = order.indexOf(state.media.repeat);
          state.media.repeat = order[(idx + 1) % order.length];
          emitState();
          break;
        }
        case 'music.like':
          state.media.liked = !state.media.liked;
          emitState();
          emitEvent('toast', { text: state.media.liked ? 'Adicionada às Curtidas' : 'Removida das Curtidas' });
          break;
        case 'music.seek':
          state.media.positionMs = Math.max(0, Math.min(state.media.durationMs || 300000, Number(args.ms || 0)));
          emitState();
          break;
        case 'music.playUri': {
          const uri = String(args.uri || '');
          const foundIdx = CATALOG.findIndex((c) => c.uri === uri);
          const pl = state.spotify.playlists.find((p) => p.uri === uri);
          if (foundIdx >= 0) {
            loadTrack(foundIdx);
          } else if (pl) {
            loadTrack((trackIdx + 1) % CATALOG.length, pl.name);
          } else {
            loadTrack(0, 'Busca Spotify');
          }
          emitState();
          emitEvent('toast', { text: `Reproduzindo: ${state.media.title}` });
          break;
        }
        case 'music.search': {
          const q = String(args.query || '').trim();
          state.spotify.search.query = q;
          if (!q) {
            state.spotify.search = { query: '', loading: false, error: null, tracks: [], artists: [], albums: [], playlists: [] };
            emitState();
            break;
          }
          if (!state.spotify.recentSearches.includes(q)) {
            state.spotify.recentSearches = [q, ...state.spotify.recentSearches].slice(0, 8);
          }
          const ql = q.toLowerCase();
          const matchedTracks = CATALOG.filter(
            (t) => t.title.toLowerCase().includes(ql) || t.artist.toLowerCase().includes(ql)
          );
          const tracksOut = (matchedTracks.length ? matchedTracks : [
            {
              title: q,
              artist: 'Artista no Spotify',
              uri: `spotify:track:search_${encodeURIComponent(ql)}`,
              coverUrl: makeCoverDataUri(q, 'Faixa', '#0284C7', '#0F172A')
            }
          ]).map((t) => ({
            kind: 'TRACK',
            name: t.title.trim(),
            subtitle: t.artist,
            uri: t.uri,
            imageUrl: t.coverUrl
          }));

          state.spotify.search = {
            query: q,
            loading: false,
            error: null,
            tracks: tracksOut,
            artists: [
              {
                kind: 'ARTIST',
                name: matchedTracks[0]?.artist || q,
                subtitle: 'Artista',
                uri: `spotify:artist:${encodeURIComponent(ql)}`,
                imageUrl: makeCoverDataUri(q, 'Artista', '#7C3AED', '#0F172A')
              }
            ],
            albums: [
              {
                kind: 'ALBUM',
                name: matchedTracks[0]?.album || `${q} (Ao Vivo)`,
                subtitle: matchedTracks[0]?.artist || 'Álbum',
                uri: `spotify:album:${encodeURIComponent(ql)}`,
                imageUrl: makeCoverDataUri(q, 'Álbum', '#0D9488', '#0F172A')
              }
            ],
            playlists: state.spotify.playlists
              .filter((p) => p.name.toLowerCase().includes(ql))
              .concat([
                {
                  kind: 'PLAYLIST',
                  name: `Mix ${q}`,
                  subtitle: 'Playlist Spotify',
                  uri: `spotify:playlist:${encodeURIComponent(ql)}`,
                  imageUrl: makeCoverDataUri(q, 'Mix', '#EA580C', '#0F172A')
                }
              ])
              .slice(0, 3)
          };
          emitState();
          break;
        }
        case 'music.authorize':
          state.media.status = 'CONNECTED';
          emitState();
          emitEvent('toast', { text: 'Spotify conectado com sucesso' });
          break;
        case 'music.openApp':
          emitEvent('toast', { text: 'Abrindo aplicativo Spotify…' });
          break;
        case 'map.search': {
          const q = String(args.query || '').trim();
          state.map.search.query = q;
          if (!q) {
            state.map.search.results = [];
            state.map.search.loading = false;
            emitState();
            break;
          }
          const ql = q.toLowerCase();
          const hits = ANAPOLIS_PLACES.filter(
            (p) => p.name.toLowerCase().includes(ql) || p.address.toLowerCase().includes(ql) || (ql.includes('posto') && p.name.toLowerCase().includes('posto'))
          );
          state.map.search.loading = false;
          state.map.search.results = hits.length
            ? hits
            : [{ name: `${q} (Anápolis)`, address: 'Centro, Anápolis - GO', lat: -16.3235, lon: -48.9505, distanceKm: 1.8 }];
          emitState();
          break;
        }
        case 'map.route':
          if (args.name) {
            buildRouteTo(String(args.name), args.lat, args.lon);
            emitState();
            emitEvent('toast', { text: `Rota iniciada para ${args.name}` });
          }
          break;
        case 'map.stopRoute':
          state.map.route.active = false;
          state.map.route.polyline = [];
          state.map.route.destination = '';
          emitState();
          emitEvent('toast', { text: 'Rota encerrada' });
          break;
        case 'map.openExternal':
          emitEvent('toast', { text: `Abrindo Google Maps: ${args.name || 'Destino'}` });
          break;
        case 'fav.save': {
          const id = args.id || `fav_${Date.now()}`;
          const item = {
            id,
            name: String(args.name || 'Favorito').trim(),
            address: String(args.address || 'Anápolis - GO').trim(),
            lat: Number(args.lat || state.map.lat),
            lon: Number(args.lon || state.map.lon),
            voice: String(args.voice || `ir para ${String(args.name || 'favorito').toLowerCase()}`).trim(),
            icon: String(args.icon || 'star')
          };
          const idx = state.map.favorites.findIndex((f) => f.id === id);
          if (idx >= 0) state.map.favorites[idx] = item;
          else state.map.favorites.push(item);
          emitState();
          emitEvent('toast', { text: `Favorito "${item.name}" salvo` });
          break;
        }
        case 'fav.delete':
          state.map.favorites = state.map.favorites.filter((f) => f.id !== args.id);
          emitState();
          emitEvent('toast', { text: 'Favorito removido' });
          break;
        case 'fav.useCurrentLocation': {
          const name = String(args.name || 'Posição atual').trim();
          const item = {
            id: `fav_${Date.now()}`,
            name,
            address: `${state.map.lat.toFixed(4)}, ${state.map.lon.toFixed(4)} · Anápolis - GO`,
            lat: state.map.lat,
            lon: state.map.lon,
            voice: String(args.voice || `ir para ${name.toLowerCase()}`).trim(),
            icon: String(args.icon || 'star')
          };
          state.map.favorites.push(item);
          emitState();
          emitEvent('toast', { text: `Local atual salvo como "${name}"` });
          break;
        }
        case 'copilot.listen':
          clearTimeout(listenTimer);
          state.copilot.listening = true;
          state.copilot.thinking = false;
          emitState();
          listenTimer = setTimeout(() => {
            if (state.copilot.listening) {
              answerCopilot('Como está o motor e o clima hoje?');
            }
          }, 4200);
          break;
        case 'copilot.cancel':
          clearTimeout(listenTimer);
          state.copilot.listening = false;
          state.copilot.thinking = false;
          emitState();
          break;
        case 'copilot.ask':
          clearTimeout(listenTimer);
          if (args.text) answerCopilot(String(args.text));
          break;
        case 'notif.read':
          emitEvent('toast', { text: 'Lendo mensagem em voz alta…' });
          state.notification = null;
          emitState();
          break;
        case 'notif.dismiss':
          state.notification = null;
          emitState();
          break;
        case 'obd.connect':
          state.status.obd = state.vehicle.simulator ? 'SIMULATED' : 'CONNECTED';
          state.vehicle.obdMessage = 'Conectado ao adaptador OBD-II';
          emitState();
          emitEvent('toast', { text: 'OBD-II conectado' });
          break;
        case 'obd.disconnect':
          state.status.obd = 'DISCONNECTED';
          state.vehicle.simulator = false;
          state.vehicle.obdMessage = 'Desconectado';
          emitState();
          emitEvent('toast', { text: 'OBD-II desconectado' });
          break;
        case 'obd.readDtc':
          state.vehicle.dtcBusy = true;
          emitState();
          setTimeout(() => {
            state.vehicle.dtcBusy = false;
            state.vehicle.dtcCodes = [
              { code: 'P0420', desc: 'Eficiência do sistema catalisador abaixo do limite (Banco 1)' }
            ];
            emitState();
            emitEvent('toast', { text: 'Leitura da ECU concluída (1 código)' });
          }, 450);
          break;
        case 'obd.clearDtc':
          state.vehicle.dtcCodes = [];
          emitState();
          emitEvent('toast', { text: 'Códigos de falha limpos da ECU' });
          break;
        case 'obd.setSimulator':
          state.vehicle.simulator = Boolean(args.enabled);
          state.status.obd = state.vehicle.simulator ? 'SIMULATED' : 'CONNECTED';
          state.vehicle.obdMessage = state.vehicle.simulator
            ? 'Simulador OBD-II ativo (Honda Civic 1.8 i-VTEC)'
            : 'Conectado ao ELM327 Bluetooth';
          emitState();
          break;
        case 'obd.selectDevice':
          state.settings.obdDevice = String(args.address || '');
          emitState();
          emitEvent('toast', { text: 'Adaptador OBD selecionado' });
          break;
        case 'fuel.log': {
          const liters = Math.max(0.1, Number(args.liters || 0));
          const entry = {
            id: `f_${Date.now()}`,
            date: new Date().toISOString().slice(0, 10),
            liters,
            price: args.price ? Number(args.price) : 6.19,
            odometer: args.odometer ? Number(args.odometer) : 128650,
            full: Boolean(args.full)
          };
          state.vehicle.fuelLog.entries.unshift(entry);
          if (entry.full) {
            state.vehicle.tankLiters = 50;
            state.vehicle.tankPct = 100;
            state.telemetry.fuelPct = 100;
          } else {
            state.vehicle.tankLiters = Math.min(50, +(state.vehicle.tankLiters + liters).toFixed(1));
            state.vehicle.tankPct = Math.round((state.vehicle.tankLiters / 50) * 100);
            state.telemetry.fuelPct = state.vehicle.tankPct;
          }
          recomputeFuelSummary();
          emitState();
          emitEvent('toast', { text: 'Abastecimento registrado' });
          break;
        }
        case 'fuel.delete':
          state.vehicle.fuelLog.entries = state.vehicle.fuelLog.entries.filter((e) => e.id !== args.id);
          recomputeFuelSummary();
          emitState();
          emitEvent('toast', { text: 'Registro excluído' });
          break;
        case 'fuel.tank': {
          const act = args.action;
          if (act === 'full') state.vehicle.tankLiters = 50;
          else if (act === 'plus20') state.vehicle.tankLiters = Math.min(50, +(state.vehicle.tankLiters + 20).toFixed(1));
          else if (act === 'plus10') state.vehicle.tankLiters = Math.min(50, +(state.vehicle.tankLiters + 10).toFixed(1));
          state.vehicle.tankPct = Math.round((state.vehicle.tankLiters / 50) * 100);
          state.telemetry.fuelPct = state.vehicle.tankPct;
          emitState();
          emitEvent('toast', { text: `Tanque atualizado: ${state.vehicle.tankPct}%` });
          break;
        }
        case 'settings.set': {
          const { key, value } = args;
          if (key === 'geminiKey') {
            state.settings.geminiKeySet = Boolean(String(value || '').trim());
            emitEvent('toast', { text: 'Chave Gemini atualizada' });
          } else if (key in state.settings) {
            state.settings[key] = value;
            if (key === 'geminiModel') state.copilot.model = String(value);
          }
          emitState();
          break;
        }
        case 'system.downloadOfflineMap':
          emitEvent('toast', { text: 'Baixando tiles offline de Anápolis (raio 12 km)…' });
          break;
        case 'camera.toggle':
          emitEvent('toast', { text: 'Alternando câmera de ré (overlay nativo)' });
          break;
      }
    }
  };

  // Telemetria realista a 10 Hz (0–120 km/h coerente com marchas N-1-2-3-4-5 e RPM)
  setInterval(() => {
    if (state.status.obd === 'DISCONNECTED') {
      state.telemetry.speedKmh = 0;
      state.telemetry.rpm = 0;
      state.telemetry.gear = 'N';
      emitTelemetry();
      return;
    }
    simPhase += 0.035;
    // Curva suave 0..116 km/h incluindo trechos de parada breve
    const wave = Math.sin(simPhase * 0.55) * 0.5 + 0.5;
    const rawSpeed = Math.max(0, Math.round((wave ** 1.15) * 124 - 6));
    const speed = Math.min(120, rawSpeed);

    let gear = 'N';
    let rpm = 820;
    if (speed > 0) {
      const bands = [0, 22, 42, 66, 92, 160];
      let gi = 0;
      while (gi < 4 && speed >= bands[gi + 1]) gi++;
      gear = String(gi + 1);
      const frac = (speed - bands[gi]) / (bands[gi + 1] - bands[gi]);
      rpm = Math.round(1850 + frac * 4350);
    }

    state.telemetry.speedKmh = speed;
    state.telemetry.rpm = rpm;
    state.telemetry.gear = gear;
    if (state.media.playing && state.media.durationMs > 0) {
      state.media.positionMs = (state.media.positionMs + 100) % state.media.durationMs;
    }
    emitTelemetry();
  }, 100);

  // Atualização periódica leve de PIDs ao vivo a cada 2,5 s
  setInterval(() => {
    state.vehicle.pids[0].value = String(state.telemetry.rpm);
    state.vehicle.pids[1].value = String(state.telemetry.speedKmh);
    state.telemetry.loadPct = Math.min(78, Math.max(14, Math.round(14 + (state.telemetry.rpm / 7000) * 58)));
    state.vehicle.pids[4].value = String(state.telemetry.loadPct);
    emitState();
  }, 2500);
}
