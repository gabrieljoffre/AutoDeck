# Conexões Físicas e Matriz de Isolamento (Zero Interferência)

## 1. Mapa Físico de Conexões (Seu Kit)

```text
                       ┌──────────────────────────────────────┐
[Tomada 12V / Pós-Chave]──►[Carregador Veicular USB-C PD 30W–45W]
                       └──────────────────┬───────────────────┘
                                          │ USB-C PD IN (Alimentação)
                                          ▼
┌────────────────────────────┐   ┌─────────────────────────────────┐
│ Tablet Samsung (Android)   │◄══│ Hub USB-C com PD Pass-Through   │
│                            │   └───────┬───────────────┬─────────┘
│  • AutoDeck + Service      │           │ USB-A 1       │ USB-A 2
│  • Bluetooth A2DP + SPP    │           ▼               ▼
└────────┬───────────┬───────┘   [Dongle CarPlay/AA]  [Conversor Multi-RCA→USB]
         │           │           (Carlinkit CCPA)     (EasyCap / UVC)
         │ BT A2DP   │ BT SPP                            ▲
         ▼           ▼                                   │ RCA Vídeo
[Transceptor 2.4 BT] [OBD-II Azulzinho]           [Câmera de Ré 12V]
         │            (ELM327 v1.5/v2.1)          (alimentada na luz de ré)
         ▼
[Processador de Áudio Taranis]
```

> **Atenção à energia e bateria Samsung:**
> 1. Use carregador veicular **USB-C PD de 30W–45W** para evitar queda de tensão (*brownout*) quando tablet + dongle + conversor trabalham juntos.
> 2. Ative **"Proteger bateria (limitar em 85%)"** nas configurações do Samsung, já que o tablet passa muito tempo plugado no carro quente.
> 3. Se o hub não cortar energia das portas USB quando você desliga o carro, o tablet pode alimentar o hub durante a noite e drenar a bateria — desligar o pós-chave do hub ou retirar ao estacionar por dias evita isso.

---

## 2. Matriz de Convivência (Zero Interferência)

### A. Áudio Taranis (Bluetooth A2DP) × OBD-II Azulzinho (Bluetooth SPP) × Wi-Fi do Carlinkit

1. **Carlinkit forçado em Wi-Fi 5 GHz (`WIFI_5G = 25`):**
   - O rádio Bluetooth e o Wi-Fi 2,4 GHz disputam a mesma antena/faixa no iPhone e no tablet.
   - O handshake do AutoDeck envia explicitamente `WIFI_5G` para o dongle Carlinkit trabalhar em 5 GHz, deixando o espectro de 2,4 GHz livre para o áudio A2DP do processador Taranis e o OBD-II.
2. **Zero `startDiscovery()` + Backoff inteligente do OBD-II:**
   - Após configurado, `HeadUnitCoordinator.btDiscoveryAllowed` permanece `false`.
   - Com o carro desligado ou após 5 falhas, o intervalo de reconexão sobe para **60 segundos** para evitar *paging* Bluetooth constante picotando o áudio.
   - Desative também *"Busca de dispositivos próximos"* e *"Busca Bluetooth"* (Localização) nas configurações do tablet Samsung.
3. **Áudio CarPlay/AA sempre em `USAGE_MEDIA`:**
   - Músicas, mapas, Siri/Assistente e ligações usam `AudioAttributes.USAGE_MEDIA` com buffer ≥ 250 ms em thread urgente (`THREAD_PRIORITY_URGENT_AUDIO`). Assim, **tudo sai pelo transceptor Bluetooth para o processador Taranis** em vez de escapar pelo alto-falante do tablet.
   - Durante a ré, aplicamos `track.setVolume(0.25f)` no próprio app + `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` para apps externos sem jamais derrubar o link A2DP.
4. **Concorrência OBD-II serializada:**
   - Um `Mutex` + drenagem de entrada no `BluetoothClassicObdTransport` garante que leitura/limpeza de falhas DTC nunca colida com o loop de sensores no mesmo socket RFCOMM.

---

### B. Como Eliminamos "Ter que Clicar / Abrir Servidor no Celular"

- **Com o Carlinkit CPC200-CCPA:** o AutoDeck envia o pacote `WIFI_CONNECT (1002)` automaticamente logo após a inicialização USB (e repete suavemente a cada ~8s enquanto em standby) junto com `/etc/android_work_mode = 1`. Assim que você entra no carro com iPhone ou Android já pareado no dongle uma vez, a projeção sobe **sozinha sem clicar em nada**.
- **Para o app abrir e não morrer sozinho no Android 10–16:**
  - `HeadUnitForegroundService` (`connectedDevice`) mantém todas as conexões vivas em segundo plano.
  - O **Checklist de Permissões** no Passo 4 do Assistente orienta a conceder `CAMERA` (exigida pelo Android 10+ para USB de vídeo), `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, isenção de otimização de bateria e opção de tornar o AutoDeck o **Launcher (`HOME`)** do tablet.
  - Ao conectar o dongle ou conversor USB pela 1ª vez, marque **"Sempre abrir com AutoDeck"** no diálogo do sistema para gravar a permissão USB de forma persistente.

---

### C. USB Carlinkit × Conversor de Câmera (EasyCap vs. UVC)

- **Banda USB e CPU:** o conversor USB sempre transmite o formato negociado pelo UVC (recomendado **MJPEG 640×480**, não YUY2), decodificando a grade de detecção 16×12 a ~5–7 fps para poupar CPU/temperatura do tablet.
- **Pausa apenas de renderização na ré:** quando a ré sobe, o `MediaCodec` H.264 do CarPlay/AA **continua decodificando os quadros com `releaseOutputBuffer(idx, false)`** (não joga fora os P-frames!) e ao sair da ré o `CarlinkitUsbEngine` solicita um keyframe IDR (`COMMAND FRAME=12`), evitando tela preta ou imagem borrada na volta.
- **Identificando o seu conversor (EasyCap vs UVC):**
  - Abra **Config / USB → Inspetor USB-C Hub ao Vivo** e plugue seu conversor:
    - Se aparecer **`UVC Padrão`** (ex: `MS2109`, VID `0x534D`), integraremos com **`bingshushu/AndroidUSBCamera`** (atualizado para Android 14/15).
    - Se aparecer **`EasyCap Proprietário`** (ex: `UTV007` VID `0x1B71`, `STK1160` `0x05E1`, `EM2860` `0xEB1A`, `Somagic` `0x1C88`), avaliaremos `Arksine/usbtv007-android` ou a troca por um conversor UVC `MS2109` (~R\$ 40) que funciona nativamente no Android 14/15/16.
