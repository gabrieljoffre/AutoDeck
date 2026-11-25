package com.autodeck.headunit.agent.live

import com.autodeck.headunit.agent.CarAgentHostBridge
import com.autodeck.headunit.agent.copilot.CopilotIntentParser
import com.autodeck.headunit.agent.copilot.CopilotJson
import com.autodeck.headunit.agent.copilot.FavoriteRef
import com.autodeck.headunit.agent.copilot.VolumeDirection
import com.autodeck.headunit.calendar.PortugueseDateTimeParser
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.phone.PhoneContactMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.Locale

data class LiveToolExecution(
    val result: String,
    val actionBadge: String? = null
)

/**
 * Declarações de ferramentas (function calling) e executor do Gemini Live,
 * reaproveitando integralmente o [CarAgentHostBridge] do HondaDeck.
 */
object GeminiLiveTools {

    private val ACCENT_ALIASES = mapOf(
        "ciano" to "#22D3EE",
        "azul" to "#22D3EE",
        "lima" to "#A3E635",
        "verde" to "#A3E635",
        "ambar" to "#FB923C",
        "laranja" to "#FB923C",
        "violeta" to "#A78BFA",
        "roxo" to "#A78BFA",
        "rosa" to "#F472B6"
    )

    fun buildFunctionDeclarations(): JSONArray = JSONArray().apply {
        put(
            fnDecl(
                name = "controlar_musica",
                description = "Controla a reprodução do Spotify no painel: tocar, pausar, próxima faixa, faixa anterior, abrir/fechar tela do Spotify ou buscar e tocar música/artista/playlist.",
                properties = JSONObject().apply {
                    put("acao", enumProp("Ação musical desejada.", listOf("play", "pause", "next", "prev", "open", "close", "search_and_play")))
                    put("consulta", strProp("Nome da música, artista, álbum ou playlist quando acao='search_and_play'."))
                },
                required = listOf("acao")
            )
        )
        put(
            fnDecl(
                name = "ajustar_volume",
                description = "Ajusta o volume do som do carro: definir porcentagem exata, subir, descer, mutar ou tirar do mudo.",
                properties = JSONObject().apply {
                    put("acao", enumProp("Tipo de ajuste de volume.", listOf("set", "up", "down", "mute", "unmute")))
                    put("porcentagem", intProp("Valor de 0 a 100 quando acao='set'."))
                },
                required = listOf("acao")
            )
        )
        put(
            fnDecl(
                name = "controlar_tela",
                description = "Abre, fecha ou expande as telas do painel: início (home), mapa 2/3 (map), mapa em tela cheia (map_full), Spotify 2/3 (music) ou ajustes (settings).",
                properties = JSONObject().apply {
                    put("tela", enumProp("Tela alvo.", listOf("home", "map", "map_full", "music", "settings")))
                    put("acao", enumProp("Abrir, fechar (volta para home) ou expandir.", listOf("open", "close", "expand")))
                },
                required = listOf("tela")
            )
        )
        put(
            fnDecl(
                name = "buscar_lugar",
                description = "Busca lugares próximos no mapa do aplicativo (postos, farmácias, restaurantes, endereços) e exibe os resultados na tela.",
                properties = JSONObject().apply {
                    put("consulta", strProp("Nome ou categoria do lugar a buscar."))
                },
                required = listOf("consulta")
            )
        )
        put(
            fnDecl(
                name = "iniciar_rota",
                description = "Traça rota dentro do aplicativo para um destino, endereço ou local favorito pelo nome (ex.: Casa, Trabalho, Posto).",
                properties = JSONObject().apply {
                    put("destino", strProp("Nome do favorito, local ou endereço de destino."))
                    put("mapa_externo", boolProp("True somente se o motorista pedir explicitamente para abrir no Google Maps ou Waze externo."))
                },
                required = listOf("destino")
            )
        )
        put(
            fnDecl(
                name = "encerrar_rota",
                description = "Encerra/cancela a navegação de rota ativa no mapa do painel.",
                properties = JSONObject()
            )
        )
        put(
            fnDecl(
                name = "ler_telemetria_carro",
                description = "Lê dados em tempo real do Honda Civic via OBD-II: telemetria do motor, alertas ativos, códigos de falha DTC, consumo/tanque, manutenção ou relatório completo.",
                properties = JSONObject().apply {
                    put(
                        "tipo",
                        enumProp(
                            "Tipo de informação veicular.",
                            listOf("resumo", "motor", "temperatura", "bateria", "consumo", "tanque", "alertas", "dtc", "manutencao", "relatorio")
                        )
                    )
                },
                required = listOf("tipo")
            )
        )
        put(
            fnDecl(
                name = "consultar_abastecimento",
                description = "Consulta o diário de abastecimento: consumo real, último tanque, gastos nos últimos 30 dias e custo por km.",
                properties = JSONObject()
            )
        )
        put(
            fnDecl(
                name = "registrar_abastecimento",
                description = "Registra um novo abastecimento no diário do carro e atualiza o tanque virtual.",
                properties = JSONObject().apply {
                    put("litros", numProp("Quantidade de litros abastecidos."))
                    put("preco_por_litro", numProp("Preço por litro em reais, se informado."))
                    put("hodometro_km", numProp("Quilometragem atual do painel, se informada."))
                    put("tanque_cheio", boolProp("True se completou o tanque, false se foi parcial."))
                },
                required = listOf("litros")
            )
        )
        put(
            fnDecl(
                name = "consultar_clima",
                description = "Consulta o clima local do carro (ou de outra cidade informada).",
                properties = JSONObject().apply {
                    put("cidade", strProp("Nome da cidade se diferente da localização atual do carro."))
                }
            )
        )
        put(
            fnDecl(
                name = "buscar_contato",
                description = "Pesquisa contatos sincronizados do celular por nome, apelido, parte do nome, relação (ex.: mãe, amor) ou número. Retorna números normalizados (+55), apelidos e se tem WhatsApp; em caso de ambiguidade (várias pessoas), pergunte ao motorista qual ele quer.",
                properties = JSONObject().apply {
                    put("nome", strProp("Nome, apelido, parte do nome ou número do contato."))
                },
                required = listOf("nome")
            )
        )
        put(
            fnDecl(
                name = "agenda",
                description = "Consulta compromissos na agenda do tablet e do celular (Google Agenda) para um dia ou intervalo (ex.: hoje, amanhã, sexta, esta semana, próxima semana, próximos 3 dias, 25/11).",
                properties = JSONObject().apply {
                    put("dia", strProp("Dia ou período em português (ex.: 'hoje', 'amanhã', 'sexta', 'esta semana', 'próximos 3 dias', '25/11')."))
                    put("inicio", strProp("Data inicial opcional no formato AAAA-MM-DD."))
                    put("fim", strProp("Data final opcional no formato AAAA-MM-DD."))
                }
            )
        )
        put(
            fnDecl(
                name = "consultar_agenda",
                description = "Alias de agenda: consulta os compromissos do motorista na agenda para hoje, amanhã ou outro período.",
                properties = JSONObject().apply {
                    put("dia", strProp("Dia ou intervalo desejado (ex.: 'hoje', 'amanha', 'sexta', 'esta semana')."))
                }
            )
        )
        put(
            fnDecl(
                name = "criar_evento",
                description = "Cria um evento no calendário Google principal (no tablet ou no celular via Ponte). SEMPRE confirme antes com o motorista título, data, hora e duração usando confirmado=false; só chame com confirmado=true após ele confirmar com 'sim'.",
                properties = JSONObject().apply {
                    put("titulo", strProp("Título ou assunto do evento."))
                    put("data_hora", strProp("Data e hora em português ou ISO (ex.: 'amanhã às 3 da tarde', 'sexta 9h', '2026-11-26 15:00')."))
                    put("duracao_minutos", intProp("Duração em minutos (padrão 60)."))
                    put("local", strProp("Endereço ou local opcional do evento."))
                    put("lembrete_minutos", intProp("Minutos de antecedência para o lembrete do evento (ex.: 15, 30)."))
                    put("confirmado", boolProp("False na primeira chamada para confirmar com o motorista; true somente após o motorista dizer sim."))
                },
                required = listOf("titulo", "data_hora")
            )
        )
        put(
            fnDecl(
                name = "lembrete",
                description = "Cria um lembrete local no tablet com alarme/notificação e aviso falado no horário combinado (ex.: 'daqui a 20 minutos', 'amanhã às 8h', 'hoje às 18h30').",
                properties = JSONObject().apply {
                    put("texto", strProp("O que deve ser lembrado ao motorista."))
                    put("quando", strProp("Quando disparar o lembrete (ex.: 'daqui a 15 minutos', 'amanhã às 3 da tarde', 'sexta 9h')."))
                },
                required = listOf("texto", "quando")
            )
        )
        put(
            fnDecl(
                name = "abrir_app",
                description = "Abre um aplicativo instalado no tablet pelo nome falado (ex.: 'YouTube Music', 'Câmera', 'Waze', 'Chrome', 'Calculadora').",
                properties = JSONObject().apply {
                    put("nome", strProp("Nome falado do aplicativo instalado no tablet."))
                },
                required = listOf("nome")
            )
        )
        put(
            fnDecl(
                name = "abrir_no_celular",
                description = "Abre um aplicativo instalado no celular Samsung conectado via HondaDeck Ponte (ex.: 'Instagram', 'WhatsApp', 'Waze', 'Uber').",
                properties = JSONObject().apply {
                    put("app", strProp("Nome falado do aplicativo instalado no celular."))
                },
                required = listOf("app")
            )
        )
        put(
            fnDecl(
                name = "ler_mensagens",
                description = "Lê as mensagens recentes recebidas no celular (WhatsApp, WhatsApp Business e SMS) ou no tablet.",
                properties = JSONObject()
            )
        )
        put(
            fnDecl(
                name = "responder_mensagem",
                description = "Responde à mensagem recebida no celular/WhatsApp. SEMPRE confirme antes com o motorista o destinatário e o texto exato (confirmado=false primeiro; só passe confirmado=true após ele dizer sim).",
                properties = JSONObject().apply {
                    put("mensagem", strProp("Texto exato da resposta a enviar."))
                    put("id", strProp("ID opcional da mensagem recebida (se omitido, responde à mais recente)."))
                    put("confirmado", boolProp("False na primeira chamada para confirmar com o motorista; true somente após o motorista dizer sim."))
                },
                required = listOf("mensagem")
            )
        )
        put(
            fnDecl(
                name = "enviar_whatsapp",
                description = "Envia uma mensagem de texto pelo WhatsApp do celular conectado. SEMPRE confirme antes destinatário e texto ('Mando para Ana: ...?') usando confirmado=false; só chame com confirmado=true depois que o motorista confirmar com 'sim'.",
                properties = JSONObject().apply {
                    put("contato", strProp("Nome do contato na agenda do celular (ex.: Ana, Mãe)."))
                    put("numero", strProp("Número de telefone opcional se informado diretamente."))
                    put("texto", strProp("Texto exato da mensagem a enviar."))
                    put("confirmado", boolProp("False na primeira chamada para validar o destinatário e pedir confirmação; true somente após o motorista dizer sim."))
                },
                required = listOf("texto")
            )
        )
        put(
            fnDecl(
                name = "enviar_audio_whatsapp",
                description = "Grava uma mensagem de voz pelo microfone do tablet (até 2 s de silêncio, máx. 60 s) e envia no WhatsApp do celular conectado. SEMPRE confirme o destinatário antes (confirmado=false primeiro; confirmado=true só após o motorista dizer sim).",
                properties = JSONObject().apply {
                    put("contato", strProp("Nome do contato na agenda do celular."))
                    put("numero", strProp("Número de telefone opcional se informado diretamente."))
                    put("confirmado", boolProp("False na primeira chamada para validar contato e confirmar; true após o motorista dizer sim para começar a gravar."))
                }
            )
        )
        put(
            fnDecl(
                name = "ligar",
                description = "Faz uma ligação telefônica pela linha do celular conectado para um contato ou número.",
                properties = JSONObject().apply {
                    put("contato", strProp("Nome do contato na agenda do celular."))
                    put("numero", strProp("Número de telefone opcional se informado diretamente."))
                }
            )
        )
        put(
            fnDecl(
                name = "ligar_whatsapp",
                description = "Faz uma ligação de voz pelo WhatsApp no celular conectado para um contato ou número.",
                properties = JSONObject().apply {
                    put("contato", strProp("Nome do contato na agenda do celular."))
                    put("numero", strProp("Número de telefone opcional se informado diretamente."))
                }
            )
        )
        put(
            fnDecl(
                name = "atender",
                description = "Atende a chamada telefônica que está tocando no celular conectado.",
                properties = JSONObject()
            )
        )
        put(
            fnDecl(
                name = "desligar",
                description = "Encerra ou rejeita a chamada telefônica em andamento ou tocando no celular conectado.",
                properties = JSONObject()
            )
        )
        put(
            fnDecl(
                name = "ler_whatsapp",
                description = "Alias de ler_mensagens: lê as mensagens recentes recebidas no WhatsApp.",
                properties = JSONObject()
            )
        )
        put(
            fnDecl(
                name = "responder_whatsapp",
                description = "Alias de responder_mensagem: responde à última mensagem do WhatsApp (sempre confirme antes com o motorista).",
                properties = JSONObject().apply {
                    put("mensagem", strProp("Texto exato da resposta a enviar."))
                    put("confirmado", boolProp("True somente após o motorista confirmar o envio."))
                },
                required = listOf("mensagem")
            )
        )
        put(
            fnDecl(
                name = "controlar_camera_re",
                description = "Abre ou fecha a visualização manual da câmera de ré.",
                properties = JSONObject().apply {
                    put("acao", enumProp("Abrir ou fechar a câmera de ré.", listOf("open", "close")))
                },
                required = listOf("acao")
            )
        )
        put(
            fnDecl(
                name = "configurar_aparencia",
                description = "Troca o tema do mapa (AUTO, LIGHT/claro ou DARK/escuro) e/ou a cor de destaque do painel (ciano, lima, âmbar, violeta, rosa ou hex).",
                properties = JSONObject().apply {
                    put("tema_mapa", enumProp("Tema visual do mapa.", listOf("AUTO", "LIGHT", "DARK")))
                    put("cor_destaque", strProp("Cor de destaque do painel: ciano, lima, ambar, violeta, rosa ou código #RRGGBB."))
                }
            )
        )
        put(
            fnDecl(
                name = "limpar_memoria_conversas",
                description = "Apaga o histórico salvo e os resumos de conversas anteriores quando o motorista pede para esquecer as conversas.",
                properties = JSONObject()
            )
        )
    }

    /**
     * Constrói o `systemInstruction` completo do Gemini Live com persona automotiva pt-BR,
     * perfil do motorista, memória de sessões anteriores e estado vivo do Civic/música/rota.
     */
    fun buildSystemInstruction(
        host: CarAgentHostBridge,
        recentSummaries: List<String>,
        oneShot: Boolean
    ): String {
        val hu = host.getHeadUnitState()
        val snap = hu.obdSnapshot
        val derived = snap.derived
        val media = host.getMediaState()
        val favorites = host.getFavoritePlaces()
        val latestWa = host.getWhatsAppMessages().firstOrNull()
        val routeSummary = host.getRouteSummary()
        val maintenanceSummary = host.getMaintenanceSummary()
        val fuelLog = host.getFuelLogSummary()
        val weather = host.getWeatherSummary()

        val favText = if (favorites.isEmpty()) {
            "Nenhum favorito cadastrado."
        } else {
            favorites.joinToString("; ") { f ->
                val hint = f.voice.takeIf { it.isNotBlank() }?.let { " (voz: \"$it\")" }.orEmpty()
                "${f.name}$hint"
            }
        }

        val memoryBlock = if (recentSummaries.isEmpty()) {
            "Nenhuma conversa anterior salva."
        } else {
            recentSummaries.joinToString("\n") { "- $it" }
        }

        val topContactsBlock = PhoneContactMatcher.formatForSystemPrompt(
            host.getTopFrequentContacts(limit = 30),
            limit = 30
        )

        val tabletApps = host.listTabletAppNames(limit = 25)
        val tabletAppsLine = if (tabletApps.isEmpty()) {
            "Use abrir_app(nome) para abrir aplicativos instalados no tablet."
        } else {
            tabletApps.joinToString(", ")
        }

        val oneShotRule = if (oneShot) {
            "PEÇA AO GEMINI (único modo de voz): o motorista tocou no botão e já está falando. Execute a ferramenta necessária e responda de forma direta, concisa e natural (1 a 2 frases) confirmando o que foi feito. Depois de responder o microfone continua aberto por alguns segundos para uma pergunta de seguimento; responda a ela normalmente e, se o motorista ficar em silêncio, não diga nada mais."
        } else {
            "MODO CONVERSA AO VIVO: mantenha um diálogo natural, amigável e objetivo para quem está dirigindo."
        }

        return """
            Você é o Copiloto do HondaDeck, assistente de voz ao vivo instalado no painel de um Honda Civic.
            REGRAS DE PERSONA:
            - Fale SEMPRE em Português do Brasil (pt-BR), com frases curtas, claras e naturais para quem está dirigindo.
            - $oneShotRule
            - SEMPRE use as ferramentas disponíveis para agir no carro/painel (Spotify, volume, mapa, rotas, OBD, abastecimento, contatos/WhatsApp/ligações via Ponte, agenda, criar eventos, lembretes falados, abrir apps no tablet ou no celular, câmera de ré, tema/cor, limpar memória) e para pesquisar fatos atuais use o Google Search nativo.
            - SEMPRE confirme brevemente em voz alta toda ação executada (ex.: "Volume ajustado para 50%", "Rota para Casa iniciada", "Música pausada").
            - CONTATOS E AMBIGUIDADE: você já conhece os ~30 contatos frequentes/favoritos listados abaixo; para qualquer outro contato (ou para checar apelidos/números), chame `buscar_contato(nome)`. Se houver mais de um resultado parecido (ex.: várias "Ana"), SEMPRE pergunte ao motorista qual deles ele quer antes de ligar ou mandar mensagem.
            - REGRA OBRIGATÓRIA PARA WHATSAPP E MENSAGENS: NUNCA envie WhatsApp de texto, áudio ou resposta sem confirmar antes com o motorista o destinatário e o texto exato (ex.: "Mando para Ana: 'tô chegando'?"). Primeiro chame a ferramenta com confirmado=false para resolver o contato; só chame com confirmado=true depois que o motorista responder "sim". Se o celular retornar "celular bloqueado", avise o motorista para desbloquear ou usar Smart Lock.
            - REGRA OBRIGATÓRIA PARA CRIAR EVENTO: NUNCA crie evento na agenda sem confirmar antes com o motorista (`criar_evento` com `confirmado=false` primeiro; só `confirmado=true` após ele dizer sim).
            - SE O MOTORISTA PERGUNTAR "O QUE VOCÊ CONSEGUE FAZER?": responda resumindo suas capacidades — música no Spotify, volume, rotas e busca de lugares no mapa, telemetria/diagnóstico OBD-II do Civic, diário de abastecimento e manutenção, clima, busca de contatos + WhatsApp (texto, áudio e ligação) + ligações pela linha do celular, consultar e criar eventos na agenda, lembretes com aviso falado, abrir aplicativos no tablet ou no celular, câmera de ré e buscas na internet.
            - Nunca invente leituras do carro: consulte `ler_telemetria_carro` quando precisar de dados atualizados.

            PERFIL DO MOTORISTA:
            - Cidade base: Anápolis-GO, Brasil.
            - Veículo: Honda Civic (painel HondaDeck 3.0).

            MEMÓRIA DE CONVERSAS RECENTES:
            $memoryBlock

            CONTATOS FAVORITOS E FREQUENTES DO MOTORISTA:
            $topContactsBlock

            APLICATIVOS INSTALADOS NO TABLET:
            $tabletAppsLine

            ESTADO ATUAL DO CARRO E PAINEL:
            - OBD-II: ${hu.obdStatus.labelPt} | Velocidade: ${derived.correctedSpeedKmh?.toInt() ?: snap.speedKmh?.toInt() ?: 0} km/h | RPM: ${snap.rpm?.toInt() ?: 0} | Marcha: ${derived.estimatedGearLabel}
            - Motor: ${snap.coolantTempC?.let { "${it.toInt()}°C" } ?: "sem leitura"} (${derived.thermalStatusLabel}) | Bateria: ${snap.batteryVoltageV?.let { "%.1fV".format(Locale.US, it) } ?: "sem leitura"} | Carga: ${snap.engineLoadPct?.let { "${it.toInt()}%" } ?: "--"}
            - Consumo médio: ${snap.averageKmPerL?.let { "%.1f km/L".format(Locale.US, it) } ?: "--"} | Tanque virtual: ${derived.virtualTankPercent.toInt()}% (${"%.1f".format(Locale.US, derived.virtualTankRemainingLiters)} L) | Autonomia: ${derived.estimatedRangeKm?.let { "${it.toInt()} km" } ?: "--"}
            - Alertas OBD ativos: ${if (hu.obdAlerts.isEmpty()) "Nenhum" else hu.obdAlerts.joinToString { it.title }}
            - Falhas DTC: ${if (hu.dtcCodes.isEmpty()) "Nenhuma" else hu.dtcCodes.joinToString { it.code }}
            - Diário de abastecimento: ${fuelLog ?: "sem registros"}
            - Manutenção: ${maintenanceSummary ?: "sem itens cadastrados"}
            - Música (${media.appLabel}): ${if (media.isPlaying) "tocando" else "pausada"}${if (media.title.isNotBlank()) " \"${media.title}\" de \"${media.artist}\"" else ""}
            - Rota no app: ${routeSummary ?: "nenhuma rota ativa"}
            - Locais favoritos: $favText
            - Clima: ${weather ?: "sem dados no momento"}
            - Celular (HondaDeck Ponte): ${host.getPhoneSummary()}
            - WhatsApp pendente: ${if (latestWa != null) "mensagem de ${latestWa.sender}" else "nenhuma"}
        """.trimIndent()
    }

    suspend fun executeTool(
        call: LiveFunctionCall,
        host: CarAgentHostBridge,
        onRecordVoiceNote: (suspend (recipient: String) -> String)? = null,
        onClearMemory: () -> Unit = {}
    ): LiveToolExecution {
        val args = CopilotJson.parseObjOrNull(call.argsJson)
        return when (call.name) {
            "controlar_musica" -> {
                val acao = args?.optString("acao")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val consulta = args?.optString("consulta")?.trim().orEmpty()
                when (acao) {
                    "play" -> LiveToolExecution(host.controlMusic("MUSIC_PLAY"), "Spotify Play")
                    "pause" -> LiveToolExecution(host.controlMusic("MUSIC_PAUSE"), "Spotify Pausa")
                    "next" -> LiveToolExecution(host.controlMusic("MUSIC_NEXT"), "Próxima faixa")
                    "prev" -> LiveToolExecution(host.controlMusic("MUSIC_PREV"), "Faixa anterior")
                    "open" -> LiveToolExecution(host.controlMusic("OPEN_SPOTIFY"), "Spotify")
                    "close" -> LiveToolExecution(host.openHome(), "Painel inicial")
                    "search_and_play" -> {
                        val res = if (consulta.isNotBlank()) {
                            host.searchAndPlayMusic(consulta)
                        } else {
                            host.controlMusic("MUSIC_PLAY")
                        }
                        LiveToolExecution(res, if (consulta.isNotBlank()) "Spotify: $consulta" else "Spotify")
                    }
                    else -> LiveToolExecution("Ação musical não reconhecida: $acao")
                }
            }

            "ajustar_volume" -> {
                val acao = args?.optString("acao")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val pct = args?.optDouble("porcentagem")?.toInt()?.coerceIn(0, 100)
                val res = when (acao) {
                    "set" -> host.adjustVolume(VolumeDirection.SET, pct ?: 60)
                    "up" -> host.adjustVolume(VolumeDirection.UP, null)
                    "down" -> host.adjustVolume(VolumeDirection.DOWN, null)
                    "mute" -> host.adjustVolume(VolumeDirection.MUTE, 0)
                    "unmute" -> host.adjustVolume(VolumeDirection.UNMUTE, null)
                    else -> if (pct != null) host.adjustVolume(VolumeDirection.SET, pct) else "Informe a ação de volume."
                }
                LiveToolExecution(res, res)
            }

            "controlar_tela" -> {
                val tela = args?.optString("tela")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val acao = args?.optString("acao")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (acao == "close") {
                    LiveToolExecution(host.openHome(), "Painel inicial")
                } else {
                    when (tela) {
                        "home" -> LiveToolExecution(host.openHome(), "Painel inicial")
                        "map_full" -> LiveToolExecution(host.openMapFullScreen(), "Mapa tela cheia")
                        "map" -> if (acao == "expand") {
                            LiveToolExecution(host.openMapFullScreen(), "Mapa tela cheia")
                        } else {
                            LiveToolExecution(host.navigateTo(null, null, false), "Mapa")
                        }
                        "music" -> LiveToolExecution(host.controlMusic("OPEN_SPOTIFY"), "Spotify")
                        "settings" -> LiveToolExecution(host.switchTab(MainTab.SETTINGS), "Ajustes")
                        else -> LiveToolExecution(host.openHome(), "Painel inicial")
                    }
                }
            }

            "buscar_lugar" -> {
                val q = args?.optString("consulta")?.trim().orEmpty()
                if (q.isBlank()) {
                    LiveToolExecution("Informe o nome ou tipo de lugar para buscar.")
                } else {
                    val res = host.searchPlacesForTool(q)
                    LiveToolExecution(res, "Busca: $q")
                }
            }

            "iniciar_rota" -> {
                val destino = args?.optString("destino")?.trim().orEmpty()
                val externo = args?.optBoolean("mapa_externo") ?: false
                if (destino.isBlank()) {
                    LiveToolExecution(host.navigateTo(null, null, false), "Mapa")
                } else {
                    val fav = matchFavorite(destino, host.getFavoritePlaces())
                    val res = host.navigateTo(destino, fav, externo)
                    LiveToolExecution(res, res)
                }
            }

            "encerrar_rota" -> {
                val res = host.stopRoute()
                LiveToolExecution(res, "Rota encerrada")
            }

            "ler_telemetria_carro" -> {
                val tipo = args?.optString("tipo")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val text = buildTelemetryResponse(tipo, host)
                LiveToolExecution(text, "OBD-II")
            }

            "consultar_abastecimento" -> {
                val summary = host.getFuelLogSummary()
                    ?: "Ainda não há abastecimentos registrados no diário."
                LiveToolExecution(summary, "Abastecimento")
            }

            "registrar_abastecimento" -> {
                val litros = args?.optDouble("litros")
                if (litros == null || litros <= 0.0) {
                    LiveToolExecution("Informe a quantidade de litros abastecidos.")
                } else {
                    val preco = args.optDouble("preco_por_litro")?.takeIf { it > 0.0 }
                    val km = args.optDouble("hodometro_km")?.takeIf { it > 0.0 }
                    val cheio = args.optBoolean("tanque_cheio") ?: true
                    val res = host.logRefuelFromAgent(litros, preco, km, cheio)
                    LiveToolExecution(res, "Abastecimento")
                }
            }

            "consultar_clima" -> {
                val city = args?.optString("cidade")?.trim().orEmpty()
                val summary = if (city.isBlank()) {
                    host.getWeatherSummary() ?: "Clima local ainda indisponível (aguardando GPS/internet)."
                } else {
                    host.getWeatherSummaryForCity(city)
                        ?: host.getWeatherSummary()
                        ?: "Não foi possível obter o clima de $city."
                }
                LiveToolExecution(summary, "Clima")
            }

            "buscar_contato" -> {
                val nome = args?.optString("nome")?.trim().orEmpty()
                if (nome.isBlank()) {
                    LiveToolExecution("Informe o nome ou apelido do contato para buscar.")
                } else {
                    val res = host.searchContactsForAgent(nome)
                    LiveToolExecution(res, "Contatos")
                }
            }

            "agenda", "consultar_agenda" -> {
                val dia = args?.optString("dia")?.trim()?.takeIf { it.isNotBlank() }
                    ?: args?.optString("intervalo")?.trim()?.takeIf { it.isNotBlank() }
                val inicio = args?.optString("inicio")?.trim()?.takeIf { it.isNotBlank() }
                val fim = args?.optString("fim")?.trim()?.takeIf { it.isNotBlank() }
                val summary = host.getAgendaForRange(dia, inicio, fim)
                LiveToolExecution(summary, "Agenda")
            }

            "criar_evento" -> {
                val titulo = args?.optString("titulo")?.trim().orEmpty()
                val rawWhen = args?.optString("data_hora")?.trim()?.takeIf { it.isNotBlank() }
                    ?: args?.optString("quando")?.trim()?.takeIf { it.isNotBlank() }
                    ?: args?.optString("data")?.trim().orEmpty()
                val duracaoMin = (
                    args?.optDouble("duracao_minutos")?.toInt()
                        ?: args?.optDouble("duracao")?.toInt()
                        ?: 60
                    ).coerceIn(10, 720)
                val local = args?.optString("local")?.trim().orEmpty()
                val lembreteMin = (
                    args?.optDouble("lembrete_minutos")?.toInt()
                        ?: args?.optDouble("lembrete")?.toInt()
                    )?.takeIf { it >= 0 }
                val confirmed = args?.optBoolean("confirmado") == true

                if (titulo.isBlank() || rawWhen.isBlank()) {
                    LiveToolExecution("Informe o título e a data/hora para criar o evento na agenda.")
                } else {
                    val parsed = PortugueseDateTimeParser.parseDateTime(rawWhen, ZonedDateTime.now())
                    if (parsed == null) {
                        LiveToolExecution("Não consegui entender a data/hora \"$rawWhen\". Diga, por exemplo, 'amanhã às 3 da tarde' ou 'sexta 9h'.")
                    } else if (!confirmed) {
                        val locPart = if (local.isNotBlank()) " em $local" else ""
                        LiveToolExecution(
                            "CONFIRMAÇÃO OBRIGATÓRIA: Pergunte ao motorista: \"Crio o evento '$titulo' para ${parsed.labelPt} (${duracaoMin} min$locPart)?\" Só chame criar_evento novamente com confirmado=true depois que ele confirmar com sim."
                        )
                    } else {
                        val startMs = parsed.dateTime.toInstant().toEpochMilli()
                        val endMs = startMs + duracaoMin * 60_000L
                        val res = host.createCalendarEvent(titulo, startMs, endMs, local, lembreteMin)
                        LiveToolExecution(res, "Evento criado")
                    }
                }
            }

            "lembrete" -> {
                val texto = args?.optString("texto")?.trim().orEmpty()
                val quando = args?.optString("quando")?.trim().orEmpty()
                if (texto.isBlank() || quando.isBlank()) {
                    LiveToolExecution("Informe o texto e quando deseja ser lembrado.")
                } else {
                    val parsed = PortugueseDateTimeParser.parseDateTime(quando, ZonedDateTime.now())
                    if (parsed == null) {
                        LiveToolExecution("Não consegui entender o horário \"$quando\". Diga, por exemplo, 'daqui a 20 minutos' ou 'amanhã às 9h'.")
                    } else {
                        val triggerMs = parsed.dateTime.toInstant().toEpochMilli()
                        val res = host.scheduleLocalReminder(texto, triggerMs)
                        LiveToolExecution(res, "Lembrete")
                    }
                }
            }

            "abrir_app" -> {
                val nome = args?.optString("nome")?.trim().orEmpty()
                if (nome.isBlank()) {
                    LiveToolExecution("Informe o nome do aplicativo que deseja abrir no tablet.")
                } else {
                    val res = host.openTabletApp(nome)
                    LiveToolExecution(res, "App: $nome")
                }
            }

            "abrir_no_celular" -> {
                val app = args?.optString("app")?.trim().orEmpty()
                if (app.isBlank()) {
                    LiveToolExecution("Informe o nome do aplicativo que deseja abrir no celular.")
                } else {
                    val res = host.openPhoneApp(app)
                    LiveToolExecution(res, "Celular: $app")
                }
            }

            "ler_mensagens", "ler_whatsapp" -> {
                val res = host.readPhoneMessages()
                LiveToolExecution(res, "Mensagens")
            }

            "responder_mensagem", "responder_whatsapp" -> {
                val msg = args?.optString("mensagem")?.trim().orEmpty()
                val msgId = args?.optString("id")?.trim()?.takeIf { it.isNotBlank() }
                val confirmed = args?.optBoolean("confirmado") == true
                if (msg.isBlank()) {
                    LiveToolExecution("Informe o texto da resposta para enviar.")
                } else if (!confirmed) {
                    val latest = host.getLatestPhoneMessageSummary()
                        ?: host.getWhatsAppMessages().firstOrNull()?.let { it.key to it.sender }
                    if (latest == null) {
                        LiveToolExecution("Nenhuma mensagem recente disponível para responder.")
                    } else {
                        LiveToolExecution(
                            "CONFIRMAÇÃO OBRIGATÓRIA: Pergunte ao motorista exatamente: \"Respondo para ${latest.second}: '$msg'?\" Só chame responder_mensagem novamente com confirmado=true depois que ele disser sim."
                        )
                    }
                } else {
                    val res = host.replyPhoneMessage(msgId, msg)
                    LiveToolExecution(res, "Mensagem respondida")
                }
            }

            "enviar_whatsapp" -> {
                val target = args?.optString("contato")?.trim()?.takeIf { it.isNotBlank() }
                    ?: args?.optString("numero")?.trim().orEmpty()
                val texto = args?.optString("texto")?.trim().orEmpty()
                val confirmed = args?.optBoolean("confirmado") == true
                if (target.isBlank() || texto.isBlank()) {
                    LiveToolExecution("Informe o contato ou número e o texto da mensagem para o WhatsApp.")
                } else if (!confirmed) {
                    val resolved = host.resolvePhoneRecipient(target)
                    if (!resolved.ok) {
                        LiveToolExecution(resolved.errorMessage ?: "Não encontrei o contato \"$target\".")
                    } else {
                        LiveToolExecution(
                            "CONFIRMAÇÃO OBRIGATÓRIA: Pergunte ao motorista exatamente: \"Mando para ${resolved.displayName}: '$texto'?\" Só chame enviar_whatsapp novamente com confirmado=true depois que ele responder sim."
                        )
                    }
                } else {
                    val res = host.sendWhatsAppTextViaPhone(target, texto)
                    LiveToolExecution(res, "WhatsApp")
                }
            }

            "enviar_audio_whatsapp" -> {
                val target = args?.optString("contato")?.trim()?.takeIf { it.isNotBlank() }
                    ?: args?.optString("numero")?.trim().orEmpty()
                val confirmed = args?.optBoolean("confirmado") == true
                if (target.isBlank()) {
                    LiveToolExecution("Informe o contato ou número para enviar a mensagem de voz no WhatsApp.")
                } else if (!confirmed) {
                    val resolved = host.resolvePhoneRecipient(target)
                    if (!resolved.ok) {
                        LiveToolExecution(resolved.errorMessage ?: "Não encontrei o contato \"$target\".")
                    } else {
                        LiveToolExecution(
                            "CONFIRMAÇÃO OBRIGATÓRIA: Pergunte ao motorista: \"Gravo e mando um áudio no WhatsApp para ${resolved.displayName}?\" Só chame enviar_audio_whatsapp com confirmado=true depois que ele disser sim."
                        )
                    }
                } else {
                    val res = onRecordVoiceNote?.invoke(target) ?: host.recordAndSendWhatsAppVoiceViaPhone(target)
                    LiveToolExecution(res, "Áudio WhatsApp")
                }
            }

            "ligar" -> {
                val target = args?.optString("contato")?.trim()?.takeIf { it.isNotBlank() }
                    ?: args?.optString("numero")?.trim().orEmpty()
                if (target.isBlank()) {
                    LiveToolExecution("Informe o contato ou número para ligar.")
                } else {
                    val res = host.dialPhoneViaPhone(target)
                    LiveToolExecution(res, "Ligação")
                }
            }

            "ligar_whatsapp" -> {
                val target = args?.optString("contato")?.trim()?.takeIf { it.isNotBlank() }
                    ?: args?.optString("numero")?.trim().orEmpty()
                if (target.isBlank()) {
                    LiveToolExecution("Informe o contato ou número para ligar no WhatsApp.")
                } else {
                    val res = host.callWhatsAppViaPhone(target)
                    LiveToolExecution(res, "Ligação WhatsApp")
                }
            }

            "atender" -> {
                val res = host.answerPhoneCall()
                LiveToolExecution(res, "Chamada atendida")
            }

            "desligar" -> {
                val res = host.endPhoneCall()
                LiveToolExecution(res, "Chamada encerrada")
            }

            "controlar_camera_re" -> {
                val acao = args?.optString("acao")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val res = if (acao == "close") host.closeReverseCamera() else host.openReverseCamera()
                LiveToolExecution(res, "Câmera de Ré")
            }

            "configurar_aparencia" -> {
                val tema = args?.optString("tema_mapa")?.trim().orEmpty()
                val cor = args?.optString("cor_destaque")?.trim().orEmpty()
                val parts = mutableListOf<String>()
                if (tema.isNotBlank()) {
                    parts += host.setMapTheme(tema)
                }
                if (cor.isNotBlank()) {
                    val hex = resolveAccentHex(cor)
                    if (hex != null) {
                        parts += host.setAccentColor(hex)
                    } else {
                        parts += "Cor não reconhecida: $cor. Opções: ciano, lima, âmbar, violeta ou rosa."
                    }
                }
                val result = parts.joinToString(". ").ifBlank { "Informe tema_mapa (AUTO/LIGHT/DARK) ou cor_destaque." }
                LiveToolExecution(result, "Aparência")
            }

            "limpar_memoria_conversas" -> {
                onClearMemory()
                LiveToolExecution("Memória e histórico de conversas apagados com sucesso.", "Memória limpa")
            }

            else -> LiveToolExecution("Ferramenta desconhecida: ${call.name}")
        }
    }

    private fun buildTelemetryResponse(tipo: String, host: CarAgentHostBridge): String {
        val hu = host.getHeadUnitState()
        val snap = hu.obdSnapshot
        val derived = snap.derived
        return when (tipo) {
            "temperatura" -> {
                val t = snap.coolantTempC?.toInt()
                if (t != null) "Temperatura do motor em $t°C (${derived.thermalStatusLabel})."
                else "Sem leitura de temperatura do motor no momento."
            }
            "bateria" -> {
                val v = snap.batteryVoltageV
                if (v != null) "Bateria em ${"%.1f".format(Locale.US, v)}V."
                else "Sem leitura de voltagem da bateria no momento."
            }
            "consumo", "tanque" -> {
                val avg = snap.averageKmPerL?.let { "%.1f km/L".format(Locale.US, it) } ?: "ainda calculando"
                val inst = snap.instantKmPerL?.let { "%.1f km/L".format(Locale.US, it) } ?: "--"
                val range = derived.estimatedRangeKm?.let { "${it.toInt()} km" } ?: "indisponível"
                val fuel = host.getFuelLogSummary()?.let { " Diário: $it." }.orEmpty()
                "Consumo médio $avg (instantâneo $inst), tanque virtual em ${derived.virtualTankPercent.toInt()}% (${"%.1f".format(Locale.US, derived.virtualTankRemainingLiters)} L) e autonomia estimada de $range.$fuel"
            }
            "alertas" -> {
                if (hu.obdAlerts.isEmpty()) "Nenhum alerta OBD ativo no Civic."
                else "Alertas ativos: ${hu.obdAlerts.joinToString("; ") { "${it.title}${if (it.detail.isNotBlank()) " (${it.detail})" else ""}" }}."
            }
            "dtc" -> host.readDtcSummaryForAgent()
            "manutencao" -> host.getMaintenanceSummary() ?: "Nenhum item de manutenção cadastrado no painel."
            "relatorio" -> host.buildCarReport()
            else -> {
                val temp = snap.coolantTempC?.let { "${it.toInt()}°C" } ?: "sem leitura"
                val volt = snap.batteryVoltageV?.let { "%.1fV".format(Locale.US, it) } ?: "sem leitura"
                val load = snap.engineLoadPct?.let { "${it.toInt()}%" } ?: "--"
                val spd = derived.correctedSpeedKmh?.toInt() ?: snap.speedKmh?.toInt() ?: 0
                val rpm = snap.rpm?.toInt() ?: 0
                val alerts = if (hu.obdAlerts.isEmpty()) "sem alertas" else "${hu.obdAlerts.size} alertas"
                val dtcs = if (hu.dtcCodes.isEmpty()) "sem falhas DTC" else "${hu.dtcCodes.size} falhas (${hu.dtcCodes.joinToString { it.code }})"
                "Civic (${hu.obdStatus.labelPt}): $spd km/h, $rpm RPM (${derived.estimatedGearLabel}), motor $temp, bateria $volt, carga $load, $alerts, $dtcs."
            }
        }
    }

    private fun matchFavorite(destino: String, favorites: List<FavoriteRef>): FavoriteRef? {
        val norm = CopilotIntentParser.normalize(destino)
        if (norm.isEmpty()) return null
        return favorites.firstOrNull { f ->
            val n = CopilotIntentParser.normalize(f.name)
            val id = CopilotIntentParser.normalize(f.id)
            val v = CopilotIntentParser.normalize(f.voice)
            n == norm || id == norm || (v.isNotEmpty() && v == norm)
        }
    }

    internal fun resolveAccentHex(raw: String): String? {
        val trimmed = raw.trim()
        if (Regex("^#[0-9A-Fa-f]{6}$").matches(trimmed)) {
            return trimmed.uppercase(Locale.ROOT)
        }
        val norm = CopilotIntentParser.normalize(trimmed)
        return ACCENT_ALIASES.entries.firstOrNull { (k, _) -> norm.contains(k) }?.value
    }

    private fun fnDecl(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put(
            "parameters",
            JSONObject().apply {
                put("type", "OBJECT")
                put("properties", properties)
                if (required.isNotEmpty()) {
                    put("required", JSONArray(required))
                }
            }
        )
    }

    private fun strProp(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun numProp(desc: String) = JSONObject().put("type", "NUMBER").put("description", desc)
    private fun intProp(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)
    private fun boolProp(desc: String) = JSONObject().put("type", "BOOLEAN").put("description", desc)
    private fun enumProp(desc: String, values: List<String>) = JSONObject().apply {
        put("type", "STRING")
        put("description", desc)
        put("enum", JSONArray(values))
    }
}
