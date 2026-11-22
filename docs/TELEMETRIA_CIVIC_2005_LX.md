# Contrato de Telemetria — Honda Civic 2005 LX 1.7 (Pneu 195/65 R15)

Este documento lista **todos os dados brutos (OBD-II)** e **dados derivados** já parametrizados e expostos em tempo real no estado `uiState.headUnit.obdSnapshot` (`ObdSnapshot`) e `uiState.headUnit.obdSnapshot.derived` (`DerivedTelemetry`) para você construir qualquer visual no aplicativo.

---

## 1. Dados Brutos da ECU (`ObdSnapshot`)

Validados ao vivo na ECU do Civic 2005 LX (`ISO 9141-2`, protocolo `"3"`):

| Campo em `ObdSnapshot` | PID | Unidade | O que representa |
| :--- | :--- | :--- | :--- |
| `rpm` | `010C` | `rpm` | Rotação do motor (ex: `720.0`) |
| `speedKmh` | `010D` | `km/h` | Velocidade bruta reportada pela ECU |
| `coolantTempC` | `0105` | `°C` | Temperatura do líquido de arrefecimento (ECT) |
| `intakeMapKpa` | `010B` | `kPa` | Pressão absoluta no coletor (MAP / vácuo) |
| `intakeTempC` | `010F` | `°C` | Temperatura do ar admitido (IAT) |
| `throttlePct` | `0111` | `%` | Posição da borboleta (TPS) |
| `engineLoadPct` | `0104` | `%` | Carga calculada do motor |
| `timingAdvanceDeg` | `010E` | `°` | Avanço de ignição |
| `stftPct` | `0106` | `%` | Ajuste de combustível curto prazo (STFT) |
| `ltftPct` | `0107` | `%` | Ajuste de combustível longo prazo (LTFT) |
| `fuelSystemStatusCode` | `0103` | código | Estado da malha de injeção (`2` = malha fechada) |
| `o2SensorB1S1VoltageV` | `0114` | `V` | Tensão da sonda lambda (Banco 1 Sensor 1) |
| `batteryVoltageV` | `ATRV` | `V` | Voltagem da bateria / alternador |
| `milOn` / `milDtcCount` | `0101` | `Boolean` / `Int` | Luz de injeção acesa e contagem de falhas |
| `instantKmPerL` | calculado | `km/L` | Consumo instantâneo (Speed-Density + pneu `195/65 R15`) |
| `averageKmPerL` | calculado | `km/L` | Consumo médio acumulado da viagem |
| `litersPerHour` | calculado | `L/h` | Vazão horária de combustível (`0.0` em cut-off) |

> **Campos não suportados pela ECU do Civic 2005 LX:** `mafGramsPerSec` (`0110` = `NO DATA`) e `fuelLevelPct` (`012F` = `NO DATA`). Por isso o consumo usa **Speed-Density** (`1.7L` + `MAP` + `RPM` + `IAT` + `STFT/LTFT`) e o nível do tanque usa o **Tanque Virtual de 50 L**.

---

## 2. Dados Derivados (`ObdSnapshot.derived` → `DerivedTelemetry`)

Calculados automaticamente em [CivicTelemetryCalculator.kt](file:///d:/car/multimidia/AutoDeck/core-protocol/src/main/kotlin/com/autodeck/protocol/obd2/CivicTelemetryCalculator.kt) a cada ciclo OBD-II:

| Campo em `DerivedTelemetry` | Tipo | Descrição / Parametrização |
| :--- | :--- | :--- |
| `correctedSpeedKmh` | `Double?` | Velocidade real corrigida para o **PowerContact 2 `195/65 R15`** (`× 1,0209` vs `185/65 R15` OEM) |
| `tireCorrectionFactor` | `Double` | Fator aplicado (`1.020917...`) |
| `estimatedGear` | `Int?` | Marcha estimada (`null` = parado/ré, `0` = neutro em movimento, `1..5` manual ou `1..4` auto) |
| `estimatedGearLabel` | `String` | Rótulo pronto para UI: `"R"`, `"N"`, `"P/N"`, `"1ª"`..`"5ª"`, `"--"` |
| `shiftAdvice` | `ShiftAdvice` | Dica de troca (`NONE`, `SHIFT_UP`, `SHIFT_DOWN`) |
| `thermalZone` | `ThermalZone?` | Faixa térmica do D17: `COLD` (`<60°C`), `WARMING` (`60–79°C`), `OPTIMAL` (`80–99°C`), `ELEVATED` (`100–104°C`), `CRITICAL` (`≥105°C`) |
| `thermalStatusLabel` | `String` | Texto pronto (ex: `"96°C • Ideal"`) |
| `electricalHealth` | `ElectricalHealth?` | Saúde elétrica (`LOW_BATTERY`, `ALTERNATOR_WEAK`, `NORMAL`, `OVERCHARGE`) |
| `ecoZone` | `EcoZone` | Zona de condução (`CUTOFF`, `ECO`, `MODERATE`, `POWER`) |
| `ecoScorePct` | `Int` | Índice `0..100` para barra/medidor de economômetro |
| `isFuelCutoff` | `Boolean` | `true` em freio motor com injeção cortada (`0 L/h`) |
| `fuelSystemStatusLabel` | `String` | Texto do PID `0103` (ex: `"Malha fechada (OK)"`) |
| `virtualTankCapacityLiters` | `Double` | Capacidade do tanque do Civic 2005 LX (`50.0` L) |
| `virtualTankRemainingLiters` | `Double` | Litros estimados restantes (persistido no tablet) |
| `virtualTankPercent` | `Double` | Percentual restante do tanque virtual (`0.0..100.0`) |
| `estimatedRangeKm` | `Double?` | Autonomia estimada em km (`virtualTankRemainingLiters × averageKmPerL`) |
| `tripDistanceKm` | `Double` | Distância da viagem já corrigida para o pneu `195/65 R15` |
| `tripFuelUsedLiters` | `Double` | Litros consumidos acumulados na viagem |
| `peakRpm` / `peakSpeedKmh` / `peakCoolantTempC` | `Double` | Picos registrados na viagem atual |
| `preventiveWarnings` | `List<DiagnosticWarning>` | Alertas preventivos (`MIL_ACTIVE`, `PENDING_DTC`, `COOLANT_HIGH`, `COOLANT_CRITICAL`, `FUEL_TRIM_DRIFT`, `HIGH_IDLE_MAP`, `ALTERNATOR_WEAK`, `ALTERNATOR_HIGH`, `BATTERY_LOW`) |

---

## 3. Como Funciona o Tanque Virtual (50 L) e Ações no `HeadUnitViewModel`

Como a ECU do Civic 2005 LX não disponibiliza a leitura da boia no conector OBD-II (`012F = NO DATA`), o app implementa o **Tanque Virtual de 50 L** com persistência automática em [HeadUnitPreferences.kt](file:///d:/car/multimidia/AutoDeck/app/src/main/kotlin/com/autodeck/headunit/data/HeadUnitPreferences.kt):

- A cada ciclo OBD-II, `FuelConsumptionCalculator` calcula `deltaFuelLiters` (via Speed-Density com `1.7L` + correção `STFT/LTFT` + corte em *cut-off*) e subtrai de `virtualTankRemainingLiters`.
- Quando abastecer, a UI chama os métodos já expostos em [HeadUnitViewModel.kt](file:///d:/car/multimidia/AutoDeck/app/src/main/kotlin/com/autodeck/headunit/ui/HeadUnitViewModel.kt):
  - `viewModel.markTankFull()` → zera o consumo para `50.0 L` (`100%`)
  - `viewModel.addFuelLiters(liters)` → soma a quantidade abastecida (ex: `20.0` L)
  - `viewModel.setVirtualTankLiters(liters)` → ajusta o nível exato manual
  - `viewModel.setTransmissionType(TransmissionType.MANUAL_5 | TransmissionType.AUTO_4)` → alterna o cálculo de marchas entre câmbio Manual (5M) e Automático (4M)
