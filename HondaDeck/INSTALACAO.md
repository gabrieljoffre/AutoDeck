# Instalação do HondaDeck no tablet

**Padrão: instalar pelo USB, em casa, com o notebook ligado ao tablet.**
O QR code é só a alternativa para quando não há computador por perto.

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
3. No app, **Configurações → Agente**: colar a chave do Gemini (se o build não tiver uma, veja abaixo).

## Instalar pelo QR code (alternativa)

Ler o QR code da página de instalação, ou abrir no tablet:
`https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/HondaDeck.apk`

O link é fixo: cada build no GitHub substitui o arquivo. Esta versão não tem WhatsApp nem capa/título da música.

## Chaves de API

**Os valores das chaves não ficam neste repositório (ele é público).**
Eles estão no documento privado **"HondaDeck – Chaves de API"**, no Google Drive da conta dona
do projeto (a mesma do Google Cloud `hondadeck`). Acesso restrito: só o dono abre.

| Chave | Para que serve | Onde entra | Onde ver ou gerar outra |
|---|---|---|---|
| **Google AI Studio (Gemini)** | Copilot de voz/chat (Gemini + pesquisa Google) | `HondaDeck/local.properties` → `GEMINI_API_KEY=...` (build no notebook), ou no tablet em Configurações → Agente | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) |
| **HondaDeck Maps Android** | Mapa, busca de endereço e rotas do Google (ainda não usado pelo app) | `HondaDeck/local.properties` → `MAPS_API_KEY=...` | Google Cloud → projeto `hondadeck` → APIs e serviços → Credenciais |

A chave do Maps é restrita ao app Android `com.autodeck.hondadeck` com o SHA-1 do keystore de debug
(`95:EB:E7:B0:A4:81:B8:E8:2C:90:5F:2B:F8:02:46:BB:F1:14:09:A3`) e às APIs Maps SDK for Android,
Places (New) e Routes. Alerta de orçamento do projeto: R$ 30/mês (e-mail em 50% e 100%).

Se uma chave vazar: gere outra no link da tabela, apague a antiga, atualize o documento do Drive
e o `local.properties`.

- **Build local (USB em casa):** a chave do Gemini pode ficar em `HondaDeck/local.properties`
  (fora do Git) na linha `GEMINI_API_KEY=...`; o APK já sai configurado. **Não publique esse APK.**
- **Builds do GitHub (release pública):** saem **sem** chave, de propósito. O repositório é público e
  qualquer pessoa poderia extrair a chave do APK. A chave é digitada uma vez em Configurações → Agente
  e fica salva só no tablet.

## Links fixos da release `hondadeck-latest`

- USB: `https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/HondaDeck-completo.apk`
- QR: `https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/HondaDeck.apk`
