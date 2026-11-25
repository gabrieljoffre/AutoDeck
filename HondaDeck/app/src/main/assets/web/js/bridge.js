// Ponte única JS <-> Kotlin conforme .agy/contrato-ponte.md
// Se window.HondaBridge não existir, carrega automaticamente ../mock/mock-backend.js

const VALID_COMMANDS = new Set([
  'ui.ready',
  'ui.setAccent',
  'ui.mode',
  'audio.setVolume',
  'audio.toggleMute',
  'music.toggle',
  'music.next',
  'music.prev',
  'music.shuffle',
  'music.repeat',
  'music.like',
  'music.seek',
  'music.playUri',
  'music.search',
  'music.authorize',
  'music.openApp',
  'map.search',
  'map.route',
  'map.stopRoute',
  'map.openExternal',
  'apps.open',
  'copilot.openGeminiApp',
  'fav.save',
  'fav.delete',
  'fav.useCurrentLocation',
  'copilot.listen',
  'copilot.cancel',
  'copilot.ask',
  'copilot.live.start',
  'copilot.live.stop',
  'copilot.ask.voice',
  'copilot.clearMemory',
  'notif.read',
  'notif.dismiss',
  'phone.pair',
  'phone.unpair',
  'phone.discover',
  'phone.reconnect',
  'phone.syncContacts',
  'phone.call.answer',
  'phone.call.end',
  'phone.voice.cancel',
  'obd.connect',
  'obd.disconnect',
  'obd.readDtc',
  'obd.clearDtc',
  'obd.setSimulator',
  'obd.selectDevice',
  'fuel.log',
  'fuel.delete',
  'fuel.tank',
  'settings.set',
  'system.downloadOfflineMap',
  'camera.toggle',
  'calendar.refresh',
  'calendar.requestPermission'
]);

export const DEFAULT_STATE = {
  v: 1,
  accent: '#22D3EE',
  brand: 'HONDA',
  clock: { epochMs: 0 },
  weather: {
    available: false,
    tempC: 0,
    maxC: 0,
    minC: 0,
    kind: 'PARTLY_CLOUDY',
    summary: '',
    city: 'Anápolis'
  },
  calendar: {
    permissionGranted: false,
    updatedAtMs: 0,
    statusText: '',
    events: [],
    holidays: []
  },
  status: {
    wifi: false,
    bluetooth: false,
    btName: '',
    obd: 'DISCONNECTED',
    gps: false,
    phone: false,
    phoneState: 'DISCONNECTED',
    phoneName: ''
  },
  telemetry: {
    speedKmh: 0,
    rpm: 0,
    gear: 'N',
    fuelPct: 0,
    voltage: 0,
    coolantC: 0,
    loadPct: 0,
    alerts: []
  },
  media: {
    status: 'NOT_CONFIGURED',
    playing: false,
    title: '',
    artist: '',
    album: '',
    context: '',
    positionMs: 0,
    durationMs: 0,
    shuffle: false,
    repeat: 'off',
    coverUrl: '',
    liked: false
  },
  spotify: {
    playlists: [],
    recent: [],
    recentSearches: [],
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
    provider: 'esri',
    jsKey: '',
    lat: -16.3281,
    lon: -48.9534,
    bearing: 0,
    hasFix: false,
    favorites: [],
    recentPlaces: [],
    search: { query: '', loading: false, results: [] },
    route: {
      active: false,
      loading: false,
      error: null,
      destination: '',
      polyline: [],
      nextInstruction: '',
      nextIcon: 'STRAIGHT',
      distanceToNextM: 0,
      remainingM: 0,
      remainingS: 0,
      etaEpochMs: 0,
      arrived: false
    }
  },
  copilot: {
    listening: false,
    thinking: false,
    speaking: false,
    online: false,
    model: 'gemini-flash-lite-latest',
    liveModel: 'gemini-2.5-flash-native-audio-latest',
    mode: 'idle',
    phase: 'idle',
    level: 0,
    userText: '',
    replyText: '',
    status: 'Pronto. Toque em Conversar ou Peça ao Gemini.',
    lastUser: '',
    lastReply: '',
    lastAction: ''
  },
  audio: {
    volumePct: 60,
    muted: false,
    route: 'Alto-falante'
  },
  notification: null,
  phone: {
    connected: false,
    state: 'DISCONNECTED',
    paired: false,
    phoneName: '',
    host: '',
    port: 5290,
    locked: false,
    accessibilityEnabled: false,
    notificationsEnabled: false,
    statusMessage: 'Celular desconectado',
    lastError: null,
    recordingVoiceNote: false,
    voiceNoteStatus: '',
    contactsCount: 0,
    contactsSyncedAtMs: 0,
    contactsSyncing: false,
    contactsSyncError: null,
    iconsVersion: 0,
    iconPackages: [],
    roles: {},
    discovered: [],
    call: {
      status: 'IDLE',
      number: '',
      displayName: '',
      isWhatsApp: false,
      startedAtMs: 0
    },
    messages: []
  },
  apps: {
    preferredMapPackage: 'com.google.android.apps.maps',
    preferredMusicPackage: 'com.spotify.music',
    tablet: [],
    mapApps: [],
    musicApps: []
  },
  vehicle: {
    obdMessage: '',
    simulator: false,
    dtcCodes: [],
    dtcBusy: false,
    tankLiters: 0,
    tankPct: 0,
    pids: [],
    fuelLog: {
      entries: [],
      summary: { lastKmL: 0, avgKmL: 0, avgPrice: 0, spent30d: 0, costPerKm: 0 }
    },
    maintenance: { items: [] }
  },
  settings: {
    accentOptions: ['#22D3EE', '#A3E635', '#FB923C', '#A78BFA', '#F472B6'],
    introMode: 'ANIMATION',
    mapTheme: 'AUTO',
    spotifyShowPlaylists: true,
    spotifyShowRecent: true,
    geminiKeySet: false,
    geminiModel: 'gemini-flash-lite-latest',
    geminiLiveModel: 'gemini-2.5-flash-native-audio-latest',
    wakeWord: 'ok honda',
    wakeWordEnabled: true,
    obdDevice: '',
    pairedDevices: [],
    ignitionDevice: '',
    version: '3.0.0'
  }
};

function asObj(v) {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
}

function asArr(v, fallback = []) {
  return Array.isArray(v) ? v : fallback;
}

export function normalizeState(raw) {
  const s = asObj(raw);
  const sp = asObj(s.spotify);
  const mp = asObj(s.map);
  const vh = asObj(s.vehicle);
  const fl = asObj(vh.fuelLog);
  const st = asObj(s.settings);
  const cal = asObj(s.calendar);
  const ph = asObj(s.phone);
  const appsRaw = asObj(s.apps);

  let notif = null;
  if (s.notification && typeof s.notification === 'object') {
    const n = s.notification;
    if (n.id || n.title || n.text) {
      notif = {
        id: String(n.id ?? ''),
        app: String(n.app ?? 'Mensagem'),
        packageName: String(n.packageName ?? ''),
        title: String(n.title ?? ''),
        text: String(n.text ?? ''),
        ...(n.source !== undefined ? { source: String(n.source) } : {}),
        ...(n.canReply !== undefined ? { canReply: Boolean(n.canReply) } : {})
      };
    }
  }

  return {
    v: Number(s.v ?? 1),
    accent: String(s.accent || DEFAULT_STATE.accent),
    brand: String(s.brand || DEFAULT_STATE.brand),
    clock: { ...DEFAULT_STATE.clock, ...asObj(s.clock) },
    weather: { ...DEFAULT_STATE.weather, ...asObj(s.weather) },
    calendar: {
      ...DEFAULT_STATE.calendar,
      ...cal,
      events: asArr(cal.events),
      holidays: asArr(cal.holidays)
    },
    status: { ...DEFAULT_STATE.status, ...asObj(s.status) },
    telemetry: {
      ...DEFAULT_STATE.telemetry,
      ...asObj(s.telemetry),
      alerts: asArr(asObj(s.telemetry).alerts)
    },
    media: { ...DEFAULT_STATE.media, ...asObj(s.media) },
    spotify: {
      playlists: asArr(sp.playlists),
      recent: asArr(sp.recent),
      recentSearches: asArr(sp.recentSearches),
      search: {
        ...DEFAULT_STATE.spotify.search,
        ...asObj(sp.search),
        tracks: asArr(asObj(sp.search).tracks),
        artists: asArr(asObj(sp.search).artists),
        albums: asArr(asObj(sp.search).albums),
        playlists: asArr(asObj(sp.search).playlists)
      }
    },
    map: {
      ...DEFAULT_STATE.map,
      ...mp,
      favorites: asArr(mp.favorites),
      recentPlaces: asArr(mp.recentPlaces),
      search: {
        ...DEFAULT_STATE.map.search,
        ...asObj(mp.search),
        results: asArr(asObj(mp.search).results)
      },
      route: {
        ...DEFAULT_STATE.map.route,
        ...asObj(mp.route),
        polyline: asArr(asObj(mp.route).polyline)
      }
    },
    copilot: { ...DEFAULT_STATE.copilot, ...asObj(s.copilot) },
    audio: { ...DEFAULT_STATE.audio, ...asObj(s.audio) },
    notification: notif,
    phone: {
      ...DEFAULT_STATE.phone,
      ...ph,
      iconsVersion: Number(ph.iconsVersion || 0),
      iconPackages: asArr(ph.iconPackages),
      roles: asObj(ph.roles),
      discovered: asArr(ph.discovered),
      call: { ...DEFAULT_STATE.phone.call, ...asObj(ph.call) },
      messages: asArr(ph.messages)
    },
    apps: {
      ...DEFAULT_STATE.apps,
      ...appsRaw,
      tablet: asArr(appsRaw.tablet),
      mapApps: asArr(appsRaw.mapApps),
      musicApps: asArr(appsRaw.musicApps)
    },
    vehicle: {
      ...DEFAULT_STATE.vehicle,
      ...vh,
      dtcCodes: asArr(vh.dtcCodes),
      pids: asArr(vh.pids),
      fuelLog: {
        entries: asArr(fl.entries),
        summary: { ...DEFAULT_STATE.vehicle.fuelLog.summary, ...asObj(fl.summary) }
      },
      maintenance: {
        items: asArr(asObj(vh.maintenance).items)
      }
    },
    settings: {
      ...DEFAULT_STATE.settings,
      ...st,
      accentOptions: asArr(st.accentOptions, DEFAULT_STATE.settings.accentOptions).length
        ? asArr(st.accentOptions)
        : DEFAULT_STATE.settings.accentOptions,
      pairedDevices: asArr(st.pairedDevices)
    }
  };
}

let mockMode = false;
let handlers = { onState: null, onTelemetry: null, onEvent: null };

export function isMockBackend() {
  return mockMode;
}

export function sendCommand(cmd, args) {
  if (!VALID_COMMANDS.has(cmd)) {
    console.warn('[HondaDeck] Comando ignorado fora do contrato:', cmd);
    return;
  }
  const message = args !== undefined ? { cmd, args } : { cmd };
  const serialized = JSON.stringify(message);
  if (window.HondaBridge && typeof window.HondaBridge.send === 'function') {
    try {
      window.HondaBridge.send(serialized);
    } catch (err) {
      console.error('[HondaDeck] Falha ao enviar para HondaBridge:', err);
    }
  }
}

export async function setupBridge(callbacks) {
  handlers = { ...handlers, ...callbacks };

  window.HD = {
    lastState: null,
    receive(rawMsg) {
      try {
        const msg = typeof rawMsg === 'string' ? JSON.parse(rawMsg) : rawMsg;
        if (!msg || typeof msg !== 'object') return;
        const { type, payload } = msg;
        if (type === 'state') {
          const normalized = normalizeState(payload);
          window.HD.lastState = normalized;
          if (handlers.onState) handlers.onState(normalized);
        } else if (type === 'telemetry') {
          const t = asObj(payload);
          if (window.HD.lastState && window.HD.lastState.telemetry) {
            Object.assign(window.HD.lastState.telemetry, t);
          }
          if (handlers.onTelemetry) handlers.onTelemetry(t);
        } else if (type === 'event' && handlers.onEvent) {
          handlers.onEvent(asObj(payload));
        }
      } catch (err) {
        console.error('[HondaDeck] Erro ao processar mensagem da ponte:', err);
      }
    }
  };

  if (typeof window.HondaBridge === 'undefined' || typeof window.HondaBridge.send !== 'function') {
    mockMode = true;
    const mod = await import('../mock/mock-backend.js');
    if (mod && typeof mod.installMockBackend === 'function') {
      mod.installMockBackend();
    }
  }
}

/** Avisa o Kotlin que a UI está pronta (ele responde com um state completo). */
export function signalReady() {
  sendCommand('ui.ready');
}

export async function initBridge(callbacks) {
  await setupBridge(callbacks);
  signalReady();
}
