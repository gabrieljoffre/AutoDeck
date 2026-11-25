package com.autodeck.hondadeck.ponte

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import androidx.core.content.ContextCompat
import com.autodeck.protocol.phone.BrazilPhoneNumber
import com.autodeck.protocol.phone.PhoneContact
import com.autodeck.protocol.phone.PhoneContactMatcher
import com.autodeck.protocol.phone.PhoneContactNumber
import java.io.ByteArrayOutputStream

/**
 * Lê e sincroniza a agenda completa do celular (`READ_CONTACTS`), incluindo:
 * - Nome, apelidos (`Nickname` e `Relation`), todos os números normalizados `+55`,
 * - Presença no WhatsApp via `RawContacts` (`account_type = com.whatsapp` / `com.whatsapp.w4b`)
 *   e linha VoIP (`vnd.android.cursor.item/vnd.com.whatsapp.voip.call`),
 * - Favoritos (`STARRED`), frequência (`TIMES_CONTACTED`, `LAST_TIME_CONTACTED`),
 * - Foto em miniatura opcional (<= 64x64 JPEG base64) para os contatos favoritos/mais frequentes.
 */
class PonteContactsManager(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun search(query: String, limit: Int = 12): List<PhoneContact> {
        if (!hasPermission()) return emptyList()
        val rawQ = query.trim()
        if (rawQ.isEmpty()) return emptyList()
        val all = loadAllContacts(includeSmallPhotosForTop = 0)
        return PhoneContactMatcher.search(all, rawQ, limit = limit)
    }

    /**
     * Carrega a lista completa de contatos com telefone do celular para sincronização com o tablet.
     */
    fun loadAllContacts(includeSmallPhotosForTop: Int = 30): List<PhoneContact> {
        if (!hasPermission()) return emptyList()
        val cr = context.contentResolver

        data class ContactAccumulator(
            val id: String,
            var name: String = "",
            var starred: Boolean = false,
            var timesContacted: Int = 0,
            var lastTimeContactedMs: Long = 0L,
            var photoThumbUri: String? = null,
            val nicknames: LinkedHashSet<String> = linkedSetOf(),
            val numbers: MutableList<PhoneContactNumber> = mutableListOf()
        )

        val contactsById = LinkedHashMap<String, ContactAccumulator>()

        // 1) Metadados base de cada contato (nome, favorito, frequência, thumbnail)
        val contactProjection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.TIMES_CONTACTED,
            ContactsContract.Contacts.LAST_TIME_CONTACTED,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        runCatching {
            cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                contactProjection,
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} > 0",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val idxId = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val idxName = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val idxStar = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                val idxTimes = cursor.getColumnIndex(ContactsContract.Contacts.TIMES_CONTACTED)
                val idxLast = cursor.getColumnIndex(ContactsContract.Contacts.LAST_TIME_CONTACTED)
                val idxPhoto = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                while (cursor.moveToNext()) {
                    val cid = if (idxId >= 0) cursor.getString(idxId) else null
                    if (cid.isNullOrBlank()) continue
                    val acc = contactsById.getOrPut(cid) { ContactAccumulator(id = cid) }
                    if (idxName >= 0) acc.name = cursor.getString(idxName)?.trim().orEmpty()
                    if (idxStar >= 0) acc.starred = cursor.getInt(idxStar) == 1
                    if (idxTimes >= 0) acc.timesContacted = cursor.getInt(idxTimes).coerceAtLeast(0)
                    if (idxLast >= 0) acc.lastTimeContactedMs = cursor.getLong(idxLast).coerceAtLeast(0L)
                    if (idxPhoto >= 0) acc.photoThumbUri = cursor.getString(idxPhoto)?.takeIf { it.isNotBlank() }
                }
            }
        }

        // 2) Todos os números de telefone normalizados (+55)
        val phoneProjection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )
        runCatching {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val idxCid = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val idxName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val idxNum = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idxType = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val idxLabel = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (cursor.moveToNext()) {
                    val cid = if (idxCid >= 0) cursor.getString(idxCid) else null
                    if (cid.isNullOrBlank()) continue
                    val rawNum = if (idxNum >= 0) cursor.getString(idxNum)?.trim().orEmpty() else ""
                    if (rawNum.isBlank()) continue

                    val acc = contactsById.getOrPut(cid) { ContactAccumulator(id = cid) }
                    if (acc.name.isBlank() && idxName >= 0) {
                        acc.name = cursor.getString(idxName)?.trim().orEmpty()
                    }
                    val normalized = BrazilPhoneNumber.normalize(rawNum) ?: rawNum
                    if (acc.numbers.any {
                            it.normalized == normalized || BrazilPhoneNumber.sameNumber(it.normalized, normalized)
                        }
                    ) {
                        continue
                    }
                    val type = if (idxType >= 0) cursor.getInt(idxType) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                    val customLabel = if (idxLabel >= 0) cursor.getString(idxLabel)?.trim().orEmpty() else ""
                    val label = phoneTypeLabel(type, customLabel)
                    acc.numbers += PhoneContactNumber(
                        raw = rawNum,
                        normalized = normalized,
                        label = label
                    )
                }
            }
        }

        val withPhones = contactsById.values.filter { it.numbers.isNotEmpty() }
        if (withPhones.isEmpty()) return emptyList()

        // 3) Apelidos (Nickname) e relações familiares (Relation)
        val nicknamesByCid = queryNicknamesByContactId()
        for ((cid, nicks) in nicknamesByCid) {
            contactsById[cid]?.nicknames?.addAll(nicks)
        }

        // 4) Presença no WhatsApp via RawContacts (account_type = com.whatsapp / com.whatsapp.w4b)
        val whatsAppContactIds = queryWhatsAppRawContactIds()

        // 5) Linhas de perfil e chamada VoIP do WhatsApp por número
        val whatsAppVoipMeta = queryAllWhatsAppVoipMeta()

        // Monta contatos enriquecidos
        val assembled = withPhones.map { acc ->
            val meta = whatsAppVoipMeta[acc.id]
            val hasWaByRaw = acc.id in whatsAppContactIds || meta != null
            val enrichedNumbers = acc.numbers.map { num ->
                val voipId = meta?.voipByDigits?.entries?.firstOrNull { (digits, _) ->
                    BrazilPhoneNumber.sameNumber(digits, num.normalized)
                }?.value
                val numHasWa = voipId != null ||
                    meta?.profileDigits?.any { BrazilPhoneNumber.sameNumber(it, num.normalized) } == true ||
                    (hasWaByRaw && acc.numbers.size == 1)
                num.copy(
                    hasWhatsApp = numHasWa,
                    whatsAppVoipDataId = voipId
                )
            }
            val primary = enrichedNumbers.firstOrNull { it.whatsAppVoipDataId != null }
                ?: enrichedNumbers.firstOrNull { it.hasWhatsApp }
                ?: enrichedNumbers.first()
            val resolvedName = acc.name.ifBlank { primary.normalized }

            PhoneContact(
                id = acc.id,
                name = resolvedName,
                phone = primary.raw,
                normalizedPhone = primary.normalized,
                hasWhatsApp = hasWaByRaw || enrichedNumbers.any { it.hasWhatsApp },
                whatsAppVoipDataId = primary.whatsAppVoipDataId ?: meta?.defaultVoipId,
                nicknames = acc.nicknames.filterNot { it.equals(resolvedName, ignoreCase = true) },
                phones = enrichedNumbers,
                starred = acc.starred,
                timesContacted = acc.timesContacted,
                lastTimeContactedMs = acc.lastTimeContactedMs
            )
        }

        if (includeSmallPhotosForTop <= 0) return assembled

        // 6) Foto pequena opcional em base64 apenas para os ~30 favoritos/frequentes (mantém JSON leve)
        val topIds = PhoneContactMatcher.topFrequentContacts(assembled, limit = includeSmallPhotosForTop)
            .map { it.id }
            .toSet()

        return assembled.map { c ->
            if (c.id in topIds) {
                val uriStr = contactsById[c.id]?.photoThumbUri
                val b64 = uriStr?.let { encodeSmallThumbnail(it) }
                if (b64 != null) c.copy(photoBase64 = b64) else c
            } else {
                c
            }
        }
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
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
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
        val voipByDigits: MutableMap<String, Long> = mutableMapOf(),
        val profileDigits: MutableSet<String> = mutableSetOf()
    )

    private fun queryWhatsAppRawContactIds(): Set<String> {
        val ids = HashSet<String>()
        val projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID)
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} IN (?, ?)"
        val args = arrayOf(ACCOUNT_TYPE_WHATSAPP, ACCOUNT_TYPE_WHATSAPP_BUSINESS)
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val idxCid = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
                while (cursor.moveToNext()) {
                    val cid = if (idxCid >= 0) cursor.getString(idxCid) else null
                    if (!cid.isNullOrBlank()) ids += cid
                }
            }
        }
        return ids
    }

    private fun queryAllWhatsAppVoipMeta(): Map<String, WhatsAppMeta> {
        val result = HashMap<String, WhatsAppMeta>()
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val args = arrayOf(MIME_WHATSAPP_PROFILE, MIME_WHATSAPP_VOIP_CALL)
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
                    val dataId = if (idxId >= 0) cursor.getLong(idxId) else 0L
                    val cid = if (idxCid >= 0) cursor.getString(idxCid) else null
                    if (cid.isNullOrBlank()) continue
                    val mime = if (idxMime >= 0) cursor.getString(idxMime).orEmpty() else ""
                    val data1 = if (idxData1 >= 0) cursor.getString(idxData1).orEmpty() else ""
                    val meta = result.getOrPut(cid) { WhatsAppMeta() }
                    val digits = data1.substringBefore("@").replace(Regex("""\D+"""), "")
                    if (mime == MIME_WHATSAPP_VOIP_CALL) {
                        if (meta.defaultVoipId == null) meta.defaultVoipId = dataId
                        if (digits.isNotEmpty()) meta.voipByDigits[digits] = dataId
                    } else if (mime == MIME_WHATSAPP_PROFILE && digits.isNotEmpty()) {
                        meta.profileDigits += digits
                    }
                }
            }
        }
        return result
    }

    private fun queryNicknamesByContactId(): Map<String, Set<String>> {
        val map = HashMap<String, LinkedHashSet<String>>()
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val args = arrayOf(
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE
        )
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val idxCid = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val idxMime = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val idxData1 = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                val idxData2 = cursor.getColumnIndex(ContactsContract.Data.DATA2)
                while (cursor.moveToNext()) {
                    val cid = if (idxCid >= 0) cursor.getString(idxCid) else null
                    if (cid.isNullOrBlank()) continue
                    val mime = if (idxMime >= 0) cursor.getString(idxMime).orEmpty() else ""
                    val value = if (idxData1 >= 0) cursor.getString(idxData1)?.trim().orEmpty() else ""
                    val bucket = map.getOrPut(cid) { linkedSetOf() }
                    if (value.isNotBlank()) bucket += value
                    if (mime == ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE && idxData2 >= 0) {
                        relationTypeToPortuguese(cursor.getInt(idxData2))?.let { bucket += it }
                    }
                }
            }
        }
        return map
    }

    private fun relationTypeToPortuguese(type: Int): String? = when (type) {
        ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER -> "Mãe"
        ContactsContract.CommonDataKinds.Relation.TYPE_FATHER -> "Pai"
        ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE -> "Esposa"
        ContactsContract.CommonDataKinds.Relation.TYPE_PARTNER -> "Amor"
        ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER -> "Irmão"
        ContactsContract.CommonDataKinds.Relation.TYPE_SISTER -> "Irmã"
        ContactsContract.CommonDataKinds.Relation.TYPE_CHILD -> "Filho"
        ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER -> "Chefe"
        else -> null
    }

    private fun phoneTypeLabel(type: Int, customLabel: String): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Celular"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Casa"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Trabalho"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Principal"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> customLabel.ifBlank { "Outro" }
        else -> customLabel
    }

    private fun encodeSmallThumbnail(uriStr: String): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
            val decoded = BitmapFactory.decodeStream(stream) ?: return null
            val scaled = if (decoded.width > 64 || decoded.height > 64) {
                Bitmap.createScaledBitmap(decoded, 64, 64, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, baos)
            scaled.recycle()
            val bytes = baos.toByteArray()
            if (bytes.isEmpty() || bytes.size > 4_000) return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }.getOrNull()

    companion object {
        const val ACCOUNT_TYPE_WHATSAPP = "com.whatsapp"
        const val ACCOUNT_TYPE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        const val MIME_WHATSAPP_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        const val MIME_WHATSAPP_VOIP_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    }
}
