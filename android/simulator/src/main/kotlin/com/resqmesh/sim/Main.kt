package com.resqmesh.sim

import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.sqrt

/**
 * The simulator experiments (plan doc, evaluation): 10–200 phones, radio range 30 m and 80 m,
 * 20 runs per point, ResQMesh vs plain flooding on the same movement. Writes runs.csv (one row per
 * run), summary.csv and summary.md to experiments/simulator.
 *
 *     ./gradlew :simulator:run                  the full sweep
 *     ./gradlew :simulator:run --args="--quick"   3 runs per point, up to 50 phones
 */
fun main(args: Array<String>) {
    val quick = "--quick" in args
    val nodeCounts = if (quick) listOf(10, 25, 50) else listOf(10, 25, 50, 100, 200)
    val ranges = listOf(30.0, 80.0)
    val runs = if (quick) 3 else 20
    val out = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter('=') ?: "experiments/simulator").apply { mkdirs() }

    val scenarios = nodeCounts.flatMap { n ->
        ranges.flatMap { range ->
            (1..runs).flatMap { run ->
                val seed = n * 1_000_000L + range.toLong() * 1_000 + run
                Scheme.entries.map { Scenario(nodes = n, rangeM = range, scheme = it, seed = seed) }
            }
        }
    }
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val started = System.currentTimeMillis()
    val futures: List<Future<RunResult>> = scenarios.map { s -> pool.submit<RunResult> { Simulation(s).run() } }
    val results = futures.mapIndexed { i, f ->
        f.get().also { if ((i + 1) % 20 == 0 || i + 1 == futures.size) println("${i + 1}/${futures.size} runs (${(System.currentTimeMillis() - started) / 1000} s)") }
    }
    pool.shutdown()

    File(out, "runs.csv").writeText(runsCsv(results))
    val points = summarize(results)
    File(out, "summary.csv").writeText(summaryCsv(points))
    File(out, "summary.md").writeText(summaryMarkdown(points, runs, quick))
    println(summaryMarkdown(points, runs, quick))
}

/** One point of the sweep, averaged over its runs. */
data class Point(
    val nodes: Int,
    val rangeM: Double,
    val scheme: Scheme,
    val runs: Int,
    val deliveryMean: Double,
    val deliveryCi95: Double,
    val latencyMedianS: Double?,
    val latencyP90S: Double?,
    val hopsMean: Double?,
    val reachMean: Double,
    val dataTxPerSos: Double,
    val controlTxPerSos: Double,
    val kbPerSos: Double,
    val degreeMean: Double,
)

fun summarize(results: List<RunResult>): List<Point> =
    results.groupBy { Triple(it.scenario.nodes, it.scenario.rangeM, it.scenario.scheme) }.map { (key, rs) ->
        val ratios = rs.map { it.deliveryRatio }
        val latencies = rs.flatMap { r -> r.sos.mapNotNull { it.latencyMs } }.sorted()
        val hops = rs.flatMap { r -> r.sos.mapNotNull { it.hops } }
        val sosTotal = rs.sumOf { it.sos.size }.toDouble()
        Point(
            nodes = key.first,
            rangeM = key.second,
            scheme = key.third,
            runs = rs.size,
            deliveryMean = ratios.average(),
            deliveryCi95 = ci95(ratios),
            latencyMedianS = percentile(latencies, 0.5)?.div(1000.0),
            latencyP90S = percentile(latencies, 0.9)?.div(1000.0),
            hopsMean = hops.takeIf { it.isNotEmpty() }?.average(),
            reachMean = rs.flatMap { r -> r.sos.map { it.reached.toDouble() / r.scenario.nodes } }.average(),
            dataTxPerSos = rs.sumOf { it.dataTransmissions } / sosTotal,
            controlTxPerSos = rs.sumOf { it.controlTransmissions } / sosTotal,
            kbPerSos = rs.sumOf { it.bytes } / sosTotal / 1024.0,
            degreeMean = rs.map { it.meanDegree }.average(),
        )
    }.sortedWith(compareBy<Point> { it.rangeM }.thenBy { it.nodes }.thenBy { it.scheme })

/** Half-width of a 95% confidence interval for the mean (normal approximation). */
private fun ci95(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    return 1.96 * sqrt(variance / values.size)
}

private fun percentile(sorted: List<Long>, p: Double): Double? =
    if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * p).toInt()].toDouble()

private fun f(value: Double?, digits: Int = 2): String = value?.let { String.format(Locale.US, "%.${digits}f", it) } ?: ""

private fun runsCsv(results: List<RunResult>): String = buildString {
    appendLine("nodes,range_m,scheme,seed,sos,delivered,delivery_ratio,median_latency_s,data_tx,control_tx,bytes,mean_degree")
    for (r in results) {
        val latencies = r.sos.mapNotNull { it.latencyMs }.sorted()
        appendLine(
            listOf(
                r.scenario.nodes, f(r.scenario.rangeM, 0), r.scenario.scheme.name, r.scenario.seed, r.sos.size, r.delivered,
                f(r.deliveryRatio, 3), f(percentile(latencies, 0.5)?.div(1000.0)), r.dataTransmissions, r.controlTransmissions,
                r.bytes, f(r.meanDegree),
            ).joinToString(","),
        )
    }
}

private fun summaryCsv(points: List<Point>): String = buildString {
    appendLine("nodes,range_m,scheme,runs,delivery_mean,delivery_ci95,latency_median_s,latency_p90_s,hops_mean,reach_mean,data_tx_per_sos,control_tx_per_sos,kb_per_sos,degree_mean")
    for (p in points) {
        appendLine(
            listOf(
                p.nodes, f(p.rangeM, 0), p.scheme.name, p.runs, f(p.deliveryMean, 3), f(p.deliveryCi95, 3), f(p.latencyMedianS, 1),
                f(p.latencyP90S, 1), f(p.hopsMean), f(p.reachMean, 3), f(p.dataTxPerSos, 1), f(p.controlTxPerSos, 1), f(p.kbPerSos), f(p.degreeMean),
            ).joinToString(","),
        )
    }
}

private fun summaryMarkdown(points: List<Point>, runs: Int, quick: Boolean): String = buildString {
    appendLine("# Simulator results${if (quick) " (quick run)" else ""}")
    appendLine()
    appendLine("$runs runs per point; 1 km² area, random-waypoint walking (0.5–1.5 m/s, pauses up to 60 s), 30 min per run,")
    appendLine("20 SOS from random phones in minutes 1–10, 5% of phones are gateways. Delivered = a gateway holds the SOS.")
    appendLine("Plain flooding is the same relay code with store-carry-forward off. Generated by `./gradlew :simulator:run`.")
    for (range in points.map { it.rangeM }.distinct()) {
        appendLine()
        appendLine("## Radio range ${f(range, 0)} m")
        appendLine()
        appendLine("| Phones | Links per phone | Scheme | Delivered | Median latency | 90th pct latency | Hops | Phones reached | Packet sends per SOS | Control frames per SOS |")
        appendLine("| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for (p in points.filter { it.rangeM == range }) {
            appendLine(
                "| ${p.nodes} | ${f(p.degreeMean, 1)} | ${p.scheme.label} | ${pct(p.deliveryMean)} ± ${pct(p.deliveryCi95)} | " +
                    "${secs(p.latencyMedianS)} | ${secs(p.latencyP90S)} | ${f(p.hopsMean, 1).ifEmpty { "–" }} | ${pct(p.reachMean)} | " +
                    "${f(p.dataTxPerSos, 1)} | ${f(p.controlTxPerSos, 1)} |",
            )
        }
    }
}

private fun pct(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100)

private fun secs(value: Double?): String = when {
    value == null -> "–"
    value < 60 -> String.format(Locale.US, "%.1f s", value)
    else -> String.format(Locale.US, "%.1f min", value / 60)
}
