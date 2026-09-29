import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop command line: turns a recorded walk-through (capture zip / folder) into the
// point cloud, colour 3D model and PC-tool exports, using the same core as the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}

// One runnable jar with everything in it: java -jar reconstruct.jar capture.zip out/
tasks.jar {
    archiveFileName.set("reconstruct.jar")
    manifest { attributes["Main-Class"] = "com.banyawa.sitescanner.cli.ReconstructKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
