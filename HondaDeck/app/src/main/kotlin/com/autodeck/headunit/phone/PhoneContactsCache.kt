package com.autodeck.headunit.phone

import com.autodeck.protocol.phone.ContactMatchResult
import com.autodeck.protocol.phone.PhoneContact
import com.autodeck.protocol.phone.PhoneContactMatcher
import com.autodeck.protocol.phone.PhoneLinkCodec
import java.io.File

data class ContactsCacheSnapshot(
    val contacts: List<PhoneContact> = emptyList(),
    val syncedAtMs: Long = 0L
) {
    val count: Int get() = contacts.size
}

/**
 * Cache local persistente dos contatos sincronizados do celular Samsung (`phone_contacts_cache.json`).
 * Permite busca instantânea e offline no tablet, além de alimentar o `systemInstruction` do Gemini
 * com os ~30 contatos mais frequentes/favoritos.
 */
class PhoneContactsCache(private val file: File?) {

    private val lock = Any()
    private var snapshot: ContactsCacheSnapshot = readFromDisk()

    fun current(): ContactsCacheSnapshot = synchronized(lock) { snapshot }

    fun contacts(): List<PhoneContact> = synchronized(lock) { snapshot.contacts }

    fun count(): Int = synchronized(lock) { snapshot.contacts.size }

    fun syncedAtMs(): Long = synchronized(lock) { snapshot.syncedAtMs }

    fun save(contacts: List<PhoneContact>, syncedAtMs: Long = System.currentTimeMillis()): ContactsCacheSnapshot {
        val next = ContactsCacheSnapshot(contacts = contacts, syncedAtMs = syncedAtMs)
        synchronized(lock) {
            snapshot = next
            writeToDisk(next)
        }
        return next
    }

    fun clear() {
        synchronized(lock) {
            snapshot = ContactsCacheSnapshot()
            runCatching { file?.delete() }
        }
    }

    fun search(query: String, limit: Int = 10): List<PhoneContact> =
        PhoneContactMatcher.search(contacts(), query, limit)

    fun resolve(query: String, maxAmbiguous: Int = 5): ContactMatchResult =
        PhoneContactMatcher.resolve(contacts(), query, maxAmbiguous)

    fun topFrequent(limit: Int = 30): List<PhoneContact> =
        PhoneContactMatcher.topFrequentContacts(contacts(), limit)

    private fun readFromDisk(): ContactsCacheSnapshot {
        val target = file ?: return ContactsCacheSnapshot()
        if (!target.exists() || target.length() == 0L) return ContactsCacheSnapshot()
        return runCatching {
            val raw = target.readText(Charsets.UTF_8)
            val root = PhoneLinkCodec.parseJsonValue(raw) as? Map<*, *> ?: return ContactsCacheSnapshot()
            val syncedAt = (root["syncedAtMs"] as? Number)?.toLong() ?: 0L
            val list = (root["contacts"] as? List<*>)
                ?.mapNotNull { PhoneLinkCodec.mapToContact(it) }
                .orEmpty()
            ContactsCacheSnapshot(contacts = list, syncedAtMs = syncedAt)
        }.getOrDefault(ContactsCacheSnapshot())
    }

    private fun writeToDisk(snap: ContactsCacheSnapshot) {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val payload = linkedMapOf<String, Any?>(
                "syncedAtMs" to snap.syncedAtMs,
                "contacts" to snap.contacts.map { PhoneLinkCodec.contactToMap(it) }
            )
            val encoded = PhoneLinkCodec.encodeValue(payload)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(encoded, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(encoded, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
