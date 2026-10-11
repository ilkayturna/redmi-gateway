package com.ilkay.redmigateway.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cache and resolution hub for contact names received from the paired iPhone.
 */
object ContactResolver {
    interface Listener {
        fun onContactResolved(number: String, name: String)
    }

    private val cache = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return number.trim().uppercase()
        if (digits.length == 12 && digits.startsWith("90")) return digits.takeLast(10)
        if (digits.length == 11 && digits.startsWith("0")) return digits.takeLast(10)
        return digits
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
    }
}
