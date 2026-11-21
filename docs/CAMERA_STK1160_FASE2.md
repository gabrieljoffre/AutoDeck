# Câmera de Ré EasyCap STK1160 (`05E1:0408`) — Especificação da Fase 2

## 1. Hardware Confirmado no Tablet

Pela tela real capturada no app *USB Camera*:
- **Dispositivo:** `USB 2.0 Video Capture Controller`
- **Vendor/Product ID:** **`VID_05E1&PID_0408`**
- **Chipset USB Bridge:** **Syntek `STK1160`**
- **Decodificador Analógico (I2C interno):** **`SAA7113` / `GM7113`**

---

## 2. Parâmetros Validados na Prática (Obrigatórios)

No teste real do carro com este conversor específico, a imagem só estabiliza sem erro de transferência quando:
1. **Áudio USB DESATIVADO:** não abrir/reivindicar a interface de áudio isócrona do `STK1160` (evita `2/Error occurred during transfer` e economiza metade da banda USB no Hub).
2. **Entrada de Vídeo:** **Canal 1 / Composite (`CVBS`)**.
3. **Padrão de Vídeo:** **`NTSC`** (`720×480` ou `640×480` @ 30 fps — UYVY/YUYV convertido para RGBA no `ANativeWindow`).
4. **Energia no Hub:** carregador veicular **USB-C PD 30W+** com cabo C-para-C na porta `PD In` do Hub para não haver queda de tensão (< 4,7V) ao ligar a placa de captura.

---

## 3. Arquitetura Planejada do Driver User-Space (C/JNI sem Root)

Referência oficial do protocolo: Linux kernel `drivers/media/usb/stk1160/` + `saa7115`.

1. **Standby com 0% de banda USB (`AltSetting 0`):**
   - Mantém a interface de vídeo do `STK1160` em **Alternate Setting 0** fora da marcha ré, deixando 100% da banda USB livre para o CarPlay/Android Auto.
   - A cada ~150 ms, realiza leitura I2C via endpoint 0 do registrador **`0x1F`** do `SAA7113`.
   - Bit 6 (`0x40` = `HLCK`): `0` = sincronismo NTSC travado (**ré engatada, inclusive no escuro total à noite!**), `1` = sem sinal.
2. **Ativação instantânea da Ré (`AltSetting 5`):**
   - Ao confirmar sinal no `SAA7113 reg 0x1F` (com histerese anti-pisca de ~1,2s na saída), troca para Alternate Setting isócrona ativa, envia URBs assíncronas via `ioctl(fd, USBDEVFS_SUBMITURB, ...)` usando o descritor de `UsbDeviceConnection.getFileDescriptor()`.
   - Decodifica os pacotes `STK1160` (cabeçalho de 4 bytes + linhas UYVY par/ímpar deinterlaced) diretamente no `ANativeWindow` obtido do `TextureView` em tela cheia.
3. **Controles na UI:**
   - Seletor rápido `Cam 1 / Cam 2 / Cam 3 / Cam 4` (trocando o mux de entrada do `SAA7113`, registrador `0x02`) para validar ou expandir para múltiplas câmeras no mesmo chicote RCA.
