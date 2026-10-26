package com.resqmesh.mesh

/** Source of wall-clock time, so tests and the simulator can control it. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
