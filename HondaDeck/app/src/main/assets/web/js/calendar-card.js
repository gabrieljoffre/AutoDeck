// Cartão grande de Calendário: visão de Mês + lista de Dia/Semana + feriados do Brasil + rota

import { sendCommand } from './bridge.js';
import { el, setText, setHidden } from './util.js';

const MESES_COMPLETOS = [
  'Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
  'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro'
];
const DIAS_SEMANA_COMPLETOS = [
  'Domingo', 'Segunda-feira', 'Terça-feira', 'Quarta-feira',
  'Quinta-feira', 'Sexta-feira', 'Sábado'
];
const DIAS_CABECALHO = ['D', 'S', 'T', 'Q', 'Q', 'S', 'S'];

let modalEl = null;
let cardEl = null;
let latestCalendar = { permissionGranted: false, events: [], holidays: [], statusText: '' };
let currentNowMs = Date.now();
let viewYear = new Date().getFullYear();
let viewMonth = new Date().getMonth(); // 0..11
let selectedIso = '';
let listScope = 'day'; // 'day' | 'week'
let isOpen = false;
let swipeStartX = null;

function toIsoDate(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function parseIsoDate(iso) {
  const [y, m, d] = String(iso).split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
}

function todayIso() {
  return toIsoDate(new Date(currentNowMs > 1_000_000_000_000 ? currentNowMs : Date.now()));
}

function formatTimeRange(ev) {
  if (ev.allDay) return 'Dia inteiro';
  const s = new Date(Number(ev.startMs || 0));
  const e = new Date(Number(ev.endMs || ev.startMs || 0));
  const sh = `${String(s.getHours()).padStart(2, '0')}:${String(s.getMinutes()).padStart(2, '0')}`;
  const eh = `${String(e.getHours()).padStart(2, '0')}:${String(e.getMinutes()).padStart(2, '0')}`;
  return ev.endMs && ev.endMs > ev.startMs ? `${sh} – ${eh}` : sh;
}

function eventsForIso(iso) {
  return (latestCalendar.events || []).filter((ev) => {
    const start = ev.dateIso || toIsoDate(new Date(ev.startMs));
    const end = ev.endDateIso || start;
    return iso >= start && iso <= end;
  });
}

function holidayForIso(iso) {
  return (latestCalendar.holidays || []).find((h) => h.dateIso === iso) || null;
}

function shiftMonth(delta) {
  const d = new Date(viewYear, viewMonth + delta, 1);
  viewYear = d.getFullYear();
  viewMonth = d.getMonth();
  render();
}

function jumpToToday() {
  const iso = todayIso();
  const d = parseIsoDate(iso);
  viewYear = d.getFullYear();
  viewMonth = d.getMonth();
  selectedIso = iso;
  render();
}

function renderMonthGrid() {
  const today = todayIso();
  const firstDay = new Date(viewYear, viewMonth, 1);
  const startDow = firstDay.getDay(); // 0=Dom
  const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();

  const headerRow = el('div', { className: 'cal-weekdays' },
    DIAS_CABECALHO.map((lbl) => el('span', { className: 'cal-weekday' }, lbl))
  );

  const cells = [];
  for (let i = 0; i < startDow; i++) {
    cells.push(el('div', { className: 'cal-day empty', 'aria-hidden': 'true' }));
  }

  for (let day = 1; day <= daysInMonth; day++) {
    const iso = `${viewYear}-${String(viewMonth + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
    const dayEvents = eventsForIso(iso);
    const holiday = holidayForIso(iso);
    const isToday = iso === today;
    const isSelected = iso === selectedIso;

    const dots = [];
    if (holiday) {
      dots.push(el('span', { className: 'cal-dot holiday', title: holiday.name }));
    }
    const seenColors = new Set();
    for (const ev of dayEvents) {
      const c = ev.color || 'var(--accent)';
      if (!seenColors.has(c) && dots.length < 3) {
        seenColors.add(c);
        const dot = el('span', { className: 'cal-dot' });
        dot.style.background = c;
        dots.push(dot);
      }
    }

    const btn = el('button', {
      type: 'button',
      className: `cal-day${isToday ? ' today' : ''}${isSelected ? ' selected' : ''}${holiday ? ' has-holiday' : ''}`,
      dataset: { iso },
      'aria-label': `${day} de ${MESES_COMPLETOS[viewMonth]}${holiday ? `, feriado: ${holiday.name}` : ''}`,
      onClick: () => {
        selectedIso = iso;
        render();
      }
    },
      el('span', { className: 'cal-day-num' }, String(day)),
      el('span', { className: 'cal-dots' }, dots)
    );
    cells.push(btn);
  }

  const grid = el('div', {
    className: 'cal-days-grid',
    onPointerdown: (e) => { swipeStartX = e.clientX; },
    onPointerup: (e) => {
      if (swipeStartX === null) return;
      const dx = e.clientX - swipeStartX;
      swipeStartX = null;
      if (Math.abs(dx) >= 48) shiftMonth(dx < 0 ? 1 : -1);
    },
    onPointercancel: () => { swipeStartX = null; }
  }, cells);

  return el('div', { className: 'cal-month-pane' },
    el('div', { className: 'cal-month-nav' },
      el('button', {
        type: 'button',
        className: 'cal-nav-btn',
        dataset: { test: 'cal-prev' },
        'aria-label': 'Mês anterior',
        onClick: () => shiftMonth(-1)
      }, '‹'),
      el('div', { className: 'cal-month-title-wrap' },
        el('span', { className: 'cal-month-title' }, `${MESES_COMPLETOS[viewMonth]} ${viewYear}`),
        el('button', {
          type: 'button',
          className: 'cal-today-btn',
          dataset: { test: 'cal-today' },
          onClick: jumpToToday
        }, 'Hoje')
      ),
      el('button', {
        type: 'button',
        className: 'cal-nav-btn',
        dataset: { test: 'cal-next' },
        'aria-label': 'Próximo mês',
        onClick: () => shiftMonth(1)
      }, '›')
    ),
    headerRow,
    grid
  );
}

function renderEventItem(ev, showDatePrefix = false) {
  const bar = el('span', { className: 'cal-ev-bar' });
  bar.style.background = ev.color || 'var(--accent)';

  const metaParts = [formatTimeRange(ev)];
  if (showDatePrefix && ev.dateIso) {
    const d = parseIsoDate(ev.dateIso);
    metaParts.unshift(`${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}`);
  }

  const hasLocation = Boolean(ev.location && ev.location.trim());
  const routeBtn = hasLocation
    ? el('button', {
        type: 'button',
        className: 'cal-route-btn',
        dataset: { test: 'cal-route' },
        onClick: (e) => {
          e.stopPropagation();
          sendCommand('map.route', { name: ev.location.trim() });
          closeCalendarCard();
          document.getElementById('dock-tab-map')?.click();
        }
      }, 'Traçar rota')
    : null;

  return el('div', {
    className: `cal-ev-item${hasLocation ? ' has-location' : ''}`,
    onClick: hasLocation ? () => {
      sendCommand('map.route', { name: ev.location.trim() });
      closeCalendarCard();
      document.getElementById('dock-tab-map')?.click();
    } : null
  },
    bar,
    el('div', { className: 'cal-ev-body' },
      el('div', { className: 'cal-ev-title' }, ev.title),
      el('div', { className: 'cal-ev-meta' }, metaParts.join(' · ')),
      hasLocation ? el('div', { className: 'cal-ev-loc' }, ev.location) : null
    ),
    routeBtn
  );
}

function renderAgendaPane() {
  const selDate = parseIsoDate(selectedIso || todayIso());
  const dowName = DIAS_SEMANA_COMPLETOS[selDate.getDay()] || '';
  const dateLabel = `${dowName}, ${selDate.getDate()} de ${MESES_COMPLETOS[selDate.getMonth()]}`;
  const holiday = holidayForIso(selectedIso);

  const scopeToggle = el('div', { className: 'cal-scope-tabs' },
    el('button', {
      type: 'button',
      className: `cal-scope-btn${listScope === 'day' ? ' active' : ''}`,
      dataset: { test: 'cal-scope-day' },
      onClick: () => { listScope = 'day'; render(); }
    }, 'Dia'),
    el('button', {
      type: 'button',
      className: `cal-scope-btn${listScope === 'week' ? ' active' : ''}`,
      dataset: { test: 'cal-scope-week' },
      onClick: () => { listScope = 'week'; render(); }
    }, 'Semana')
  );

  const content = [];

  if (holiday) {
    content.push(
      el('div', { className: 'cal-holiday-banner' },
        el('span', { className: 'cal-holiday-badge' }, 'Feriado'),
        el('span', null, holiday.name)
      )
    );
  }

  if (!latestCalendar.permissionGranted) {
    content.push(
      el('div', { className: 'cal-perm-box' },
        el('p', { className: 'cal-perm-text' }, 'Permita o acesso à agenda do tablet para ver seus compromissos do Google Agenda.'),
        el('button', {
          type: 'button',
          className: 'btn-accent cal-perm-btn',
          dataset: { test: 'cal-grant' },
          onClick: () => sendCommand('calendar.requestPermission')
        }, 'Permitir acesso à agenda')
      )
    );
  } else if (listScope === 'day') {
    const list = eventsForIso(selectedIso);
    if (list.length === 0) {
      content.push(el('div', { className: 'cal-empty-msg' }, 'Sem compromissos neste dia.'));
    } else {
      list.forEach((ev) => content.push(renderEventItem(ev, false)));
    }
  } else {
    // Semana começando no domingo da semana do dia selecionado
    const startOfWeek = new Date(selDate.getFullYear(), selDate.getMonth(), selDate.getDate() - selDate.getDay());
    let any = false;
    for (let i = 0; i < 7; i++) {
      const d = new Date(startOfWeek.getFullYear(), startOfWeek.getMonth(), startOfWeek.getDate() + i);
      const iso = toIsoDate(d);
      const h = holidayForIso(iso);
      const evs = eventsForIso(iso);
      if (h) {
        any = true;
        content.push(
          el('div', { className: 'cal-holiday-banner compact' },
            el('span', { className: 'cal-holiday-badge' }, `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}`),
            el('span', null, h.name)
          )
        );
      }
      for (const ev of evs) {
        any = true;
        content.push(renderEventItem({ ...ev, dateIso: iso }, true));
      }
    }
    if (!any) {
      content.push(el('div', { className: 'cal-empty-msg' }, 'Sem compromissos nesta semana.'));
    }
  }

  return el('div', { className: 'cal-agenda-pane' },
    el('div', { className: 'cal-agenda-head' },
      el('span', { className: 'cal-selected-title' }, dateLabel),
      scopeToggle
    ),
    el('div', { className: 'cal-events-scroll' }, content)
  );
}

function render() {
  if (!cardEl || !isOpen) return;
  if (!selectedIso) selectedIso = todayIso();

  const header = el('div', { className: 'modal-card-header' },
    el('div', { className: 'modal-card-title-group' },
      el('span', { className: 'modal-card-eyebrow' }, 'AGENDA & FERIADOS'),
      el('h2', { className: 'modal-card-title' }, 'Calendário')
    ),
    el('button', {
      type: 'button',
      className: 'close-hero-btn',
      dataset: { test: 'calendar-close' },
      'aria-label': 'Fechar calendário',
      onClick: closeCalendarCard
    }, '✕')
  );

  const body = el('div', { className: 'cal-modal-body' },
    renderMonthGrid(),
    renderAgendaPane()
  );

  cardEl.replaceChildren(header, body);
}

export function initCalendarCard() {
  modalEl = document.getElementById('calendar-modal');
  cardEl = document.getElementById('calendar-card');
  if (!modalEl || !cardEl) return;

  modalEl.addEventListener('click', (e) => {
    if (e.target === modalEl) closeCalendarCard();
  });
}

export function openCalendarCard() {
  if (!modalEl) return;
  isOpen = true;
  const iso = todayIso();
  const d = parseIsoDate(iso);
  viewYear = d.getFullYear();
  viewMonth = d.getMonth();
  selectedIso = iso;
  modalEl.classList.remove('hidden');
  modalEl.classList.add('open');
  render();
  sendCommand('calendar.refresh');
}

export function closeCalendarCard() {
  if (!modalEl) return;
  isOpen = false;
  modalEl.classList.remove('open');
  modalEl.classList.add('hidden');
}

export function updateCalendarCard(state, nowMs) {
  if (nowMs) currentNowMs = nowMs;
  if (state?.calendar) latestCalendar = state.calendar;
  if (isOpen) render();
}
