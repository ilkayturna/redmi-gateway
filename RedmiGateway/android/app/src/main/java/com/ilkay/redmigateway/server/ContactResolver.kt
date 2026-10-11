package com.ilkay.redmigateway.server

import android.content.Context
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

    interface Listener {
        fun onContactResolved(number: String, name: String)
    }

    private val cache = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var isLoaded = false

    fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return number.trim().uppercase()
        if (digits.length == 14 && digits.startsWith("0090")) return digits.takeLast(10)
        if (digits.length == 12 && digits.startsWith("90")) return digits.takeLast(10)
        if (digits.length == 11 && digits.startsWith("0")) return digits.takeLast(10)
        if (digits.length > 10) {
            val last10 = digits.takeLast(10)
            if (last10.startsWith("5")) return last10
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
                                        for (rawTel in currentTels) {
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
            } catch (e: Exception) {
                Log.w(TAG, "Could not load seed contacts: ${e.message}")
            }
        }, "ContactResolver-SeedLoader").start()
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
        isLoaded = false
    }
}
