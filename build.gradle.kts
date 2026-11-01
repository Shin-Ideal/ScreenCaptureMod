
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.named<org.gradle.jvm.tasks.Jar>("reobfJar") {
    archiveClassifier.set("control")
    doLast {
        val sourceJar = archiveFile.get().asFile
        val targetJar = sourceJar.parentFile.resolve("screencapture-v1.0.0-control.jar")
        sourceJar.copyTo(targetJar, overwrite = true)
    }
}
