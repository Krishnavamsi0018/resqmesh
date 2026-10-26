import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Bytecode stays at Java 17 so the Android app (week 2) can depend on this module.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.prov)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val testVectorsFile = rootDir.resolve("../shared/test-vectors/packets-v1.json").canonicalFile

tasks.test {
    useJUnitPlatform()
    systemProperty("resqmesh.vectorsFile", testVectorsFile.absolutePath)
}

tasks.register<JavaExec>("writeTestVectors") {
    group = "resqmesh"
    description = "Regenerates shared/test-vectors/packets-v1.json from the current packet format."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.resqmesh.mesh.vectors.WriteTestVectorsKt")
    args(testVectorsFile.absolutePath)
}
