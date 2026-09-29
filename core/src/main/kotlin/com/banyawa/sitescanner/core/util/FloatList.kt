package com.banyawa.sitescanner.core.util

/** Growable primitive float array. */
internal class FloatList(capacity: Int = 1024) {
    private var data = FloatArray(maxOf(capacity, 1))

    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Float = data[i]

    fun toArray(): FloatArray = data.copyOf(size)
}
