
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = "1.0.0-desktop"

tasks.withType<ProcessResources> {
    filteringCharset = "UTF-8"
}

tasks.named<org.gradle.jvm.tasks.Jar>("reobfJar") {
    archiveClassifier.set("desktop")
    doLast {
        val sourceJar = archiveFile.get().asFile
        val targetJar = sourceJar.parentFile.resolve("ScreenCaptureControlMod-v1.0.0-desktop.jar")
        sourceJar.copyTo(targetJar, overwrite = true)
    }
}
