# Arquitetura do AutoDeck

## 1. Layout de Tela (Paisagem / Landscape)

O tablet opera em paisagem (`sensorLandscape`) com tela cheia imersiva e compatibilidade com Android 16 em telas grandes (`PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` + `configChanges` completos para periféricos no hub não reiniciarem a tela).

```text
+-----------------+----------------------------------------------------------------+
|  DOCK LATERAL   |                      ÁREA PRINCIPAL                          |
|  (fixo ~196dp)  |                                                                |
|                 |  • Camada Base SEMPRE montada (ProjectionPanel SurfaceView):   |
|  14:35          |    Projeção H.264 Carlinkit na resolução útil exata (múltiplo  |
|  ●BT ●OBD ●CAM  |    de 16 até 1920x1080) — nunca é destruída ao trocar de aba.  |
|  ●CP            |                                                                |
|                 |  • Abas [Scanner OBD2] e [Config / USB] sobrepõem sem destruir |
| +-------------+ |    a SurfaceView do CarPlay/Android Auto.                      |
| | 🌡  89 °C   | |                                                                |
| | ⛽ 11.4 km/L | |  ┌──────────────────────────────────────────────────────────┐  |
| | 🔋 14.1 V   | |  │ OVERLAY PRIORITÁRIO DE RÉ (100% TELA CHEIA + TextureView)│  |
| +-------------+ |  │ Sobe sobre qualquer aba — e sobre outros apps (HUR) via  │  |
|  (clique abre   |  │ SYSTEM_ALERT_WINDOW se concedido — com linhas guia.      │  |
|   Scanner)      |  └──────────────────────────────────────────────────────────┘  |
+-----------------+----------------------------------------------------------------+
```

---

## 2. Separação Modular e Referências Open-Source Aproveitadas

### Módulo `:core-protocol` (Kotlin JVM Puro — 26 testes JUnit)
- **`carplay/Cpc200Protocol.kt`** *(baseado em `lvalen91/carlink` e `CPC200-CCPA_resources`)*:
  - Handshake completo (`buildFullInitSequence`: `screen_dpi`, `OPEN` normalizado múltiplo de 16, `night_mode`, `box_name`, `BOX_SETTINGS`, `WIFI_ENABLE`, `WIFI_5G`, `MIC`/`BOX_MIC`, `AUDIO_TRANSFER_OFF`, `/etc/android_work_mode=1`).
  - Auto-reconexão `WIFI_CONNECT` (`buildAutoConnectPacket`), toque `(action, x, y, flags)`, multitouch (`0x17`), áudio por `decodeType` (8/16/24/44,1/48 kHz filtrando pacotes de controle/volume de 13/16 bytes), áudio do microfone (`buildMicAudioPacket`) e ressincronização no magic `0x55AA55AA`.
- **`obd2/`** *(inspirado em `eltonvs/kotlin-obd-api` e `fr3ts0n/AndrOBD`)*:
  - Parser ELM327 com remontagem CAN ISO-TP multi-frame (`0: 43 ... / 1: ...`) que não trunca códigos contendo `43` (ex: `P0443`), modos `03 / 07 / 0A`, autodetecção MAP/MAF pelo bitmask `0100`, MIL `0101`, confirmação `44` no `Mode 04`.
  - Cálculo de consumo `km/L` e `L/h` com fallback Speed-Density, correção STFT/LTFT, cut-off de injeção e persistência da viagem.
  - Alertas com sustentação mínima na partida (ignora queda rápida no arranque) e backoff até 60s sem pós-chave.
- **`camera/`**:
  - `VideoSignalDetector`: analisa variância espacial + calibração "Sem Sinal" + **ruído analógico temporal entre frames** (`temporalDiff >= 1.2` com `stdDev >= 0.8`) para detectar ré mesmo à noite sem disparar em telas estáticas.
  - `ReverseStateMachine`: 3 frames para subir, 1,2s de histerese na perda de sinal.
- **`coordinator/HeadUnitCoordinator.kt`**: árbitro central garantindo prioridade da câmera de ré, bloqueio de `btDiscoveryAllowed`, tipo de telefone conectado (`CarPlay` / `Android Auto`) e controle de alerta dispensável.

### Módulo `:app` (Android + Compose + Serviço Persistente)
- **`service/HeadUnitRuntime.kt` + `HeadUnitForegroundService.kt`**: serviço `connectedDevice` dono das engines de USB, OBD-II, Áudio e Câmera, mantendo tudo ativo mesmo quando outro app (ex: HUR) está aberto, ouvindo energia pós-chave dinamicamente e exibindo overlay da ré se `SYSTEM_ALERT_WINDOW` estiver concedida.
- **`hardware/CarlinkitUsbEngine.kt`**: fila única de escrita USB (`Channel`), `MediaCodec` confinado a uma única thread (`CarlinkitVideo`) solicitando keyframe IDR (`FRAME=12`) ao voltar da ré/trocar `Surface`, thread urgente de áudio (`CarlinkitAudio`) com pool por `audioType`/`decodeType` em `USAGE_MEDIA` e captura `AudioRecord` 16 kHz mono.
- **`hardware/BluetoothObdTransport.kt`**: `Mutex` serializando polling e DTC, drenagem de buffer antes de enviar comando e fallback RFCOMM em 3 níveis (inclusive `createRfcommSocket(1)` para clones v2.1).
- **`hardware/ReverseCameraEngine.kt` + `UsbHubCoordinator.kt`**: verificação de permissão `CAMERA` (obrigatória no Android 10+ para USB `0x0E`), classificação de UVC (`MS2109`) vs EasyCap legado (`UTV007`/`STK1160`/`EM2860`/`Somagic`) e `TextureView` pronto no overlay.
- **`LauncherHomeAlias`** (ativável pelo usuário): permite tornar o AutoDeck a tela inicial padrão (`HOME`) do tablet Samsung.
