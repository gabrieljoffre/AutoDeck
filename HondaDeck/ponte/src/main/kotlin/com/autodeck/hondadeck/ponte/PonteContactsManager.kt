package com.autodeck.hondadeck.ponte

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.autodeck.protocol.phone.BrazilPhoneNumber
import com.autodeck.protocol.phone.PhoneContact
import java.text.Normalizer
import java.util.Locale

/**
 * Pesquisa contatos na agenda do celular (`READ_CONTACTS`), retornando contatos com e sem
 * WhatsApp, números normalizados no padrão brasileiro (+55 DDD) e o ID da linha de ligação
 * VoIP do WhatsApp (`vnd.android.cursor.item/vnd.com.whatsapp.voip.call`) quando existir.
 */
class PonteContactsManager(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun search(query: String, limit: Int = 12): List<PhoneContact> {
        if (!hasPermission()) return emptyList()
        val rawQ = query.trim()
        if (rawQ.isEmpty()) return emptyList()

        val normQuery = normalizeText(rawQ)
        val digitsQuery = rawQ.replace(Regex("""\D+"""), "")
        val cr = context.contentResolver

        val matchedContactIds = LinkedHashMap<String, MutableList<PhoneContact>>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idxId = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val idxName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val idxNum = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val cid = cursor.getString(idxId) ?: continue
                val name = cursor.getString(idxName)?.trim().orEmpty()
                val rawPhone = cursor.getString(idxNum)?.trim().orEmpty()
                if (name.isBlank() && rawPhone.isBlank()) continue

                val normName = normalizeText(name)
                val normalizedPhone = BrazilPhoneNumber.normalize(rawPhone) ?: rawPhone
                val phoneDigits = normalizedPhone.replace(Regex("""\D+"""), "")

                val nameMatches = normQuery.isNotEmpty() && normName.contains(normQuery)
                val phoneMatches = digitsQuery.length >= 4 && phoneDigits.contains(digitsQuery)
                if (!nameMatches && !phoneMatches) continue

                val bucket = matchedContactIds.getOrPut(cid) { mutableListOf() }
                if (bucket.none { BrazilPhoneNumber.sameNumber(it.normalizedPhone, normalizedPhone) || it.normalizedPhone == normalizedPhone }) {
                    bucket += PhoneContact(
                        id = cid,
                        name = name.ifBlank { normalizedPhone },
                        phone = rawPhone,
                        normalizedPhone = normalizedPhone
                    )
                }
                if (matchedContactIds.size >= 40) break
            }
        }

        if (matchedContactIds.isEmpty()) return emptyList()

        // Enriquecer com presença do WhatsApp e linha de ligação VoIP
        val waInfo = queryWhatsAppMeta(matchedContactIds.keys)
        val all = matchedContactIds.values.flatten().map { contact ->
            val meta = waInfo[contact.id]
            val voipId = meta?.voipByDigits?.entries?.firstOrNull { (digits, _) ->
                BrazilPhoneNumber.sameNumber(digits, contact.normalizedPhone)
            }?.value ?: meta?.defaultVoipId
            contact.copy(
                hasWhatsApp = meta != null,
                whatsAppVoipDataId = voipId
            )
        }

        return all
            .sortedWith(
                compareByDescending<PhoneContact> { normalizeText(it.name) == normQuery }
                    .thenByDescending { normalizeText(it.name).startsWith(normQuery) }
                    .thenByDescending { it.hasWhatsApp }
                    .thenBy { normalizeText(it.name) }
            )
            .take(limit)
    }

    /** Procura o `Data._ID` de ligação VoIP do WhatsApp diretamente por número de telefone. */
    fun findWhatsAppVoipDataIdByNumber(number: String): Long? {
        if (!hasPermission()) return null
        val targetDigits = BrazilPhoneNumber.toWhatsAppDigits(number) ?: return null
        val cr = context.contentResolver
        val projection = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA3
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(MIME_WHATSAPP_VOIP_CALL)

        cr.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idxId = cursor.getColumnIndex(ContactsContract.Data._ID)
            val idxData1 = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            val idxData3 = cursor.getColumnIndex(ContactsContract.Data.DATA3)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idxId)
                val d1 = cursor.getString(idxData1).orEmpty()
                val d3 = cursor.getString(idxData3).orEmpty()
                val candidate = d1.substringBefore("@").ifBlank { d3 }
                if (BrazilPhoneNumber.sameNumber(candidate, targetDigits)) {
                    return id
                }
            }
        }
        return null
    }

    /** Resolve o nome do contato a partir de um número (para exibir no banner de chamada). */
    fun resolveDisplayNameByNumber(number: String): String? {
        if (!hasPermission() || number.isBlank()) return null
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(number)
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    private data class WhatsAppMeta(
        var defaultVoipId: Long? = null,
        val voipByDigits: MutableMap<String, Long> = mutableMapOf()
    )

    private fun queryWhatsAppMeta(contactIds: Set<String>): Map<String, WhatsAppMeta> {
        if (contactIds.isEmpty()) return emptyMap()
        val result = HashMap<String, WhatsAppMeta>()
        val placeholders = contactIds.joinToString(",") { "?" }
        val selection =
            "${ContactsContract.Data.CONTACT_ID} IN ($placeholders) AND " +
                "${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val args = contactIds.toTypedArray() + arrayOf(MIME_WHATSAPP_PROFILE, MIME_WHATSAPP_VOIP_CALL)
        val projection = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val idxId = cursor.getColumnIndex(ContactsContract.Data._ID)
                val idxCid = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val idxMime = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val idxData1 = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                while (cursor.moveToNext()) {
                    val dataId = cursor.getLong(idxId)
                    val cid = cursor.getString(idxCid) ?: continue
                    val mime = cursor.getString(idxMime).orEmpty()
                    val data1 = cursor.getString(idxData1).orEmpty()
                    val meta = result.getOrPut(cid) { WhatsAppMeta() }
                    if (mime == MIME_WHATSAPP_VOIP_CALL) {
                        if (meta.defaultVoipId == null) meta.defaultVoipId = dataId
                        val digits = data1.substringBefore("@").replace(Regex("""\D+"""), "")
                        if (digits.isNotEmpty()) meta.voipByDigits[digits] = dataId
                    }
                }
            }
        }
        return result
    }

    private fun normalizeText(input: String): String {
        val lower = input.lowercase(Locale.forLanguageTag("pt-BR"))
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return nfd.replace(Regex("""\p{M}+"""), "").replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        const val MIME_WHATSAPP_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        const val MIME_WHATSAPP_VOIP_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    }
}
