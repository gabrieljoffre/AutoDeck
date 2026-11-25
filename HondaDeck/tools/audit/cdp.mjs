// Cliente Chrome DevTools Protocol (CDP) para WebView do HondaDeck (Node 22+, zero dependências npm).
// Usa o WebSocket global e respeita timeout máximo estrito de 20 s por operação.
//
// Convenção exigida da interface web:
//   window.HD.lastState -> deve armazenar o último objeto `payload` recebido em
//   window.HD.receive({ type: "state", payload }) (e atualizar telemetry quando vier type: "telemetry").

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  APP_PACKAGE,
  MAX_CALL_TIMEOUT_MS,
  clampTimeout,
  forwardTcp,
  pidof,
  removeForward,
} from './adb.mjs';

export const DEFAULT_CDP_PORT = 9222;
export const DEFAULT_CDP_TIMEOUT_MS = 12_000;

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function formatRemoteObject(arg) {
  if (!arg || typeof arg !== 'object') return String(arg ?? '');
  if ('value' in arg && arg.value !== undefined) {
    return typeof arg.value === 'string' ? arg.value : JSON.stringify(arg.value);
  }
  if (arg.description) return String(arg.description);
  if (arg.unserializableValue) return String(arg.unserializableValue);
  return arg.type || 'unknown';
}

function formatExceptionDetails(details) {
  if (!details) return 'Exceção desconhecida no Runtime';
  const base = details.text || 'Uncaught exception';
  const desc = details.exception?.description || details.exception?.value || '';
  const loc = details.url
    ? ` (${details.url}:${(details.lineNumber ?? 0) + 1}:${(details.columnNumber ?? 0) + 1})`
    : '';
  return desc ? `${base}: ${desc}${loc}` : `${base}${loc}`;
}

/**
 * Determina se uma string deve ser avaliada como expressão JS em vez de seletor CSS.
 */
export function isLikelyJsExpression(input) {
  const s = String(input || '').trim();
  if (!s) return false;
  if (s.startsWith('js:')) return true;
  if (s.startsWith('css:')) return false;
  if (
    /^(window\.|globalThis\.|document\.|HD\.|state\.|vars\.|!|\(|return\s|Boolean\(|Number\(|Array\.)/.test(
      s
    )
  ) {
    return true;
  }
  if (/(\?\.|===|!==|==|!=|>=|<=|&&|\|\||=>)/.test(s)) {
    return true;
  }
  return false;
}

/**
 * Lista os alvos inspecionáveis da WebView em `http://127.0.0.1:<port>/json`.
 */
export async function listTargets(port = DEFAULT_CDP_PORT, timeoutMs = 8_000) {
  const limitMs = clampTimeout(timeoutMs, 8_000);
  const url = `http://127.0.0.1:${port}/json`;
  const res = await fetch(url, {
    signal: AbortSignal.timeout(limitMs),
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) {
    throw new Error(`Falha ao consultar ${url}: HTTP ${res.status}`);
  }
  const list = await res.json();
  return Array.isArray(list) ? list : [];
}

/**
 * Sessão ativa do Chrome DevTools Protocol conectada à página principal da WebView.
 */
export class CdpSession {
  constructor(ws, target, meta = {}) {
    this.ws = ws;
    this.target = target;
    this.meta = meta;
    this._nextId = 1;
    this._pending = new Map();
    this._consoleErrors = [];
    this._consoleAll = [];
    this._closed = false;

    this._onMessage = (ev) => {
      const raw =
        typeof ev.data === 'string'
          ? ev.data
          : ev.data instanceof ArrayBuffer
            ? Buffer.from(ev.data).toString('utf8')
            : Buffer.from(ev.data).toString('utf8');

      let msg;
      try {
        msg = JSON.parse(raw);
      } catch {
        return;
      }

      if (typeof msg.id === 'number') {
        const entry = this._pending.get(msg.id);
        if (!entry) return;
        this._pending.delete(msg.id);
        clearTimeout(entry.timer);
        if (msg.error) {
          entry.reject(
            new Error(`Erro CDP em ${entry.method}: ${msg.error.message || JSON.stringify(msg.error)}`)
          );
        } else {
          entry.resolve(msg.result ?? {});
        }
        return;
      }

      if (msg.method === 'Runtime.consoleAPICalled') {
        const p = msg.params || {};
        const text = Array.isArray(p.args) ? p.args.map(formatRemoteObject).join(' ') : '';
        const topFrame = p.stackTrace?.callFrames?.[0];
        const record = {
          source: 'console',
          type: p.type || 'log',
          text,
          url: topFrame?.url || '',
          lineNumber: topFrame ? topFrame.lineNumber + 1 : null,
          timestamp: p.timestamp || Date.now(),
        };
        this._consoleAll.push(record);
        if (p.type === 'error' || p.type === 'assert') {
          this._consoleErrors.push(record);
        }
      } else if (msg.method === 'Runtime.exceptionThrown') {
        const details = msg.params?.exceptionDetails;
        const record = {
          source: 'exception',
          type: 'uncaught',
          text: formatExceptionDetails(details),
          url: details?.url || '',
          lineNumber: details?.lineNumber != null ? details.lineNumber + 1 : null,
          timestamp: msg.params?.timestamp || Date.now(),
        };
        this._consoleAll.push(record);
        this._consoleErrors.push(record);
      }
    };

    this._onClose = () => {
      this._closed = true;
      for (const [, entry] of this._pending) {
        clearTimeout(entry.timer);
        entry.reject(new Error(`Conexão WebSocket CDP encerrada durante ${entry.method}`));
      }
      this._pending.clear();
    };

    ws.addEventListener('message', this._onMessage);
    ws.addEventListener('close', this._onClose);
    ws.addEventListener('error', this._onClose);
  }

  /**
   * Envia um comando bruto do protocolo CDP com timeout máximo de 20 s.
   */
  send(method, params = {}, timeoutMs = DEFAULT_CDP_TIMEOUT_MS) {
    if (this._closed || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error(`WebSocket CDP não está aberto ao chamar ${method}`));
    }
    const limitMs = clampTimeout(timeoutMs, DEFAULT_CDP_TIMEOUT_MS);
    const id = this._nextId++;

    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        this._pending.delete(id);
        rejectPromise(new Error(`Timeout de ${limitMs} ms em CDP ${method}`));
      }, limitMs);

      this._pending.set(id, {
        method,
        timer,
        resolve: resolvePromise,
        reject: rejectPromise,
      });

      try {
        this.ws.send(JSON.stringify({ id, method, params }));
      } catch (err) {
        clearTimeout(timer);
        this._pending.delete(id);
        rejectPromise(new Error(`Falha ao enviar comando CDP ${method}: ${err.message}`));
      }
    });
  }

  /**
   * Avalia uma expressão JavaScript na página (aguardando Promises) e retorna o valor serializado.
   */
  async evaluate(expr, timeoutMs = DEFAULT_CDP_TIMEOUT_MS) {
    const limitMs = clampTimeout(timeoutMs, DEFAULT_CDP_TIMEOUT_MS);
    const cleanExpr = String(expr).startsWith('js:') ? String(expr).slice(3) : String(expr);
    const res = await this.send(
      'Runtime.evaluate',
      {
        expression: cleanExpr,
        returnByValue: true,
        awaitPromise: true,
        userGesture: true,
      },
      limitMs
    );

    if (res.exceptionDetails) {
      throw new Error(formatExceptionDetails(res.exceptionDetails));
    }
    if (!res.result) return undefined;
    if ('value' in res.result) return res.result.value;
    if (res.result.subtype === 'null') return null;
    if (res.result.type === 'undefined') return undefined;
    return res.result.description ?? null;
  }

  /**
   * Aguarda um seletor CSS existir/ficar visível ou uma expressão JS tornar-se truthy (máx. 20 s).
   */
  async waitFor(selectorOrExpr, timeoutMs = 10_000, opts = {}) {
    const limitMs = clampTimeout(timeoutMs, 10_000);
    const deadline = Date.now() + limitMs;
    const intervalMs = Math.min(250, Math.max(80, opts.pollMs || 150));

    const raw =
      typeof selectorOrExpr === 'function'
        ? `js:(${selectorOrExpr.toString()})()`
        : String(selectorOrExpr ?? '').trim();

    if (!raw) {
      throw new Error('waitFor recebeu seletor/expressão vazio.');
    }

    const treatAsJs = isLikelyJsExpression(raw);
    const cleanTarget = raw.replace(/^(js:|css:)/, '');
    let lastErr = null;

    while (Date.now() <= deadline) {
      const remaining = Math.max(250, deadline - Date.now());
      try {
        if (treatAsJs) {
          const val = await this.evaluate(`Boolean(${cleanTarget})`, Math.min(remaining, 4_000));
          if (val) return true;
        } else {
          const requireVisible = opts.visible !== false;
          const checkExpr = `(() => {
            const sel = ${JSON.stringify(cleanTarget)};
            const nodes = Array.from(document.querySelectorAll(sel));
            if (!nodes.length) return false;
            if (!${JSON.stringify(requireVisible)}) return true;
            return nodes.some((el) => {
              const st = window.getComputedStyle(el);
              if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return false;
              const r = el.getBoundingClientRect();
              return r.width > 0 && r.height > 0;
            }) || nodes.some((el) => el.isConnected);
          })()`;
          const found = await this.evaluate(checkExpr, Math.min(remaining, 4_000));
          if (found) return true;
        }
      } catch (err) {
        lastErr = err;
      }
      if (Date.now() + intervalMs > deadline) break;
      await sleep(intervalMs);
    }

    const detail = lastErr ? ` (último erro: ${lastErr.message})` : '';
    throw new Error(`Timeout (${limitMs} ms) aguardando: ${raw}${detail}`);
  }

  /**
   * Clica no elemento correspondente ao seletor CSS (priorizando o exemplar visível).
   */
  async click(selector, timeoutMs = 10_000) {
    const limitMs = clampTimeout(timeoutMs, 10_000);
    const cleanSel = String(selector).replace(/^css:/, '').trim();
    await this.waitFor(`css:${cleanSel}`, limitMs);

    const script = `(() => {
      const sel = ${JSON.stringify(cleanSel)};
      const nodes = Array.from(document.querySelectorAll(sel));
      if (!nodes.length) throw new Error('Elemento não encontrado para clique: ' + sel);
      const isVisible = (el) => {
        const st = window.getComputedStyle(el);
        if (st.display === 'none' || st.visibility === 'hidden') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      const el = nodes.find(isVisible) || nodes[0];
      if (el.disabled) throw new Error('Elemento está desabilitado: ' + sel);
      if (typeof el.scrollIntoView === 'function') {
        el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
      }
      if (typeof el.focus === 'function') {
        try { el.focus({ preventScroll: true }); } catch {}
      }
      if (typeof el.click === 'function') {
        el.click();
      } else {
        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, composed: true, view: window }));
      }
      const r = el.getBoundingClientRect();
      return {
        selector: sel,
        tag: el.tagName.toLowerCase(),
        text: (el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 80),
        rect: { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) }
      };
    })()`;

    return this.evaluate(script, Math.min(limitMs, 8_000));
  }

  /**
   * Preenche um campo (<input>, <textarea>, <select> ou range) disparando eventos input/change reais.
   */
  async type(selector, text, opts = {}) {
    const timeoutMs = typeof opts === 'number' ? opts : opts.timeoutMs ?? 10_000;
    const clear = typeof opts === 'object' && opts.clear !== undefined ? Boolean(opts.clear) : true;
    const submit = typeof opts === 'object' && Boolean(opts.submit);
    const limitMs = clampTimeout(timeoutMs, 10_000);
    const cleanSel = String(selector).replace(/^css:/, '').trim();

    await this.waitFor(`css:${cleanSel}`, limitMs);

    const script = `(() => {
      const sel = ${JSON.stringify(cleanSel)};
      const val = ${JSON.stringify(String(text ?? ''))};
      const shouldClear = ${JSON.stringify(clear)};
      const shouldSubmit = ${JSON.stringify(submit)};
      const nodes = Array.from(document.querySelectorAll(sel));
      if (!nodes.length) throw new Error('Campo não encontrado para digitação: ' + sel);
      const isVisible = (el) => {
        const st = window.getComputedStyle(el);
        if (st.display === 'none' || st.visibility === 'hidden') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      let el = nodes.find(isVisible) || nodes[0];
      if (!['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)) {
        const inner = el.querySelector('input, textarea, select');
        if (inner) el = inner;
      }
      if (typeof el.focus === 'function') el.focus();

      const nextVal = shouldClear ? val : String(el.value || '') + val;
      const proto =
        el.tagName === 'TEXTAREA'
          ? window.HTMLTextAreaElement?.prototype
          : el.tagName === 'SELECT'
            ? window.HTMLSelectElement?.prototype
            : window.HTMLInputElement?.prototype;
      const setter = proto && Object.getOwnPropertyDescriptor(proto, 'value')?.set;
      if (setter) setter.call(el, nextVal);
      else el.value = nextVal;

      el.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, data: val, inputType: 'insertText' }));
      el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));

      if (shouldSubmit) {
        el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
        el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      }
      return { selector: sel, tag: el.tagName.toLowerCase(), value: el.value };
    })()`;

    return this.evaluate(script, Math.min(limitMs, 8_000));
  }

  /**
   * Envia comando para a ponte nativa `HondaBridge.send(JSON.stringify({ cmd, args }))`.
   */
  async sendBridge(cmd, args = {}, timeoutMs = 8_000) {
    const limitMs = clampTimeout(timeoutMs, 8_000);
    const payload = { cmd: String(cmd), args: args ?? {} };
    const script = `(() => {
      const msg = ${JSON.stringify(payload)};
      const raw = JSON.stringify(msg);
      if (window.HondaBridge && typeof window.HondaBridge.send === 'function') {
        window.HondaBridge.send(raw);
        return { via: 'HondaBridge.send', payload: msg };
      }
      if (window.HD && typeof window.HD.send === 'function') {
        window.HD.send(msg.cmd, msg.args);
        return { via: 'HD.send', payload: msg };
      }
      throw new Error('HondaBridge.send não está disponível na página.');
    })()`;
    return this.evaluate(script, limitMs);
  }

  /**
   * Lê o último estado completo recebido pela interface web (`window.HD.lastState`).
   */
  async getState(timeoutMs = 8_000) {
    return this.evaluate(
      'window.HD && window.HD.lastState !== undefined ? window.HD.lastState : null',
      timeoutMs
    );
  }

  /**
   * Retorna os erros coletados em `Runtime.consoleAPICalled` (error/assert) e `Runtime.exceptionThrown`.
   */
  consoleErrors() {
    return [...this._consoleErrors];
  }

  clearConsoleErrors() {
    this._consoleErrors.length = 0;
  }

  /**
   * Captura PNG da página via `Page.captureScreenshot` e salva no caminho indicado.
   */
  async screenshot(file, timeoutMs = 15_000) {
    const limitMs = clampTimeout(timeoutMs, 15_000);
    const outFile = resolve(file);
    mkdirSync(dirname(outFile), { recursive: true });

    const res = await this.send('Page.captureScreenshot', { format: 'png' }, limitMs);
    if (!res?.data) {
      throw new Error('Page.captureScreenshot não retornou dados base64.');
    }
    const buf = Buffer.from(res.data, 'base64');
    writeFileSync(outFile, buf);
    return { file: outFile, bytes: buf.length, method: 'cdp' };
  }

  /**
   * Encerra o WebSocket e opcionalmente remove o redirecionamento ADB sem travar o event loop.
   */
  async close({ removeAdbForward = false } = {}) {
    if (!this._closed) {
      this._closed = true;
      await new Promise((resolveClose) => {
        const timer = setTimeout(resolveClose, 500);
        this.ws.addEventListener(
          'close',
          () => {
            clearTimeout(timer);
            resolveClose();
          },
          { once: true }
        );
        try {
          this.ws.close();
        } catch {
          clearTimeout(timer);
          resolveClose();
        }
      });
    }
    if (removeAdbForward && this.meta.port) {
      await removeForward(this.meta.port, this.meta.adbOpts).catch(() => {});
    }
  }
}

/**
 * Descobre o PID do app, faz `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
 * e abre sessão CDP na página principal da WebView (máx. 20 s no total).
 */
export async function connectWebView(opts = {}) {
  const {
    port = DEFAULT_CDP_PORT,
    packageName = APP_PACKAGE,
    pid: explicitPid = null,
    skipAdbForward = false,
    urlFilter = 'appassets.androidplatform.net',
    timeoutMs = MAX_CALL_TIMEOUT_MS,
    adbOpts = {},
  } = opts;

  const limitMs = clampTimeout(timeoutMs, MAX_CALL_TIMEOUT_MS);
  const deadline = Date.now() + limitMs;

  let pid = explicitPid;
  if (!skipAdbForward) {
    while (!pid && Date.now() < deadline) {
      pid = await pidof(packageName, {
        ...adbOpts,
        timeoutMs: Math.min(5_000, Math.max(500, deadline - Date.now())),
      });
      if (!pid) await sleep(400);
    }
    if (!pid) {
      throw new Error(`Processo ${packageName} não encontrado via adb shell pidof.`);
    }
    await forwardTcp(port, `webview_devtools_remote_${pid}`, {
      ...adbOpts,
      timeoutMs: Math.min(6_000, Math.max(500, deadline - Date.now())),
    });
  }

  let chosenTarget = null;
  let lastListErr = null;
  while (Date.now() < deadline) {
    try {
      const targets = await listTargets(
        port,
        Math.min(4_000, Math.max(500, deadline - Date.now()))
      );
      const pages = targets.filter((t) => t.webSocketDebuggerUrl && (t.type === 'page' || !t.type));
      chosenTarget =
        pages.find((t) => urlFilter && String(t.url || '').includes(urlFilter)) ||
        pages.find((t) => !String(t.url || '').startsWith('devtools://')) ||
        pages[0] ||
        null;
      if (chosenTarget) break;
    } catch (err) {
      lastListErr = err;
    }
    await sleep(350);
  }

  if (!chosenTarget) {
    throw new Error(
      `Nenhuma página WebView disponível em http://127.0.0.1:${port}/json${
        lastListErr ? ` (${lastListErr.message})` : ''
      }`
    );
  }

  const wsUrl = chosenTarget.webSocketDebuggerUrl;
  const wsRemaining = Math.max(1_000, deadline - Date.now());

  const ws = await new Promise((resolveWs, rejectWs) => {
    const sock = new WebSocket(wsUrl);
    const timer = setTimeout(() => {
      try {
        sock.close();
      } catch {}
      rejectWs(new Error(`Timeout (${wsRemaining} ms) ao abrir WebSocket CDP: ${wsUrl}`));
    }, wsRemaining);

    sock.addEventListener(
      'open',
      () => {
        clearTimeout(timer);
        resolveWs(sock);
      },
      { once: true }
    );
    sock.addEventListener(
      'error',
      (ev) => {
        clearTimeout(timer);
        rejectWs(
          new Error(`Erro ao conectar WebSocket CDP (${wsUrl}): ${ev?.message || 'falha de rede'}`)
        );
      },
      { once: true }
    );
  });

  const session = new CdpSession(ws, chosenTarget, { pid, port, adbOpts });
  try {
    await session.send('Runtime.enable', {}, 5_000);
    await session.send('Page.enable', {}, 5_000);
  } catch (err) {
    await session.close();
    throw err;
  }

  return session;
}

// Uso direto via CLI para inspeção rápida na Fase 7:
// node tools/audit/cdp.mjs <targets|state|eval|click|type|screenshot|errors> [args...]
const isMain =
  process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));

if (isMain) {
  const [, , subcmd = 'state', arg1, arg2] = process.argv;
  (async () => {
    const session = await connectWebView();
    try {
      switch (subcmd) {
        case 'targets':
          console.log(JSON.stringify(await listTargets(), null, 2));
          break;
        case 'state':
          console.log(JSON.stringify(await session.getState(), null, 2));
          break;
        case 'eval':
          if (!arg1) throw new Error('Uso: node cdp.mjs eval "<expressao>"');
          console.log(JSON.stringify(await session.evaluate(arg1), null, 2));
          break;
        case 'click':
          if (!arg1) throw new Error('Uso: node cdp.mjs click "<seletor>"');
          console.log(JSON.stringify(await session.click(arg1), null, 2));
          break;
        case 'type':
          if (!arg1) throw new Error('Uso: node cdp.mjs type "<seletor>" "<texto>"');
          console.log(JSON.stringify(await session.type(arg1, arg2 ?? ''), null, 2));
          break;
        case 'screenshot':
          if (!arg1) throw new Error('Uso: node cdp.mjs screenshot <arquivo.png>');
          console.log(JSON.stringify(await session.screenshot(arg1), null, 2));
          break;
        case 'errors':
          await sleep(500);
          console.log(JSON.stringify(session.consoleErrors(), null, 2));
          break;
        default:
          throw new Error(
            `Subcomando desconhecido: ${subcmd}. Use: targets | state | eval | click | type | screenshot | errors`
          );
      }
    } finally {
      await session.close();
    }
  })().catch((err) => {
    console.error(`[cdp.mjs] ERRO: ${err.message}`);
    process.exit(1);
  });
}
