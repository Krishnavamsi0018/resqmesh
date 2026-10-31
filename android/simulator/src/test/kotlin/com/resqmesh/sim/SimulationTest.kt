package com.resqmesh.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationTest {
    private fun scenario(scheme: Scheme, nodes: Int) =
        Scenario(nodes = nodes, rangeM = 30.0, scheme = scheme, seed = 1, durationS = 300, sosCount = 1)

    /** Phones that stay where they are. */
    private fun still(vararg at: Pair<Double, Double>) = object : Mobility {
        override fun positions(timeS: Int) = at.toList()
    }

    @Test
    fun `an SOS crosses a still chain hop by hop to the gateway`() {
        val chain = still(0.0 to 0.0, 25.0 to 0.0, 50.0 to 0.0, 75.0 to 0.0)
        val result = Simulation(scenario(Scheme.RESQMESH, 4), chain, gateways = setOf(3), senders = listOf(10 to 0)).run()

        val sos = result.sos.single()
        assertEquals(3, sos.hops)
        assertTrue(sos.latencyMs!! < 1_000, "three hops of about 0.2 s: ${sos.latencyMs}")
        assertEquals(4, sos.reached)
        assertEquals(3.0 / 2, result.meanDegree, "ends have one link, middles two")
    }

    @Test
    fun `a walking phone carries the SOS to a gateway out of reach, which plain flooding cannot`() {
        // Phone 1 waits next to the sender, then from minute 1 walks 1 m/s towards the gateway 500 m away.
        val walk = object : Mobility {
            override fun positions(timeS: Int) = listOf(0.0 to 0.0, (10.0 + maxOf(0, timeS - 60)).coerceAtMost(480.0) to 0.0, 500.0 to 0.0)
        }
        fun run(scheme: Scheme) = Simulation(scenario(scheme, 3).copy(durationS = 700), walk, gateways = setOf(2), senders = listOf(30 to 0)).run().sos.single()

        val ours = run(Scheme.RESQMESH)
        assertNotNull(ours.latencyMs, "carried")
        assertEquals(2, ours.hops)
        assertTrue(ours.latencyMs!! > 400_000, "it takes the walk: ${ours.latencyMs}")

        // Flooding sends it to phone 1 only while it is near; the gateway never gets it.
        assertNull(run(Scheme.FLOODING).latencyMs)
    }

    @Test
    fun `the same seed gives the same run, and both schemes see the same movement`() {
        val s = Scenario(nodes = 30, rangeM = 80.0, scheme = Scheme.RESQMESH, seed = 42, durationS = 600)
        val a = Simulation(s).run()
        val b = Simulation(s).run()
        assertEquals(a.sos, b.sos)
        assertEquals(a.dataTransmissions, b.dataTransmissions)

        val flooding = Simulation(s.copy(scheme = Scheme.FLOODING)).run()
        assertEquals(a.meanDegree, flooding.meanDegree)
        assertEquals(a.sos.map { it.sentAtS }, flooding.sos.map { it.sentAtS })
        assertTrue(a.delivered >= flooding.delivered)
    }
}
