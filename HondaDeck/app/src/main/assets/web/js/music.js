// Spotify: card do Início, mini-player (modo mapa) e painel expandido (modo música 2/3)

import { sendCommand } from './bridge.js';
import {
  el,
  clamp,
  debounce,
  formatDurationMs,
  setText,
  setHidden,
  setCover,
  setPlayIcon,
  jsonSig,
  createNoteSvg,
  createClockSvg,
  createAppIconEl,
  appCandidatesForKey
} from './util.js';

const KIND_LABEL = { TRACK: 'MÚSICA', ARTIST: 'ARTISTA', ALBUM: 'ÁLBUM', PLAYLIST: 'PLAYLIST', EPISODE: 'EPISÓDIO', SHOW: 'PODCAST' };

let switchModeFn = null;
let lastState = null;
let media = {};
let spotify = { playlists: [], recent: [], recentSearches: [], search: {} };
let settings = {};

// progresso local
const progress = { baseMs: 0, baseAt: performance.now(), playing: false, durationMs: 0, trackKey: '' };
const seekers = [];
let homeFocused = false;
let homeDropClosed = false;

const sigs = { gateHome: '', gateFull: '', playlists: '', recents: '', homeDrop: '', fullSearch: '', homeShelf: '' };

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// Progresso
// ---------------------------------------------------------------------------

function currentPositionMs() {
  const dur = progress.durationMs || 0;
  const raw = progress.baseMs + (progress.playing ? performance.now() - progress.baseAt : 0);
  return dur > 0 ? clamp(raw, 0, dur) : Math.max(0, raw);
}

function paintSeekers(fraction, posMs, durMs) {
  const pct = `${(fraction * 100).toFixed(2)}%`;
  for (const s of seekers) {
    s.fill.style.width = pct;
    s.thumb.style.left = pct;
    s.bar.setAttribute('aria-valuenow', String(Math.round(fraction * 100)));
  }
  setText($('home-pos-time'), formatDurationMs(posMs));
  setText($('full-pos-time'), formatDurationMs(posMs));
  setText($('home-dur-time'), formatDurationMs(durMs));
  setText($('full-dur-time'), formatDurationMs(durMs));
}

function tickProgress() {
  if (document.hidden) return;
  if (seekers.some((s) => s.dragging)) return;
  const dur = progress.durationMs || 0;
  const pos = currentPositionMs();
  paintSeekers(dur > 0 ? pos / dur : 0, pos, dur);
}

function bindSeek(prefix) {
  const bar = $(`${prefix}-seek-bar`);
  const seeker = { bar, fill: $(`${prefix}-seek-fill`), thumb: $(`${prefix}-seek-thumb`), dragging: false };
  seekers.push(seeker);

  const fractionOf = (ev) => {
    const rect = seeker.bar.getBoundingClientRect();
    return rect.width > 0 ? clamp((ev.clientX - rect.left) / rect.width, 0, 1) : 0;
  };
  const preview = (ev) => {
    const f = fractionOf(ev);
    paintSeekers(f, f * progress.durationMs, progress.durationMs);
    return f;
  };

  bar.addEventListener('pointerdown', (ev) => {
    if (!progress.durationMs) return;
    seeker.dragging = true;
    try {
      bar.setPointerCapture(ev.pointerId);
    } catch {
      /* ignore */
    }
    preview(ev);
  });
  bar.addEventListener('pointermove', (ev) => {
    if (seeker.dragging) preview(ev);
  });
  const finish = (ev, commit) => {
    if (!seeker.dragging) return;
    seeker.dragging = false;
    if (commit) {
      const ms = Math.round(fractionOf(ev) * progress.durationMs);
      progress.baseMs = ms;
      progress.baseAt = performance.now();
      sendCommand('music.seek', { ms });
    }
    tickProgress();
  };
  bar.addEventListener('pointerup', (ev) => finish(ev, true));
  bar.addEventListener('pointercancel', (ev) => finish(ev, false));
  bar.addEventListener('keydown', (ev) => {
    if (!progress.durationMs) return;
    const step = ev.key === 'ArrowRight' ? 5000 : ev.key === 'ArrowLeft' ? -5000 : 0;
    if (!step) return;
    const ms = clamp(currentPositionMs() + step, 0, progress.durationMs);
    progress.baseMs = ms;
    progress.baseAt = performance.now();
    sendCommand('music.seek', { ms: Math.round(ms) });
  });
}

// ---------------------------------------------------------------------------
// Gate (NEEDS_AUTH / NOT_INSTALLED / ...)
// ---------------------------------------------------------------------------

function gateInfo(status) {
  switch (status) {
    case 'NEEDS_AUTH':
      return { title: 'Conectar ao Spotify', sub: 'Autorize o acesso para tocar suas músicas e playlists no painel.', btn: 'Conectar Spotify', cmd: 'music.authorize' };
    case 'NOT_INSTALLED':
      return { title: 'Spotify não instalado', sub: 'Instale o aplicativo Spotify no tablet para usar a música no painel.', btn: null, cmd: null };
    case 'CONNECTING':
      return { title: 'Conectando ao Spotify…', sub: 'Aguarde um instante.', btn: null, cmd: null };
    case 'FAILED':
      return { title: 'Spotify indisponível', sub: 'Não foi possível conectar. Abra o aplicativo Spotify e tente de novo.', btn: 'Abrir Spotify', cmd: 'music.openApp' };
    case 'NOT_CONFIGURED':
      return { title: 'Spotify não configurado', sub: 'As chaves do Spotify ainda não foram configuradas neste aparelho.', btn: null, cmd: null };
    default:
      return null;
  }
}

function renderGate(container, sigKey, info) {
  const sig = info ? `${info.title}|${info.btn}` : '';
  if (sigs[sigKey] === sig) return;
  sigs[sigKey] = sig;
  container.replaceChildren();
  if (!info) return;
  const children = [
    createAppIconEl({
      candidates: appCandidatesForKey('spotify'),
      size: 36,
      radius: 10,
      alt: 'Spotify',
      fallback: el('span', { className: 'spotify-dot', 'aria-hidden': 'true' })
    }),
    el('span', { className: 'music-gate-title' }, info.title),
    el('span', { className: 'music-gate-sub' }, info.sub)
  ];
  if (info.btn) {
    children.push(
      el('button', { className: 'spotify-connect-btn', type: 'button', onClick: () => sendCommand(info.cmd) }, info.btn)
    );
  }
  container.append(...children);
}

// ---------------------------------------------------------------------------
// Busca
// ---------------------------------------------------------------------------

const debouncedMusicSearch = debounce((q) => sendCommand('music.search', { query: q }), 350);

function searchMatches(q) {
  const s = spotify.search || {};
  return String(s.query || '').trim().toLowerCase() === q.trim().toLowerCase();
}

function searchItemsFlat() {
  const s = spotify.search || {};
  const out = [];
  for (const [list, max] of [[s.tracks, 3], [s.artists, 2], [s.albums, 2], [s.playlists, 2]]) {
    for (const it of (list || []).slice(0, max)) out.push(it);
  }
  return out;
}

function thumbNode(url, size) {
  const box = el('span', { className: 'dropdown-item-thumb' });
  box.style.width = `${size}px`;
  box.style.height = `${size}px`;
  if (url) {
    const img = el('img', { alt: '', loading: 'lazy' });
    img.style.cssText = 'width:100%;height:100%;object-fit:cover;border-radius:inherit';
    img.addEventListener('error', () => {
      img.remove();
      box.appendChild(createNoteSvg(16));
    });
    img.src = url;
    box.appendChild(img);
  } else {
    box.appendChild(createNoteSvg(16));
  }
  return box;
}

function playItem(item) {
  if (!item?.uri) return;
  sendCommand('music.playUri', { uri: item.uri });
}

function renderHomeDropdown() {
  const input = $('home-music-input');
  const clearBtn = $('home-music-clear');
  const dropdown = $('home-music-dropdown');
  const q = input.value.trim();
  clearBtn.classList.toggle('hidden', !input.value);
  if (homeDropClosed) {
    dropdown.classList.add('hidden');
    return;
  }

  const s = spotify.search || {};
  let sig;
  if (!q) {
    sig = homeFocused && settings.spotifyShowRecent !== false ? `recent|${jsonSig(spotify.recentSearches)}` : 'none';
  } else {
    sig = `q|${q}|${searchMatches(q)}|${s.loading}|${s.error}|${jsonSig(searchItemsFlat().map((i) => i.uri))}`;
  }
  if (sig === sigs.homeDrop) return;
  sigs.homeDrop = sig;

  dropdown.replaceChildren();
  if (sig === 'none') {
    dropdown.classList.add('hidden');
    return;
  }

  if (!q) {
    const recents = (spotify.recentSearches || []).slice(0, 5);
    if (!recents.length) {
      dropdown.classList.add('hidden');
      return;
    }
    for (const term of recents) {
      dropdown.appendChild(
        el(
          'button',
          {
            className: 'dropdown-item',
            type: 'button',
            onClick: () => {
              input.value = term;
              homeFocused = true;
              sendCommand('music.search', { query: term });
              renderHomeDropdown();
            }
          },
          createClockSvg(16, '#8A97A8'),
          el('span', { className: 'dropdown-item-texts' }, el('span', { className: 'dropdown-item-title' }, term)),
          el('span', { className: 'dropdown-item-meta' }, 'RECENTE')
        )
      );
    }
    dropdown.classList.remove('hidden');
    return;
  }

  const items = searchItemsFlat();
  if (s.error && searchMatches(q)) {
    dropdown.appendChild(el('div', { className: 'dropdown-item' }, el('span', { className: 'dropdown-item-texts' }, el('span', { className: 'dropdown-item-title' }, String(s.error)))));
  } else if (!searchMatches(q) || s.loading) {
    dropdown.appendChild(el('div', { className: 'dropdown-item' }, el('span', { className: 'dropdown-item-texts' }, el('span', { className: 'dropdown-item-title' }, 'Buscando no Spotify…'))));
  } else if (!items.length) {
    dropdown.appendChild(el('div', { className: 'dropdown-item' }, el('span', { className: 'dropdown-item-texts' }, el('span', { className: 'dropdown-item-title' }, 'Nada encontrado'))));
  }
  if (searchMatches(q) && !s.loading) {
    items.forEach((item, idx) => {
      dropdown.appendChild(
        el(
          'button',
          {
            className: 'dropdown-item',
            type: 'button',
            'data-test': `music-result-${idx}`,
            onClick: () => {
              input.value = '';
              homeFocused = false;
              renderHomeDropdown();
              playItem(item);
            }
          },
          thumbNode(item.imageUrl, 34),
          el(
            'span',
            { className: 'dropdown-item-texts' },
            el('span', { className: 'dropdown-item-title' }, item.name || ''),
            item.subtitle ? el('span', { className: 'dropdown-item-sub' }, item.subtitle) : null
          ),
          el('span', { className: 'dropdown-item-meta' }, KIND_LABEL[String(item.kind || '').toUpperCase()] || '')
        )
      );
    });
  }
  dropdown.classList.remove('hidden');
}

function sectionBox(title, list, nextIdx) {
  const box = el('div', { className: 'search-sec-box' }, el('span', { className: 'section-kicker' }, title));
  for (const item of list.slice(0, 5)) {
    const row = recentRow(item);
    if (nextIdx) row.setAttribute('data-test', `music-result-${nextIdx()}`);
    box.appendChild(row);
  }
  return box;
}

function recentRow(item) {
  const btn = el('button', { className: 'recent-music-btn', type: 'button', onClick: () => {
    clearFullSearch();
    playItem(item);
  } });
  if (item.imageUrl) {
    const img = el('img', { alt: '', loading: 'lazy' });
    img.addEventListener('error', () => img.remove());
    img.src = item.imageUrl;
    btn.appendChild(img);
  } else {
    btn.appendChild(createNoteSvg(20));
  }
  btn.appendChild(
    el(
      'span',
      { className: 'dropdown-item-texts' },
      el('span', { className: 'dropdown-item-title' }, item.name || ''),
      item.subtitle ? el('span', { className: 'dropdown-item-sub' }, item.subtitle) : null
    )
  );
  return btn;
}

function clearFullSearch() {
  const input = $('full-music-input');
  input.value = '';
  renderFullSearch();
}

function renderFullSearch() {
  const input = $('full-music-input');
  const clearBtn = $('full-music-clear');
  const sections = $('full-music-search-sections');
  const main = $('full-music-main');
  const q = input.value.trim();
  clearBtn.classList.toggle('hidden', !input.value);

  const gateActive = Boolean(gateInfo(media.status));
  if (!q || gateActive) {
    sections.classList.add('hidden');
    main.classList.toggle('hidden', gateActive);
    sigs.fullSearch = '';
    return;
  }

  sections.classList.remove('hidden');
  main.classList.add('hidden');

  const s = spotify.search || {};
  const sig = `${q}|${searchMatches(q)}|${s.loading}|${s.error}|${jsonSig([s.tracks, s.artists, s.albums, s.playlists])}`;
  if (sig === sigs.fullSearch) return;
  sigs.fullSearch = sig;

  sections.replaceChildren();
  if (s.error && searchMatches(q)) {
    sections.appendChild(el('span', { className: 'settings-sub' }, String(s.error)));
    return;
  }
  if (!searchMatches(q) || s.loading) {
    sections.appendChild(el('span', { className: 'settings-sub' }, 'Buscando no Spotify…'));
    return;
  }
  const defs = [
    ['MÚSICAS', s.tracks],
    ['ARTISTAS', s.artists],
    ['ÁLBUNS', s.albums],
    ['PLAYLISTS', s.playlists]
  ];
  let any = false;
  let resultIdx = 0;
  const nextIdx = () => resultIdx++;
  for (const [title, list] of defs) {
    if (Array.isArray(list) && list.length) {
      any = true;
      sections.appendChild(sectionBox(title, list, nextIdx));
    }
  }
  if (!any) sections.appendChild(el('span', { className: 'settings-sub' }, 'Nada encontrado para essa busca.'));
}

// ---------------------------------------------------------------------------
// Playlists e recentes
// ---------------------------------------------------------------------------

function renderLibrary() {
  const showPl = settings.spotifyShowPlaylists !== false;
  const showRec = settings.spotifyShowRecent !== false;
  setHidden($('music-playlists-col'), !showPl);
  setHidden($('music-recents-col'), !showRec);
  const grid = $('music-library-grid');
  grid.style.gridTemplateColumns = showPl && showRec ? '' : 'minmax(0, 1fr)';
  setHidden(grid, !showPl && !showRec);

  const plSig = showPl ? jsonSig(spotify.playlists) : '';
  if (plSig !== sigs.playlists) {
    sigs.playlists = plSig;
    const container = $('music-playlists-grid');
    container.replaceChildren();
    const sorted = [...(spotify.playlists || [])].sort((a, b) => Number(Boolean(b.honda)) - Number(Boolean(a.honda)));
    if (!sorted.length) {
      container.appendChild(el('span', { className: 'settings-sub' }, 'Nenhuma playlist disponível.'));
    }
    for (const pl of sorted) {
      const thumb = el('span', { className: 'playlist-thumb' });
      if (pl.imageUrl) {
        const img = el('img', { alt: '', loading: 'lazy' });
        img.addEventListener('error', () => {
          img.remove();
          thumb.appendChild(createNoteSvg(20));
        });
        img.src = pl.imageUrl;
        thumb.appendChild(img);
      } else {
        thumb.appendChild(createNoteSvg(20));
      }
      container.appendChild(
        el(
          'button',
          { className: pl.honda ? 'playlist-card-btn honda' : 'playlist-card-btn', type: 'button', onClick: () => playItem(pl) },
          thumb,
          el(
            'span',
            { className: 'playlist-name-wrap' },
            el('span', { className: 'playlist-name' }, pl.name || 'Playlist'),
            pl.honda ? el('span', { className: 'honda-badge' }, 'HONDA') : null
          )
        )
      );
    }
  }

  const recSig = showRec ? jsonSig([spotify.recent, spotify.recentSearches]) : '';
  if (recSig !== sigs.recents) {
    sigs.recents = recSig;
    const list = $('music-recents-list');
    list.replaceChildren();
    for (const term of (spotify.recentSearches || []).slice(0, 4)) {
      list.appendChild(
        el(
          'button',
          {
            className: 'recent-music-btn',
            type: 'button',
            onClick: () => {
              const input = $('full-music-input');
              input.value = term;
              renderFullSearch();
              sendCommand('music.search', { query: term });
            }
          },
          createClockSvg(18, '#8A97A8'),
          el('span', { className: 'dropdown-item-texts' }, el('span', { className: 'dropdown-item-title' }, term), el('span', { className: 'dropdown-item-sub' }, 'Busca recente'))
        )
      );
    }
    for (const item of (spotify.recent || []).slice(0, 8)) list.appendChild(recentRow(item));
    if (!list.children.length) list.appendChild(el('span', { className: 'settings-sub' }, 'Nada tocado recentemente.'));
  }
}

// ---------------------------------------------------------------------------
// Faixa horizontal no cartão do Início (playlists Honda + Recentes)
// ---------------------------------------------------------------------------

function shelfTile(item, isHonda) {
  const art = el('span', { className: 'home-shelf-art' });
  if (item.imageUrl) {
    const img = el('img', { alt: '', loading: 'lazy' });
    img.addEventListener('error', () => {
      img.remove();
      art.appendChild(createNoteSvg(24));
    });
    img.src = item.imageUrl;
    art.appendChild(img);
  } else {
    art.appendChild(createNoteSvg(24));
  }
  if (isHonda) {
    art.appendChild(el('span', { className: 'home-shelf-badge' }, 'HONDA'));
  }
  return el(
    'button',
    {
      className: isHonda ? 'home-shelf-tile honda' : 'home-shelf-tile',
      type: 'button',
      title: item.name || '',
      onClick: () => playItem(item)
    },
    art,
    el('span', { className: 'home-shelf-title' }, item.name || 'Spotify')
  );
}

function renderHomeShelf() {
  const wrap = $('home-music-shelf-wrap');
  const shelf = $('home-music-shelf');
  if (!wrap || !shelf) return;

  const showPl = settings.spotifyShowPlaylists !== false;
  const showRec = settings.spotifyShowRecent !== false;
  const hondaPlaylists = showPl ? (spotify.playlists || []).filter((p) => Boolean(p && p.honda && p.uri)) : [];
  const recents = showRec ? (spotify.recent || []).filter((r) => Boolean(r && r.uri)) : [];

  const sig = jsonSig([
    hondaPlaylists.map((p) => [p.uri, p.name, p.imageUrl]),
    recents.map((r) => [r.uri, r.name, r.imageUrl])
  ]);
  if (sig === sigs.homeShelf) return;
  sigs.homeShelf = sig;

  shelf.replaceChildren();
  if (!hondaPlaylists.length && !recents.length) {
    setHidden(wrap, true);
    return;
  }

  if (hondaPlaylists.length) {
    const row = el('div', { className: 'home-shelf-row' });
    for (const pl of hondaPlaylists) row.appendChild(shelfTile(pl, true));
    shelf.appendChild(
      el(
        'div',
        { className: 'home-shelf-group' },
        el('span', { className: 'home-shelf-label honda' }, 'PLAYLISTS HONDA'),
        row
      )
    );
  }

  if (hondaPlaylists.length && recents.length) {
    shelf.appendChild(el('span', { className: 'home-shelf-sep', 'aria-hidden': 'true' }));
  }

  if (recents.length) {
    const row = el('div', { className: 'home-shelf-row' });
    for (const item of recents.slice(0, 10)) row.appendChild(shelfTile(item, false));
    shelf.appendChild(
      el(
        'div',
        { className: 'home-shelf-group' },
        el('span', { className: 'home-shelf-label' }, 'RECENTES'),
        row
      )
    );
  }

  setHidden(wrap, false);
}

// ---------------------------------------------------------------------------
// Init e update
// ---------------------------------------------------------------------------

export function initMusic({ switchMode }) {
  switchModeFn = switchMode;

  // Navegação
  $('home-music-expand-btn').addEventListener('click', () => switchModeFn('music'));
  $('home-music-track-btn').addEventListener('click', () => switchModeFn('music'));
  $('mini-player-open-btn').addEventListener('click', () => switchModeFn('music'));
  $('mini-player-info-btn').addEventListener('click', () => switchModeFn('music'));
  $('music-close-btn').addEventListener('click', () => switchModeFn('home'));

  // Transporte
  for (const id of ['home-play-btn', 'mini-play-btn', 'full-play-btn']) {
    $(id).addEventListener('click', () => sendCommand('music.toggle'));
  }
  for (const id of ['home-prev-btn', 'full-prev-btn']) $(id).addEventListener('click', () => sendCommand('music.prev'));
  for (const id of ['home-next-btn', 'full-next-btn']) $(id).addEventListener('click', () => sendCommand('music.next'));
  for (const id of ['home-shuffle-btn', 'full-shuffle-btn']) $(id).addEventListener('click', () => sendCommand('music.shuffle'));
  $('full-repeat-btn').addEventListener('click', () => sendCommand('music.repeat'));
  for (const id of ['home-like-btn', 'full-like-btn']) $(id).addEventListener('click', () => sendCommand('music.like'));

  bindSeek('home');
  bindSeek('full');
  setInterval(tickProgress, 250);

  // Busca (card do início)
  const homeInput = $('home-music-input');
  homeInput.addEventListener('focus', () => {
    homeFocused = true;
    homeDropClosed = false;
    sigs.homeDrop = '';
    renderHomeDropdown();
  });
  homeInput.addEventListener('input', () => {
    homeFocused = true;
    homeDropClosed = false;
    const q = homeInput.value.trim();
    if (q) debouncedMusicSearch(q);
    renderHomeDropdown();
  });
  homeInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && homeInput.value.trim()) {
      debouncedMusicSearch.cancel();
      sendCommand('music.search', { query: homeInput.value.trim() });
    }
  });
  $('home-music-clear').addEventListener('click', () => {
    homeInput.value = '';
    homeFocused = false;
    homeDropClosed = true;
    homeInput.blur();
    debouncedMusicSearch.cancel();
    sendCommand('music.search', { query: '' });
    sigs.homeDrop = '';
    renderHomeDropdown();
  });
  homeInput.addEventListener('blur', () => {
    setTimeout(() => {
      if (document.activeElement !== homeInput && !homeInput.value.trim()) {
        homeFocused = false;
        homeDropClosed = true;
        sigs.homeDrop = '';
        $('home-music-dropdown').classList.add('hidden');
      }
    }, 180);
  });
  document.addEventListener('pointerdown', (e) => {
    const wrap = document.querySelector('.music-home-searchbar');
    if (wrap && !wrap.contains(e.target)) {
      homeFocused = false;
      homeDropClosed = true;
      sigs.homeDrop = '';
      $('home-music-dropdown').classList.add('hidden');
    }
  });

  // Busca (painel expandido)
  const fullInput = $('full-music-input');
  fullInput.addEventListener('input', () => {
    const q = fullInput.value.trim();
    if (q) debouncedMusicSearch(q);
    else {
      debouncedMusicSearch.cancel();
      sendCommand('music.search', { query: '' });
    }
    renderFullSearch();
  });
  fullInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && fullInput.value.trim()) {
      debouncedMusicSearch.cancel();
      sendCommand('music.search', { query: fullInput.value.trim() });
    }
  });
  $('full-music-clear').addEventListener('click', () => {
    clearFullSearch();
    debouncedMusicSearch.cancel();
    sendCommand('music.search', { query: '' });
  });
}

export function focusFullMusicSearch() {
  $('full-music-input')?.focus();
}

export function updateMusic(state) {
  lastState = state;
  media = state.media || {};
  spotify = state.spotify || spotify;
  settings = state.settings || {};

  const status = String(media.status || 'NOT_CONFIGURED').toUpperCase();
  const gate = gateInfo(status);

  // Gates
  renderGate($('home-music-gate'), 'gateHome', gate);
  setHidden($('home-music-gate'), !gate);
  setHidden($('home-music-body'), Boolean(gate));
  renderGate($('full-music-gate'), 'gateFull', gate);
  setHidden($('full-music-gate'), !gate);

  // Faixa atual
  const hasTrack = Boolean(media.title);
  const title = hasTrack ? media.title : 'Sem reprodução';
  const artist = hasTrack ? media.artist || '' : 'Spotify';
  const ctx = media.context ? String(media.context).toUpperCase() : 'SPOTIFY';
  setText($('home-track-title'), title);
  setText($('home-track-artist'), artist);
  setText($('home-track-context'), `DE: ${ctx}`);
  setText($('mini-track-title'), title);
  setText($('mini-track-artist'), artist);
  setText($('full-track-title'), title);
  setText($('full-track-artist'), artist);
  setText($('full-track-context'), `TOCANDO DE: ${ctx}`);

  setCover($('home-cover-img'), $('home-cover-fallback'), media.coverUrl);
  setCover($('mini-cover-img'), $('mini-cover-fallback'), media.coverUrl);
  setCover($('full-cover-img'), $('full-cover-fallback'), media.coverUrl);

  const playing = Boolean(media.playing);
  setPlayIcon($('home-play-icon'), playing);
  setPlayIcon($('mini-play-icon'), playing);
  setPlayIcon($('full-play-icon'), playing);

  // Auxiliares
  const shuffleOn = Boolean(media.shuffle);
  $('home-shuffle-btn')?.classList.toggle('active', shuffleOn);
  $('full-shuffle-btn').classList.toggle('active', shuffleOn);
  const repeat = String(media.repeat || 'off').toLowerCase();
  $('full-repeat-btn').classList.toggle('active', repeat !== 'off');
  setHidden($('full-repeat-one'), repeat !== 'one');
  const liked = Boolean(media.liked);
  $('home-like-btn')?.classList.toggle('active', liked);
  $('full-like-btn').classList.toggle('active', liked);
  for (const heartId of ['home-like-svg', 'full-like-svg']) {
    const heart = $(heartId);
    if (heart) heart.setAttribute('fill', liked ? 'currentColor' : 'none');
  }

  // Progresso: só ressincroniza quando muda a faixa/estado ou o desvio é grande
  const duration = Math.max(0, Number(media.durationMs || 0));
  const reportedPos = Math.max(0, Number(media.positionMs || 0));
  const trackKey = `${media.title}|${media.artist}|${duration}`;
  const drift = Math.abs(currentPositionMs() - reportedPos);
  if (trackKey !== progress.trackKey || playing !== progress.playing || drift > 1500) {
    progress.baseMs = reportedPos;
    progress.baseAt = performance.now();
  }
  progress.trackKey = trackKey;
  progress.playing = playing;
  progress.durationMs = duration;
  tickProgress();

  // Busca, biblioteca e faixa rápida do início
  if (!homeDropClosed && (homeFocused || $('home-music-input').value.trim())) {
    renderHomeDropdown();
  }
  renderFullSearch();
  renderLibrary();
  renderHomeShelf();
}

/** Zera a busca local quando o modo música é reaberto. */
export function resetFullSearchView() {
  renderFullSearch();
}
