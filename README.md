# AutoDeck — Central Automotiva Unificada

O **AutoDeck** é uma central multimídia Android projetada para rodar em **tablet Samsung** conectado a um **Hub USB-C com Power Delivery**, unificando em **um único aplicativo** ("configure uma vez e funcione sozinho"):

1. **Projeção Apple CarPlay + Android Auto (Zero Cliques)**
   - Integração USB nativa com dongle **Carlinkit CPC200-CCPA** alinhada às referências open-source [`lvalen91/carlink`](https://github.com/lvalen91/carlink) e [`lvalen91/CPC200-CCPA_resources`](https://github.com/lvalen91/CPC200-CCPA_resources).
   - Handshake completo (`SEND_FILE`, `BOX_SETTINGS`, Wi-Fi 5 GHz, `/etc/android_work_mode=1` e auto-reconexão `WIFI_CONNECT` sem precisar abrir nada no celular), `MediaCodec` H.264 em thread dedicada, multitouch, áudio PCM multicanal em `USAGE_MEDIA` + microfone para Siri/Google/ligações.
   - Modo transição com atalho direto para o **HUR** enquanto o dongle não estiver conectado.
2. **Câmera de Ré com Detecção Automática de Sinal**
   - Preparado para conversor **EasyCap / UVC multi-RCA → USB** com permissão `CAMERA`, `TextureView` dedicado e detector híbrido (variância espacial + ruído analógico temporal para funcionar inclusive à noite + calibração "Sem Sinal").
   - Sobe automaticamente em **100% tela cheia** ao engatar a ré (inclusive por cima do HUR via `SYSTEM_ALERT_WINDOW` quando concedido), com linhas guia calibráveis e histerese anti-pisca.
3. **Leitor OBD-II Bluetooth Classic (ELM327 azulzinho v1.5 / v2.1)**
   - **Dock Lateral Permanente**: exibe **Temperatura do Motor (°C)**, **Consumo (km/L ou L/h parado)** e **Voltagem da Bateria (V)**.
   - **Scanner Detalhado**: clique no cartão OBD-II do dock para abrir todos os sensores, autodetecção de MAF/MAP via `0100`, luz de injeção (`0101` MIL) e leitura/limpeza de falhas **DTC** (`03 / 07 / 0A`, inclusive CAN multi-frame).
4. **Convivência Bluetooth & Serviço Persistente**
   - `HeadUnitForegroundService` (`connectedDevice`) + opção de **Launcher (`HOME`)** no tablet.
   - Zero `startDiscovery()` após a 1ª configuração + Wi-Fi 5 GHz no Carlinkit + backoff inteligente do OBD-II para nunca picotar o áudio enviado pelo transceptor 2.4 BT ao **processador Taranis**.

---

## Estrutura da Pasta (`E:\Projetos\AutoDeck`)

```text
E:\Projetos\AutoDeck\
├── README.md
├── docs/
│   ├── ARQUITETURA.md                 # Diagrama de tela, serviço persistente e fluxos
│   ├── CONEXOES_E_ISOLAMENTO.md       # Cabeamento USB-C/PD e matriz de convivência BT/Wi-Fi/USB
│   └── ROADMAP_E_SETUP.md             # Fases, configuração no Android Studio (E:) e checklist de bancada
├── gradle/
│   ├── libs.versions.toml             # AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2025.05.00
│   └── wrapper/                       # Gradle Wrapper 9.3.1 (gradlew / gradlew.bat)
├── core-protocol/                     # Módulo Kotlin JVM puro (26 testes JUnit passando)
└── app/                               # Aplicativo Android (Kotlin + Jetpack Compose + ForegroundService)
```

---

## Como Rodar os Testes e Abrir no Android Studio

### 1. Rodar os testes de protocolo e lógica (JVM puro — sem Android SDK)
No PowerShell em `E:\Projetos\AutoDeck`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME = "E:\Projetos\.gradle-home"
$env:TEMP = "E:\Projetos\.gradle-home\tmp"
$env:TMP = "E:\Projetos\.gradle-home\tmp"
.\gradlew.bat :core-protocol:test -PonlyJvm=true --info
```

### 2. Compilar e instalar o APK no Tablet Samsung
Consulte [docs/ROADMAP_E_SETUP.md](docs/ROADMAP_E_SETUP.md) para apontar o Android SDK em `E:\Projetos\AndroidSdk` no Android Studio, gerar o APK e testar no tablet com o **Simulador** e o **Inspetor USB**.
