# Roadmap e Guia de Setup (`E:\Projetos\AutoDeck`)

## 1. Roadmap do Projeto (4 Fases)

- [x] **Fase 1 — Fundação, Protocolos, Auditoria Opus 5.5, Referências GitHub e Simulador**
  - Projeto migrado para `E:\Projetos\AutoDeck` com **Gradle Wrapper 9.3.1**, **AGP 9.1.1**, **Kotlin 2.2.10** e **Compose BOM 2025.05.00**.
  - Protocolo completo Carlinkit CPC200-CCPA (`lvalen91/carlink`: handshake completo + Wi-Fi 5 GHz + `/etc/android_work_mode=1` + `WIFI_CONNECT` automático + áudio por `decodeType` + microfone + multitouch + IDR ao voltar da ré).
  - OBD-II ELM327 com `Mutex`, fallback RFCOMM em 3 níveis, parser DTC multi-frame (`03 / 07 / 0A` + `P0443`), autodetecção `0100`, MIL `0101` e cálculo de consumo com cut-off e trims.
  - `HeadUnitForegroundService` (`connectedDevice`), opção `LauncherHomeAlias`, overlay de background (`SYSTEM_ALERT_WINDOW`), detecção noturna temporal na câmera e **Inspetor USB ao Vivo**.

- [ ] **Fase 2 — Feed Real do Conversor USB na `ReverseCameraEngine`**
  - Instalar o 1º APK no tablet Samsung, plugar seu conversor EasyCap/multi-RCA e ler o `VID:PID` exato no **Inspetor USB ao Vivo**.
  - Acoplar [`bingshushu/AndroidUSBCamera`](https://github.com/bingshushu/AndroidUSBCamera) (se UVC `MS2109`) ou driver específico (se `UTV007`, ou trocar por adaptador UVC `MS2109`) enviando vídeo real para o `TextureView` do overlay + frames `Y` 16×12 a ~5–7 fps para o `VideoSignalDetector`.

- [ ] **Fase 3 — Teste de Bancada com Carlinkit CPC200-CCPA + iPhone / Android + ELM327**
  - Validar decodificação H.264 real, áudio A2DP no **Taranis**, microfone na Siri/Google e auto-reconexão zero-cliques ao ligar o tablet.

- [ ] **Fase 4 — Acabamento Final no Carro**
  - Calibrar fator de consumo por abastecimento, ajustar limites de temperatura/bateria do seu motor, calibrar linhas guia no para-choque e ativar Modo Launcher + "Apps que nunca dormem" no Samsung.

---

## 2. Configurando o Android Studio e SDK no Disco `E:`

> **Por que usar o disco `E:`?**
> O disco `C:` desta máquina está com apenas ~18 MB livres, enquanto o disco `E:` tem ~15 GB livres. Por isso o projeto já foi movido para **`E:\Projetos\AutoDeck`** e o cache Gradle de testes já está em **`E:\Projetos\.gradle-home`**.

### Passo a passo para gerar o 1º APK e instalar no Tablet Samsung:

1. **Configurar caminhos no Android Studio (1ª vez):**
   - Crie uma pasta `E:\Projetos\AndroidSdk`.
   - Abra o **Android Studio** → **More Actions** (ou *File → Settings*).
   - Em **Languages & Frameworks → Android SDK**, mude o **Android SDK Location** para **`E:\Projetos\AndroidSdk`** e instale **Android SDK Platform 36**, **Build-Tools 36.x** e **Platform-Tools (adb)**.
   - Em **Build, Execution, Deployment → Build Tools → Gradle**, configure o **Gradle user home** para **`E:\Projetos\.gradle-home`** (reaproveita os arquivos já baixados!).

2. **Abrir o projeto:**
   - Clique em **Open** e selecione **`E:\Projetos\AutoDeck`**.
   - Aguarde o Gradle Sync finalizar.

3. **Rodar os testes unitários pela linha de comando (sempre que quiser validar a lógica):**
   ```powershell
   Set-Location "E:\Projetos\AutoDeck"
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
   $env:GRADLE_USER_HOME = "E:\Projetos\.gradle-home"
   $env:TEMP = "E:\Projetos\.gradle-home\tmp"
   $env:TMP = "E:\Projetos\.gradle-home\tmp"
   .\gradlew.bat :core-protocol:test -PonlyJvm=true --info
   ```

4. **Instalar no Tablet Samsung (Depuração USB):**
   - No tablet: *Configurações → Sobre o tablet → Informações do software → toque 7x em "Número de compilação"* → volte e ative **Depuração USB** em *Opções do desenvolvedor*.
   - Conecte o tablet ao PC, clique em **Run ('app')** no Android Studio (ou rode `.\gradlew.bat :app:installDebug`).

---

## 3. Checklist do Primeiro Teste no Tablet

1. No **Assistente de 1ª Configuração**, conclua o **Passo 4 (Checklist de Permissões)**: conceda Bluetooth, **Câmera**, Microfone, Notificações, Sobreposição e isenção de bateria.
2. Teste primeiro o **Modo Simulador** (OBD-II + Câmera) para validar o Dock, o Scanner Detalhado e a subida de tela cheia na ré.
3. Depois plugue o **Hub USB-C com o seu conversor de câmera**, vá em **Config / USB → Inspetor USB-C Hub ao Vivo**, e anote o código **`VID:PID`** e o tipo detectado (`UVC Padrão` ou `EasyCap Proprietário`) — é com esse código exato que concluímos a Fase 2!
