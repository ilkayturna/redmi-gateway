package com.ilkay.redmigateway.server

import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cache and resolution hub for contact names received from the paired iPhone
 * or seeded from local bundled contacts.
 */
object ContactResolver {
    private const val TAG = "ContactResolver"

    data class SeedContact(val name: String, val numbers: List<String>)

    interface Listener {
        fun onContactResolved(number: String, name: String)
    }

    private val cache = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val seedContactList = CopyOnWriteArrayList<SeedContact>()
    private val syncLock = Any()
    @Volatile private var isLoaded = false
    @Volatile private var isSyncedToSystem = false

    fun getSeedContacts(): List<SeedContact> = seedContactList

    fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return number.trim().uppercase()
        if (digits.length == 14 && digits.startsWith("0090")) return digits.takeLast(10)
        if (digits.length == 12 && digits.startsWith("90")) return digits.takeLast(10)
        if (digits.length == 11 && digits.startsWith("0")) return digits.takeLast(10)
        if (digits.length > 10) {
            val last10 = digits.takeLast(10)
            // Only reduce to the last ten when the number actually looks Turkish. This branch used
            // to fire on the last ten digits alone, so a German number ending 5112345678 was cut
            // down to 5112345678 and would then match a Turkish contact: the call would show the
            // wrong person's name, which is worse than showing no name at all. A real Turkish
            // number of this length starts with the country code or the trunk zero, and the two
            // rules above handle the ordinary spellings, so nothing that used to resolve stops
            // resolving.
            val looksTurkish = digits.startsWith("90") || digits.startsWith("0")
            if (last10.startsWith("5") && looksTurkish) return last10
        }
        if (digits.length == 10) return digits
        return digits
    }

    fun loadSeedContacts(context: Context) {
        if (isLoaded) return
        Thread({
            synchronized(this) {
                if (isLoaded) return@Thread
                isLoaded = true
            }
            try {
                var contactCount = 0
                var numberCount = 0
                var currentFn: String? = null
                var currentN: String? = null
                val currentTels = mutableListOf<String>()

                context.assets.open("seed_contacts.vcf").use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line?.trim() ?: continue
                            when {
                                l.equals("BEGIN:VCARD", ignoreCase = true) -> {
                                    currentFn = null
                                    currentN = null
                                    currentTels.clear()
                                }
                                l.startsWith("FN:", ignoreCase = true) || l.startsWith("FN;", ignoreCase = true) -> {
                                    val idx = l.indexOf(':')
                                    if (idx != -1) {
                                        val fn = l.substring(idx + 1).trim()
                                            .replace("\\,", ",")
                                            .replace("\\;", ";")
                                            .replace("\\n", " ")
                                        if (fn.isNotEmpty()) {
                                            currentFn = fn
                                        }
                                    }
                                }
                                l.startsWith("N:", ignoreCase = true) || l.startsWith("N;", ignoreCase = true) -> {
                                    val idx = l.indexOf(':')
                                    if (idx != -1) {
                                        val parts = l.substring(idx + 1).split(';')
                                            .map { it.trim().replace("\\,", ",").replace("\\;", ";") }
                                            .filter { it.isNotEmpty() }
                                        if (parts.isNotEmpty()) {
                                            currentN = parts.reversed().joinToString(" ")
                                        }
                                    }
                                }
                                l.startsWith("TEL", ignoreCase = true) -> {
                                    val idx = l.indexOf(':')
                                    if (idx != -1) {
                                        val tel = l.substring(idx + 1).trim()
                                        if (tel.isNotEmpty()) {
                                            currentTels.add(tel)
                                        }
                                    }
                                }
                                l.equals("END:VCARD", ignoreCase = true) -> {
                                    val name = currentFn ?: currentN
                                    if (!name.isNullOrBlank() && currentTels.isNotEmpty()) {
                                        contactCount++
                                        val nums = currentTels.toList()
                                        seedContactList.add(SeedContact(name, nums))
                                        for (rawTel in nums) {
                                            val normalized = normalize(rawTel)
                                            if (normalized.isNotBlank()) {
                                                cache.putIfAbsent(normalized, name)
                                                numberCount++
                                            }
                                        }
                                    }
                                    currentFn = null
                                    currentN = null
                                    currentTels.clear()
                                }
                            }
                        }
                    }
                }
                Log.i(TAG, "Loaded seed contacts: $contactCount contacts, $numberCount numbers mapped")
                syncToSystemContacts(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load seed contacts: ${e.message}")
            }
        }, "ContactResolver-SeedLoader").start()
    }

    fun syncToSystemContacts(context: Context) {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WRITE_CONTACTS permission not granted, skipping system contacts sync")
            return
        }
        if (seedContactList.isEmpty()) {
            Log.d(TAG, "Seed contacts list is empty, skipping sync")
            return
        }
        if (isSyncedToSystem) return
        Thread({
            synchronized(syncLock) {
                if (isSyncedToSystem) return@Thread
                try {
                    val count = runCatching {
                        context.contentResolver.query(
                            ContactsContract.Contacts.CONTENT_URI,
                            arrayOf(ContactsContract.Contacts._ID),
                            null, null, null
                        )?.use { it.count } ?: 0
                    }.getOrElse { 0 }
                    if (count >= 50) {
                        Log.i(TAG, "System contacts already populated ($count contacts), skipping seed sync")
                        isSyncedToSystem = true
                        return@Thread
                    }

                    Log.i(TAG, "Starting sync of ${seedContactList.size} seed contacts to ContactsContract...")
                    val batch = ArrayList<ContentProviderOperation>()
                    var inBatchCount = 0
                    var totalInserted = 0

                    for (contact in seedContactList) {
                        val rawContactIndex = batch.size
                        batch.add(
                            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                                .withValue(RawContacts.ACCOUNT_TYPE, null)
                                .withValue(RawContacts.ACCOUNT_NAME, null)
                                .build()
                        )
                        batch.add(
                            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactIndex)
                                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                                .withValue(StructuredName.DISPLAY_NAME, contact.name)
                                .build()
                        )
                        for (number in contact.numbers) {
                            batch.add(
                                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                                    .withValueBackReference(Data.RAW_CONTACT_ID, rawContactIndex)
                                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                                    .withValue(Phone.NUMBER, number)
                                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                                    .build()
                            )
                        }
                        inBatchCount++
                        if (inBatchCount >= 50) {
                            runCatching {
                                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, batch)
                                totalInserted += inBatchCount
                            }.onFailure { e ->
                                Log.w(TAG, "Failed to apply batch of contacts: ${e.message}")
                            }
                            batch.clear()
                            inBatchCount = 0
                        }
                    }
                    if (batch.isNotEmpty()) {
                        runCatching {
                            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, batch)
                            totalInserted += inBatchCount
                        }.onFailure { e ->
                            Log.w(TAG, "Failed to apply final batch of contacts: ${e.message}")
                        }
                        batch.clear()
                    }
                    isSyncedToSystem = true
                    Log.i(TAG, "Successfully synced $totalInserted contacts to Android ContactsContract")
                } catch (e: Exception) {
                    Log.w(TAG, "Error syncing seed contacts to system: ${e.message}")
                }
            }
        }, "ContactResolver-SystemSync").start()
    }

    fun put(number: String, name: String) {
        if (name.isBlank()) return
        val normalized = normalize(number)
        cache[normalized] = name.trim()
        for (listener in listeners) {
            runCatching { listener.onContactResolved(number, name.trim()) }
        }
    }

    fun getCached(number: String?): String? {
        if (number.isNullOrBlank()) return null
        return cache[normalize(number)]
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun clear() {
        cache.clear()
        seedContactList.clear()
        isLoaded = false
        isSyncedToSystem = false
    }
}
