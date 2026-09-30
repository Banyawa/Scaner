package com.banyawa.sitescanner.core.util

/** Open-addressing `Long → Int` map over primitive arrays (no boxing). Keys must not be [Long.MIN_VALUE]. */
class LongIntMap(expectedSize: Int = 1024) {
    private var keys: LongArray
    private var values: IntArray
    private var mask: Int

    var size = 0
        private set

    init {
        var cap = 16
        while (cap < expectedSize * 2) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        values = IntArray(cap)
        mask = cap - 1
    }

    /** The value for [key], or -1. */
    operator fun get(key: Long): Int {
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) return -1
            i = (i + 1) and mask
        }
    }

    operator fun set(key: Long, value: Int) {
        if ((size + 1) * 10 > keys.size * 6) grow()
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) {
                values[i] = value
                return
            }
            if (k == EMPTY) {
                keys[i] = key
                values[i] = value
                size++
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun slot(key: Long): Int {
        val h = key * -0x61c8864680b583ebL
        return (h xor (h ushr 29)).toInt() and mask
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = IntArray(oldKeys.size * 2)
        mask = keys.size - 1
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != EMPTY) set(oldKeys[i], oldValues[i])
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}

/** Growable primitive int array. */
class IntList(capacity: Int = 1024) {
    private var data = IntArray(maxOf(capacity, 1))

    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    operator fun set(i: Int, v: Int) {
        require(i in 0 until size) { "index $i of $size" }
        data[i] = v
    }

    fun toArray(): IntArray = data.copyOf(size)
}
