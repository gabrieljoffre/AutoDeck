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

## Atualizar pelo próprio tablet (botão "Atualizar app")

Só na versão **completo**. Depois de uma instalação pelo USB com este recurso, as próximas
atualizações podem ser feitas no tablet: **Ajustes → Sistema → Atualizar app → Atualizar agora**.

- O app lê `version.json` da release `hondadeck-latest`, baixa o `HondaDeck-completo.apk` e abre o
  instalador do Android (você só toca em **Atualizar**).
- Na primeira vez o Android pede para liberar **"Permitir desta fonte"** para o HondaDeck.
- O número da versão é o minuto do build (desde 01/01/2026): sempre cresce, no PC de casa e no GitHub.
- O APK do GitHub não tem chaves. A instalação pelo USB (com `local.properties`) guarda as chaves
  dentro do app, e o APK baixado pelo botão continua usando essas chaves salvas.
- Se algo der errado, o USB continua funcionando como sempre.

## Mapa offline de Goiás + DF

O mapa 3D lê as ruas de um arquivo no tablet (`go-df.pmtiles`, ~360 MB, OpenStreetMap via
[Protomaps](https://protomaps.com)), sem depender de internet dentro de GO e DF. Fora dessa área,
usa o mapa online normal. Busca de endereço, rota, trânsito e clima continuam precisando de internet.

O arquivo **não vai para o Git** (tamanho). Fica em `Documents\Mult\_mapas\go-df.pmtiles` e é copiado pelo USB:

```powershell
.\scripts\instalar-usb.ps1 -Mapa       # instala o app e copia o mapa
.\scripts\instalar-usb.ps1 -SoMapa     # só copia o mapa
```

No tablet ele fica em `Android/data/com.autodeck.hondadeck/files/maps/`. Em **Ajustes → Sistema →
Mapa offline** aparece se está instalado. Os nomes de rua usam letras baixadas uma vez com internet
(automático ao abrir o app em casa).

**Gerar ou atualizar o arquivo** (ruas novas; uma vez por ano já basta), com o
[go-pmtiles](https://github.com/protomaps/go-pmtiles/releases):

```powershell
pmtiles extract https://build.protomaps.com/AAAAMMDD.pmtiles go-df.pmtiles --bbox=-53.4,-19.6,-45.8,-12.3 --maxzoom=15
```

(`AAAAMMDD` = data de um build recente, lista em https://maps.protomaps.com/builds/.) Baixa só a região (~360 MB).

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

## HondaDeck Ponte (celular Samsung — módulo `:ponte`)

O app **HondaDeck Ponte** (`com.autodeck.hondadeck.ponte`) roda no celular Samsung e permite ao tablet enviar WhatsApp (texto e áudio), fazer/atender/desligar ligações (linha do celular e voz do WhatsApp) e receber notificações de WhatsApp/SMS — sem precisar de chip nem WhatsApp no tablet.

### Instalação no celular pelo USB
Com os dois aparelhos ligados no USB, use sempre o serial do celular (`adb devices`):
```powershell
.\gradlew.bat :ponte:assembleDebug
adb -s <SERIAL_CELULAR> install -r ponte\build\outputs\apk\debug\ponte-debug.apk
```

### Configuração única no celular
1. Abra o **HondaDeck Ponte** e conceda as permissões de **Contatos**, **Telefone** e **Notificações** pelo checklist na tela.
2. Ative uma única vez **Acesso a notificações** (para repassar WhatsApp/SMS e respostas rápidas).
3. Ative uma única vez o **Serviço de Acessibilidade → HondaDeck Ponte — Envio WhatsApp** (usado apenas na janela de 15 s após um comando do tablet para tocar no botão Enviar e voltar à tela anterior). Se a opção aparecer cinza no Android 13+, abra **Configurações → Apps → HondaDeck Ponte → ⋮ → Permitir configurações restritas**.
4. **Recomendação para o carro (Smart Lock / Estender desbloqueio):** em **Configurações do Samsung → Tela de bloqueio → Estender desbloqueio → Dispositivos confiáveis**, adicione o Bluetooth do carro (ex.: Taramps) para manter o celular desbloqueado enquanto dirige. Se o celular estiver bloqueado, o copiloto avisa no painel.
5. No tablet HondaDeck, abra **Ajustes → Celular**, digite o **PIN de 6 dígitos** mostrado no celular e toque em **Parear celular** (feito uma única vez; depois reconecta sozinho).
