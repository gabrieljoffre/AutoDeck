# Kit de Auditoria Automática no Tablet — HondaDeck 3.0 (`tools/audit`)

Kit sem dependências externas (Node 22+ nativo com `WebSocket` global + ADB) para testar a interface web **Órbita** e a ponte Kotlin no tablet Galaxy Tab A7 (`SM-T500`, `2000x1200` paisagem).

## Execução rápida

```powershell
# Auditoria completa (tablet via USB; gera relatório JSON + Markdown em D:\car\multimidia\HondaDeck\results\v3\)
.\tools\audit\audit.ps1

# Instalar o APK debug antes de rodar
.\tools\audit\audit.ps1 -Install

# Teste a seco (valida scenarios.json, comandos da ponte e sintaxe JS sem precisar do tablet/app)
.\tools\audit\audit.ps1 -DryRun

# Rodar apenas um cenário específico durante correções
.\tools\audit\audit.ps1 -Only "03-mapa"
```

## Arquivos

- `adb.mjs` — Wrappers não interativos com timeout estrito (máx. 20 s) para `D:\car\multimidia\.tools\android-sdk\platform-tools\adb.exe`: `installApk`, `startApp`, `pidof`, `screencap`, `logcatErrors` (`FATAL|ANR|AndroidRuntime|chromium.*Uncaught`), `checkScreenLock` (`isKeyguardShowing` / `mDreamingLockscreen`) e `keepScreenOn` (`svc power stayon true`). Nunca digita PIN/senha nem apaga dados do app.
- `cdp.mjs` — Cliente Chrome DevTools Protocol sobre o `WebSocket` global do Node 22+: redireciona `tcp:9222 -> localabstract:webview_devtools_remote_<pid>`, lê `http://127.0.0.1:9222/json` e expõe `evaluate`, `click`, `type`, `waitFor`, `getState`, `consoleErrors` e `screenshot` (timeout máx. 20 s por chamada).
- `run-audit.mjs` — Executor de cenários (`scenarios.json`) que gera `D:\car\multimidia\HondaDeck\results\v3\auditoria-<data>.json` e `auditoria-<data>.md` com tabela `OK`/`FALHA`/`PENDENTE` e capturas de evidência.
- `scenarios.json` — Roteiro completo cobrindo todos os itens de `.agy/fase7-auditoria-tablet.md`.
- `audit.ps1` — Script de um comando para rodar validação/auditoria pelo PowerShell.

---

## Convenções obrigatórias para a Interface Web (`app/src/main/assets/web/`)

### 1. Estado exposto em `window.HD.lastState`
A interface web **deve** manter o último estado recebido do Kotlin acessível em `window.HD.lastState`:
- Ao receber `window.HD.receive({ type: "state", payload })`, atribuir `window.HD.lastState = payload`.
- Ao receber `window.HD.receive({ type: "telemetry", payload })`, atualizar também `Object.assign(window.HD.lastState.telemetry, payload)` se `window.HD.lastState?.telemetry` já existir.

### 2. Atributos `data-test="..."` esperados no DOM

Abaixo estão todos os seletores `data-test` usados por `scenarios.json` (se houver exemplares na tela inicial e nos modos expandidos 2/3, o `cdp.mjs` prioriza automaticamente o elemento visível):

#### Abertura e Velocímetro
| `data-test` | Elemento |
|---|---|
| `intro` | Overlay de abertura `H -> HONDA` ou botão do emblema `H / HONDA` central no topo (repete animação ao toque) |
| `gauge-speed` | Número principal da velocidade (`KM/H`) no velocímetro |
| `gauge-rpm` | Indicador textual de rotação (`x.x ×1000 RPM`) |
| `gear-N`, `gear-1`, `gear-2`, `gear-3`, `gear-4`, `gear-5` | Indicadores individuais de marcha (`gear-*`) |
| `fuel` | Barra/marcador de nível de combustível abaixo do velocímetro |

#### Mapa e Navegação
| `data-test` | Elemento |
|---|---|
| `map-card` | Botão/área clicável do cartão de mapa no Início que abre o modo Mapa 2/3 |
| `map-search` | Campo `<input>` de busca de destino/local (no Início e/ou modo Mapa 2/3) |
| `map-result-0` (`map-result-1`, ...) | Itens de resultado da busca de lugares (`0` = primeiro resultado clicável) |
| `route-stop` | Botão "Encerrar rota" (`map.stopRoute`) visível quando há rota ativa |
| `mode-close` | Botão `X` para fechar o modo atual (Mapa 2/3, Música 2/3 ou Ajustes) e voltar ao Início |

#### Música / Spotify
| `data-test` | Elemento |
|---|---|
| `music-card` | Botão/área de expandir o cartão do Spotify no Início para o modo Música 2/3 |
| `music-prev` | Botão faixa anterior (`music.prev`) |
| `music-toggle` | Botão tocar/pausar (`music.toggle`) |
| `music-next` | Botão próxima faixa (`music.next`) |
| `music-search` | Campo `<input>` de busca no Spotify |
| `music-result-0` (`music-result-1`, ...) | Itens de resultado da busca do Spotify (`0` = primeiro resultado clicável) |

#### Dock, Copiloto e Áudio
| `data-test` | Elemento |
|---|---|
| `dock-home` | Botão da aba Início na dock |
| `dock-map` | Botão da aba Mapa na dock |
| `dock-music` | Botão da aba Música na dock |
| `dock-settings` | Botão da aba Ajustes na dock |
| `dock-copilot` | Botão `COPILOTO` na dock (`copilot.listen`) |
| `volume` | `<input type="range">` de volume na dock (`audio.setVolume`) |
| `mute` | Botão silenciar/reativar som na dock (`audio.toggleMute`) |

#### Ajustes (`settings-*`, `accent-*`, `fav-*`, `fuel-log-*`, `dtc-*`)
| `data-test` | Elemento |
|---|---|
| `settings-panel` | Contêiner principal da tela de Ajustes |
| `settings-tab-appearance` | Botão "Aparência" no menu lateral de Ajustes |
| `settings-tab-favorites` | Botão "Locais favoritos" no menu lateral de Ajustes |
| `settings-tab-spotify` | Botão "Spotify" no menu lateral de Ajustes |
| `settings-tab-copilot` | Botão "Copiloto" no menu lateral de Ajustes |
| `settings-tab-vehicle` | Botão "Veículo" no menu lateral de Ajustes |
| `settings-tab-system` | Botão "Sistema" no menu lateral de Ajustes |
| `settings-obd-simulator` | Toggle/botão do simulador OBD em Ajustes › Veículo (`obd.setSimulator`) |
| `settings-maintenance` | Seção/lista de Manutenção em Ajustes › Veículo (`vehicle.maintenance.items`) |
| `settings-offline-map` | Botão "Baixar mapa offline" em Ajustes › Sistema (`system.downloadOfflineMap`) |
| `settings-camera-test` | Botão "Testar câmera" em Ajustes › Sistema (`camera.toggle`) |
| `accent-0`, `accent-1`, `accent-2`, `accent-3`, `accent-4` | Botões de cor de destaque na ordem de `settings.accentOptions` (`ui.setAccent`) |
| `fav-list` | Lista de locais favoritos salvos |
| `fav-item-0`, `fav-edit-0`, `fav-delete-0` | Item, botão editar e botão remover do favorito no índice `0` (e `1`, `2`...) |
| `fav-name` | `<input>` Nome do novo favorito |
| `fav-address` | `<input>` Endereço do novo favorito |
| `fav-voice` | `<input>` Comando de voz do favorito (se presente no formulário) |
| `fav-use-current` | Botão "Usar local atual" (`fav.useCurrentLocation`) |
| `fav-save` | Botão "Salvar favorito" (`fav.save`) |
| `fuel-log-list` | Lista de entradas do diário de abastecimento |
| `fuel-log-liters` | `<input>` Litros abastecidos |
| `fuel-log-price` | `<input>` Preço por litro |
| `fuel-log-odometer` | `<input>` Hodômetro / km |
| `fuel-log-full` | Checkbox/toggle de tanque cheio |
| `fuel-log-add` | Botão de registrar abastecimento (`fuel.log`) |
| `fuel-log-delete-0` | Botão excluir do primeiro registro do diário (`fuel.delete`) |
| `dtc-read` | Botão "Ler falhas" (`obd.readDtc`) |
| `dtc-clear` | Botão "Limpar falhas" (`obd.clearDtc`) |
| `dtc-confirm` | Botão de confirmação ao limpar falhas (se houver confirmação) |
| `dtc-list` | Lista/estado dos códigos de falha DTC (`vehicle.dtcCodes`) |
