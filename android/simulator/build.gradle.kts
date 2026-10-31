import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// N virtual phones running mesh-core's real relay code (TRD 2.12, week 9 experiments).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.resqmesh.sim.MainKt")
}

dependencies {
    implementation(project(":mesh-core"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Results go to experiments/simulator at the repo root: ./gradlew :simulator:run --args="--quick"
tasks.named<JavaExec>("run") {
    workingDir = rootDir.resolve("..").canonicalFile
    maxHeapSize = "2g"
}
