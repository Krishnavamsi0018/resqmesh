package com.resqmesh.mesh.vectors

import java.io.File

/** Entry point for `./gradlew :mesh-core:writeTestVectors`. */
fun main(args: Array<String>) {
    val target = File(args.single())
    target.parentFile.mkdirs()
    target.writeText(TestVectors.render())
    println("Wrote ${TestVectors.build().vectors.size} vectors to $target")
}
