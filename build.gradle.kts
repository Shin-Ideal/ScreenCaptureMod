
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.named<org.gradle.jvm.tasks.Jar>("reobfJar") {
    archiveClassifier.set("smooth")
    doLast {
        val sourceJar = archiveFile.get().asFile
        val targetJar = sourceJar.parentFile.resolve("screencapture-v1.0.0-smooth.jar")
        sourceJar.copyTo(targetJar, overwrite = true)
    }
}
