/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.Properties

/**
 * Bundles rime-tw (Liur, Liur Lite, double pinyin and zhuyin) into assets/shared, so a fresh
 * install works without copying a Rime folder to the phone.
 *
 * rime-tw is a private repository, so its files are never committed here. Point the build at a
 * local checkout with the RIME_TW_DIR environment variable, the rimeTwDir Gradle property, or
 * `rimeTw.dir` in local.properties. Which files belong to Android is decided by rime-tw's own
 * tools/lib_platform_files.sh, so the rules live in one place.
 *
 * Files from data/xiashuo are copied last; they are Xiashuo's own additions (e.g. the voice
 * wording table) and are tracked in this repository.
 */
class RimeTwDataPlugin : Plugin<Project> {
    companion object {
        const val INSTALL_TASK = "installRimeTwData"
        const val CLEAN_TASK = "cleanRimeTwData"
    }

    private val Project.rimeTwDir: String?
        get() = envOrPropOrNull("RIME_TW_DIR", "rimeTwDir")
            ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
                Properties().apply { file.reader(Charsets.UTF_8).use(::load) }.getProperty("rimeTw.dir")
            }

    override fun apply(target: Project) {
        val install =
            target.tasks.register<InstallRimeTwDataTask>(INSTALL_TASK) {
                rimeTwDir.set(target.rimeTwDir.orEmpty())
                extraDir.set(target.file("data/xiashuo").absolutePath)
                sharedDir.set(target.assetsDir.resolve("shared").absolutePath)
                manifest.set(target.layout.buildDirectory.file("rime-tw/installed.txt").get().asFile.absolutePath)
                staging.set(target.layout.buildDirectory.dir("rime-tw/rime").get().asFile.absolutePath)
                // rime-tw replaces some upstream OpenCC files (e.g. TWPhrases.txt) on purpose.
                mustRunAfter(OpenCCDataPlugin.INSTALL_TASK)
            }
        target.tasks.getByName(DataChecksumsPlugin.TASK).dependsOn(install)
        target.tasks.register<DefaultTask>(CLEAN_TASK) {
            doLast {
                val manifest = target.layout.buildDirectory.file("rime-tw/installed.txt").get().asFile
                val shared = target.assetsDir.resolve("shared")
                if (manifest.isFile) manifest.readLines().filter { it.isNotBlank() }.forEach { shared.resolve(it).delete() }
                manifest.delete()
            }
        }.also { target.cleanTask.dependsOn(it) }
    }

    abstract class InstallRimeTwDataTask : DefaultTask() {
        @get:Internal abstract val rimeTwDir: Property<String>

        @get:Internal abstract val extraDir: Property<String>

        @get:Internal abstract val sharedDir: Property<String>

        @get:Internal abstract val manifest: Property<String>

        @get:Internal abstract val staging: Property<String>

        init {
            // Writes into src/main/assets/shared next to the OpenCC task; always refresh, it is
            // only a local copy and the checksum task skips files whose content did not change.
            doNotTrackState("copies an external checkout into the shared assets")
        }

        @TaskAction
        fun execute() {
            val source = File(rimeTwDir.get())
            if (rimeTwDir.get().isBlank() || !source.resolve("tools/lib_platform_files.sh").isFile) {
                throw GradleException(
                    "rime-tw checkout not found (got '${rimeTwDir.get()}'). Set RIME_TW_DIR, " +
                        "-PrimeTwDir or rimeTw.dir in local.properties to the rime-tw repository.",
                )
            }
            val stagingDir = File(staging.get())
            stagingDir.deleteRecursively()
            val script =
                "set -euo pipefail; cd \"$1\"; source tools/lib_platform_files.sh; " +
                    "build_platform_tree android \"$2\"; copy_android_octagram_models rime \"$2\""
            val process =
                ProcessBuilder("bash", "-c", script, "bash", source.absolutePath, stagingDir.absolutePath)
                    .inheritIO()
                    .start()
            if (process.waitFor() != 0) throw GradleException("rime-tw build_platform_tree failed")
            File(extraDir.get()).takeIf { it.isDirectory }?.copyRecursively(stagingDir, overwrite = true)

            val shared = File(sharedDir.get())
            val installed = stagingDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(stagingDir).invariantSeparatorsPath }.toSortedSet()
            val manifestFile = File(manifest.get())
            if (manifestFile.isFile) {
                (manifestFile.readLines().filter { it.isNotBlank() }.toSet() - installed).forEach { shared.resolve(it).delete() }
            }
            installed.forEach { rel ->
                val dest = shared.resolve(rel)
                // Drop upstream symlinks first, or the copy would write through into a submodule.
                if (java.nio.file.Files.isSymbolicLink(dest.toPath())) dest.delete()
                stagingDir.resolve(rel).copyTo(dest, overwrite = true)
            }
            manifestFile.parentFile.mkdirs()
            manifestFile.writeText(installed.joinToString("\n", postfix = "\n"))
            logger.lifecycle("rime-tw: bundled ${installed.size} files from ${source.absolutePath}")
        }
    }
}
