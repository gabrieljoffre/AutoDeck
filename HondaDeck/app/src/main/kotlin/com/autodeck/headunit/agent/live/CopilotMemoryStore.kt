package com.autodeck.headunit.agent.live

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CopilotMemoryTurn(
    val role: String, // "user" | "model"
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CopilotMemorySession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val mode: String, // "live" | "ask" | "text"
    val summary: String,
    val turns: List<CopilotMemoryTurn>
)

/**
 * Armazenamento local das conversas do Copiloto (transcrições + resumo curto por sessão).
 * Os últimos resumos são injetados no `systemInstruction` do Gemini Live e do modo texto.
 */
class CopilotMemoryStore(
    private val storageFile: File? = null
) {
    private val lock = Any()
    private val sessions = mutableListOf<CopilotMemorySession>()

    init {
        loadFromDiskLocked()
    }

    fun recordSession(
        mode: String,
        turns: List<CopilotMemoryTurn>,
        startedAtMs: Long = System.currentTimeMillis(),
        endedAtMs: Long = System.currentTimeMillis(),
        explicitSummary: String? = null
    ): CopilotMemorySession? {
        val cleaned = turns
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() }
            .takeLast(MAX_TURNS_PER_SESSION)
        if (cleaned.isEmpty()) return null

        val summary = explicitSummary?.trim()?.takeIf { it.isNotBlank() }
            ?: summarizeTurns(cleaned)
        val session = CopilotMemorySession(
            id = "sess_${startedAtMs}_${cleaned.size}",
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs.coerceAtLeast(startedAtMs),
            mode = mode.ifBlank { "live" },
            summary = summary,
            turns = cleaned
        )

        synchronized(lock) {
            sessions.add(session)
            trimLocked()
            saveToDiskLocked()
        }
        return session
    }

    fun recordSingleExchange(
        userText: String,
        replyText: String,
        mode: String = "text",
        nowMs: Long = System.currentTimeMillis()
    ): CopilotMemorySession? {
        val u = userText.trim()
        val r = replyText.trim()
        if (u.isBlank() && r.isBlank()) return null

        val newTurns = buildList {
            if (u.isNotBlank()) add(CopilotMemoryTurn("user", u, nowMs))
            if (r.isNotBlank()) add(CopilotMemoryTurn("model", r, nowMs))
        }

        synchronized(lock) {
            val last = sessions.lastOrNull()
            if (last != null && last.mode == mode && (nowMs - last.endedAtMs) <= MERGE_WINDOW_MS) {
                val mergedTurns = (last.turns + newTurns).takeLast(MAX_TURNS_PER_SESSION)
                val updated = last.copy(
                    endedAtMs = nowMs,
                    turns = mergedTurns,
                    summary = summarizeTurns(mergedTurns)
                )
                sessions[sessions.lastIndex] = updated
                saveToDiskLocked()
                return updated
            }
            val created = CopilotMemorySession(
                id = "sess_${nowMs}_${newTurns.size}",
                startedAtMs = nowMs,
                endedAtMs = nowMs,
                mode = mode,
                summary = summarizeTurns(newTurns),
                turns = newTurns
            )
            sessions.add(created)
            trimLocked()
            saveToDiskLocked()
            return created
        }
    }

    fun loadSessions(): List<CopilotMemorySession> = synchronized(lock) {
        sessions.toList()
    }

    fun recentSummaries(limit: Int = 5): List<String> = synchronized(lock) {
        sessions.takeLast(limit.coerceAtLeast(1)).map { s ->
            val whenLabel = formatShortDateTime(s.endedAtMs)
            "$whenLabel (${modeLabelPt(s.mode)}): ${s.summary}"
        }
    }

    fun clear() {
        synchronized(lock) {
            sessions.clear()
            runCatching {
                if (storageFile != null && storageFile.exists()) {
                    storageFile.delete()
                }
            }
        }
    }

    private fun trimLocked() {
        while (sessions.size > MAX_SESSIONS) {
            sessions.removeAt(0)
        }
    }

    private fun loadFromDiskLocked() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            val arr = root.optJSONArray("sessions") ?: return
            sessions.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val turnsArr = o.optJSONArray("turns") ?: JSONArray()
                val turns = buildList {
                    for (j in 0 until turnsArr.length()) {
                        val t = turnsArr.optJSONObject(j) ?: continue
                        val txt = t.optString("text").trim()
                        if (txt.isNotBlank()) {
                            add(
                                CopilotMemoryTurn(
                                    role = t.optString("role", "user"),
                                    text = txt,
                                    timestampMs = t.optLong("timestampMs", 0L)
                                )
                            )
                        }
                    }
                }
                val summary = o.optString("summary").trim().ifBlank { summarizeTurns(turns) }
                if (summary.isBlank() && turns.isEmpty()) continue
                sessions.add(
                    CopilotMemorySession(
                        id = o.optString("id", "sess_$i"),
                        startedAtMs = o.optLong("startedAtMs", 0L),
                        endedAtMs = o.optLong("endedAtMs", 0L),
                        mode = o.optString("mode", "live"),
                        summary = summary,
                        turns = turns
                    )
                )
            }
            trimLocked()
        }
    }

    private fun saveToDiskLocked() {
        val file = storageFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (s in sessions) {
                val turnsArr = JSONArray()
                for (t in s.turns) {
                    turnsArr.put(
                        JSONObject().apply {
                            put("role", t.role)
                            put("text", t.text)
                            put("timestampMs", t.timestampMs)
                        }
                    )
                }
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("startedAtMs", s.startedAtMs)
                        put("endedAtMs", s.endedAtMs)
                        put("mode", s.mode)
                        put("summary", s.summary)
                        put("turns", turnsArr)
                    }
                )
            }
            val root = JSONObject().apply {
                put("version", 1)
                put("sessions", arr)
            }
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    companion object {
        private const val MAX_SESSIONS = 20
        private const val MAX_TURNS_PER_SESSION = 30
        private const val MERGE_WINDOW_MS = 10 * 60_000L
        private val SHORT_DT_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.forLanguageTag("pt-BR"))

        fun summarizeTurns(turns: List<CopilotMemoryTurn>): String {
            if (turns.isEmpty()) return ""
            val userTopics = turns
                .filter { it.role == "user" }
                .map { compactClause(it.text, 70) }
                .distinct()
                .take(3)
            val lastReply = turns
                .lastOrNull { it.role != "user" }
                ?.text
                ?.let { compactClause(it, 90) }

            return buildString {
                if (userTopics.isNotEmpty()) {
                    append("Motorista: ").append(userTopics.joinToString("; "))
                }
                if (!lastReply.isNullOrBlank()) {
                    if (isNotEmpty()) append(" → ")
                    append("Copiloto: ").append(lastReply)
                }
            }.take(220)
        }

        private fun compactClause(text: String, maxLen: Int): String {
            val singleLine = text.replace(Regex("\\s+"), " ").trim()
            return if (singleLine.length <= maxLen) {
                singleLine
            } else {
                singleLine.take(maxLen - 1).trimEnd() + "…"
            }
        }

        private fun modeLabelPt(mode: String): String = when (mode.lowercase(Locale.ROOT)) {
            "live" -> "Conversa ao vivo"
            "ask" -> "Peça ao Gemini"
            else -> "Texto"
        }

        private fun formatShortDateTime(epochMs: Long): String = runCatching {
            if (epochMs <= 0L) "Sessão anterior"
            else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(SHORT_DT_FMT)
        }.getOrDefault("Sessão anterior")
    }
}
