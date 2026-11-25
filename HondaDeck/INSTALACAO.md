# Instalação do HondaDeck no tablet

**Padrão: instalar pelo USB, em casa, com o notebook ligado ao tablet.**
O QR code é só a alternativa para quando não há computador por perto.

## Arquitetura (HondaDeck 3.0 — Órbita)

- **Interface Web (WebView + `WebViewAssetLoader`)**: layout **Órbita** em `app/src/main/assets/web/` (HTML/CSS/JS + Leaflet local e fontes `woff2` offline, sem CDN externo), servido em `https://appassets.androidplatform.net/` com tiles Esri Dark Gray cacheados em disco (`/tiles/` e `/labels/`) e capa da música (`/cover/current.jpg`).
- **Ponte Bidirecional (`WebBridge` + `StateDto`)**: `window.HondaBridge.send(json)` recebe comandos da UI web e `window.HD.receive(...)` publica estado (`state` conflatado, `telemetry` a ~10 Hz e `event`).
- **Camadas Compose Mantidas**: apenas assistente de 1ª configuração (`SetupWizardScreen`), vídeo de partida opcional (`BootSplashOverlay`), overlay prioritário de câmera de ré / alertas OBD / standby (`ReverseCameraOverlay`) e cápsula flutuante sobre apps externos (`FloatingHudOverlayManager`).
- **Backend Nativo Kotlin (`HeadUnitRuntime`)**: OBD-II ELM327 Turbo, Spotify App Remote + Web API, GPS e rota curva-a-curva no app (OSRM/Nominatim), câmera de ré USB STK1160, clima Open-Meteo, diário de abastecimentos e Copiloto híbrido (regras locais + Gemini com Function Calling).

## As duas versões

| | **completo** (padrão) | **qr** (alternativa) |
|---|---|---|
| Como instala | USB, pelo notebook (ADB / Android Studio) | QR code ou link no navegador do tablet |
| WhatsApp (ler e responder por voz) | Sim | Não |
| Capa e título da música | Qualquer app de música | Não |
| Serviço de notificações no manifesto | Sim (`src/completo/AndroidManifest.xml`) | Não |

As duas têm o mesmo pacote (`com.autodeck.hondadeck`) e a mesma assinatura (keystore de debug):
uma atualiza a outra e os dados continuam (configurações, chave do Gemini, diário de abastecimento).
**Evite instalar a versão do QR por cima da completa**: o WhatsApp para de funcionar até reinstalar pelo USB.

### Por que existem duas

O WhatsApp e a capa/título da música dependem de um `NotificationListenerService`
(`BIND_NOTIFICATION_LISTENER_SERVICE`). Com ele no manifesto, o Android **bloqueia a instalação**
de APKs baixados pela internet (navegador, QR code, mensagem, gerenciador de arquivos). Pelo USB (ADB),
a instalação funciona.

Por isso o serviço fica **só** em `app/src/completo/AndroidManifest.xml`, nunca em `src/main`.
O teste `NectarLinkAndMobileAgentTest` falha se alguém colocar o serviço em `src/main`, e o workflow
`build-hondadeck.yml` confere os dois APKs gerados (o do QR sem o serviço; o completo com ele).

## Instalar pelo USB (padrão)

### Uma vez só, no tablet (Samsung SM-T500)

1. **Configurações → Sobre o tablet → Informações do software** → tocar 7 vezes em **Número de compilação**.
2. **Configurações → Opções do desenvolvedor** → ligar **Depuração USB**.
3. Se existir **Configurações → Segurança → Bloqueador automático**, desligar (ele bloqueia o ADB).
4. Ligar o cabo no notebook. No aviso **"Permitir depuração USB?"**, marcar *Sempre permitir* e confirmar.

### Cada instalação ou atualização, no notebook

Dentro da pasta `HondaDeck`:

```powershell
.\scripts\instalar-usb.ps1                      # compila no notebook e instala a versão completa
.\scripts\instalar-usb.ps1 -DoGitHub            # baixa o HondaDeck-completo.apk mais novo e instala
.\scripts\instalar-usb.ps1 -AbrirNotificacoes   # instala e abre "Acesso a notificações" no tablet
```

Pelo Android Studio: **Build Variants → `completoDebug`** (já é o padrão) e **Run**.
Pelo Gradle direto: `.\gradlew.bat :app:installCompletoDebug`.

### Primeira vez depois de instalar

1. **Configurações → Notificações → Acesso a notificações → HondaDeck** → ativar.
2. Se a opção estiver cinza: **Configurações → Apps → HondaDeck → ⋮ → Permitir configurações restritas**,
   e tentar o passo 1 de novo.
3. No app, **Ajustes → Copiloto (voz)**: colar a chave do Gemini (se o build não tiver uma, veja abaixo).

## Instalar pelo QR code (alternativa)

Ler o QR code da página de instalação, ou abrir no tablet:
`https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/HondaDeck.apk`

O link é fixo: cada build no GitHub substitui o arquivo. Esta versão não tem WhatsApp nem capa/título da música.

## Chaves de API

**Os valores das chaves não ficam neste repositório (ele é público).**
Eles estão no documento privado **"HondaDeck – Chaves de API"**, no Google Drive da conta dona
do projeto (a mesma do Google Cloud `hondadeck`):
[abrir no Drive](https://drive.google.com/file/d/10kXWZS7VPJ4-i-WjGOYPieKt15qoJjI2/view).
Acesso restrito: só o dono abre; para qualquer outra pessoa o link pede permissão.
**Nunca mude o compartilhamento para "Qualquer pessoa com o link"** — este repositório é público.

| Chave | Para que serve | Onde entra | Onde ver ou gerar outra |
|---|---|---|---|
| **Google AI Studio (Gemini)** | Copiloto de voz/chat (Gemini + pesquisa Google) | `HondaDeck/local.properties` → `GEMINI_API_KEY=...` (build no notebook), ou no tablet em Ajustes → Copiloto (voz) | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) |
| **Spotify Client ID** | Spotify App Remote + Web API (busca e playlists) | `HondaDeck/local.properties` → `spotify.clientId=...` | [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) |

- **Build local (USB em casa):** as chaves ficam em `HondaDeck/local.properties`
  (fora do Git); o APK já sai configurado. **Não publique esse APK.**
- **Builds do GitHub (release pública):** saem **sem** chave, de propósito. O repositório é público e
  qualquer pessoa poderia extrair a chave do APK. A chave do Gemini é digitada uma vez em Ajustes → Copiloto (voz)
  e fica salva só no tablet.

## Links fixos da release `hondadeck-latest`

- USB: `https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/HondaDeck-completo.apk`
- QR: `https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/HondaDeck.apk`
