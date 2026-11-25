# HondaDeck Web — Layout "Órbita"

Interface web pura (HTML/CSS/JS em ES modules, sem bundler nem framework) para o painel automotivo HondaDeck 3.0 rodando em WebView no Galaxy Tab A7 paisagem (`1333×800` CSS px, `dpr 1.5`).

## Estrutura

- `index.html` — marcação completa do painel (intro, topo, Início, Mapa 2/3, Spotify 2/3, Ajustes, dock, copiloto, notificação, toasts).
- `css/orbita.css` — tema escuro Órbita, `@font-face` locais (`Chakra Petch` e `Inter`), variáveis dinâmicas `--accent` / `--a10` / `--a15` / `--a20` / `--a35`, animações e responsividade fluida sem rolagem da página.
- `js/` — módulos ES:
  - `main.js` — inicialização, alternância de modos, relógio/clima/status, monitoramento e ligação com a ponte.
  - `bridge.js` — contrato `window.HD.receive({ type, payload })` e `HondaBridge.send(JSON.stringify({ cmd, args }))`, com fallback automático para o mock fora do Android.
  - `intro.js` — abertura animada do emblema/letras `HONDA` (~2,8 s, pulada em `introMode === "VIDEO"`) e replay ao tocar no logo do topo.
  - `background.js` — `<canvas>` 30 fps com grade synthwave em perspectiva na cor `--accent`, velocidade proporcional a `telemetry.speedKmh` e pausa em `document.hidden`.
  - `gauge.js` — velocímetro de dois anéis (RPM 0–8 externo com zonas/ECG animado + velocidade 0–220 interna), marchas `N 1 2 3 4 5` e barra de combustível interpolados por frame.
  - `map.js` — instância única Leaflet 1.9.4 reaproveitada entre Início, Mapa 2/3 e mini-mapa do modo Música; usa `/tiles/{z}/{y}/{x}.png` e `/labels/{z}/{y}/{x}.png` no app real.
  - `music.js` — card Spotify do Início, mini-player e modo Música 2/3 com busca debounced, progresso arrastável, playlists com selo `HONDA` e telas de estado (`NEEDS_AUTH`, `NOT_INSTALLED`, etc.).
  - `copilot.js` — overlay do Copiloto (ouvindo, pensando, cartão de resposta por 8 s com pergunta por texto).
  - `dock.js` — dock inferior (Copiloto, abas, volume com mudo e throttle de 150 ms), banner de notificação e toasts.
  - `settings.js` — ajustes em tela cheia (Aparência, Locais favoritos, Spotify, Copiloto, Veículo com OBD/DTC/tanque/diário/manutenção, Sistema) e barra lateral rápida.
  - `util.js` — helpers DOM seguros (`textContent`), cores, formatação pt-BR e ícones SVG.
- `vendor/` — Leaflet 1.9.4 offline (`leaflet.js`, `leaflet.css`, `images/`).
- `fonts/` — fontes locais `Chakra Petch` (400–700) e `Inter` (400–600) em `.woff2`.
- `mock/mock-backend.js` — backend simulado em Anápolis-GO (`-16.3281, -48.9534`) com telemetria a 10 Hz e tiles Esri Dark Gray para testes no navegador do PC.

## Como testar no PC

```powershell
python -m http.server 8765 --directory app/src/main/assets/web
```

Abra `http://localhost:8765/index.html` com viewport `1333×800`. Sem `window.HondaBridge`, o `bridge.js` carrega automaticamente o `mock/mock-backend.js`.
