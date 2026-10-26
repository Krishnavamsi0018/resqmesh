package com.resqmesh.mesh

import com.resqmesh.mesh.crypto.NodeKeys

class FixedClock(var now: Long) : Clock {
    override fun nowMillis(): Long = now
}

object Fixtures {
    const val NOW = 1_793_000_000_000L
    const val HOUR = 3_600_000L

    /** Deterministic keys so failures are reproducible. Test-only; never ship these. */
    fun keys(seed: Int): NodeKeys = NodeKeys.fromPrivateKey(ByteArray(32) { (seed + it).toByte() })
}
