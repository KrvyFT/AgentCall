import com.android.build.api.artifact.SingleArtifact
import java.io.BufferedOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
    // AGP 内置 Kotlin：不应用 kotlin-android 插件。
    // :bridge 只作为特权运行容器的 DEX 打包载体，不作为第二个用户应用安装。
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.krvyft.agentcall.bridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.krvyft.agentcall.bridge"
        minSdk = 36
        targetSdk = 37

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        // 与 compileOptions 的 Java 11 保持一致；Daemon JDK 仍由 gradle-daemon-jvm.properties 决定。
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core"))

    // 配置主副本与协议负载都使用 Gson（版本目录已固定 2.11.0）。
    // 运行容器最终 DEX 必须自带该库，因此不能只依赖 :core 的内部实现依赖。
    implementation(libs.gson)

    testImplementation(libs.junit)
    // 仪器测试只用于真机内核凭据验证（本机无设备，未执行）。
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}


/**
 * 把 AGP release APK 里真实的 classes*.dex 收进 app_process 运行时容器。
 *
 * 不假设只有一个 classes.dex：AGP 产出几个就复制几个（classes.dex、classes2.dex……），
 * 漏一个直接失败；Kotlin stdlib、coroutines、Gson 与 :core 由 D8 打进这些 dex，
 * 因此容器自带全部桥接运行库。这里对类描述符做字节级存在性检查（dex 字符串表
 * 中保存的就是 "L...;" 描述符），BridgeMain.main / AgentCallCli.main 的
 * 结构级 dexdump 等价校验由 tools/check_package.py 完成。
 *
 * 输出是确定性的未压缩 JAR；ART 的 PathClassLoader 只从 classpath JAR 根部的
 * classes.dex 加载类，因此多 dex 时只用 classes.dex 能解析，其余 dex 作为证据保留。
 */
abstract class PackageRuntimeTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @get:OutputFile
    abstract val containerJar: RegularFileProperty

    @TaskAction
    fun packageRuntime() {
        val directory = apkDirectory.get().asFile
        val apks = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (apks.size != 1) {
            throw GradleException(
                "expected exactly one release APK in " + directory + ", found " + apks.map { it.name })
        }
        val dexEntries = TreeMap<String, ByteArray>()
        ZipFile(apks.single()).use { archive ->
            for (entry in archive.entries()) {
                if (DEX_ENTRY.matches(entry.name)) {
                    dexEntries[entry.name] = archive.getInputStream(entry).use { it.readBytes() }
                }
            }
        }
        if (dexEntries.isEmpty()) {
            throw GradleException("release APK " + apks.single() + " contains no classes*.dex")
        }
        if (!dexEntries.containsKey("classes.dex")) {
            throw GradleException("release APK has no classes.dex; app_process cannot load the rest")
        }
        val missing = REQUIRED_DESCRIPTORS.filter { descriptor ->
            dexEntries.values.none { bytes -> indexOf(bytes, descriptor.toByteArray(Charsets.UTF_8)) >= 0 }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "runtime container is missing required classes: " + missing.joinToString())
        }

        val target = containerJar.get().asFile
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        ZipOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            for ((name, bytes) in dexEntries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        logger.lifecycle(
            "packageRuntime: " + target + " (" + dexEntries.keys.joinToString() + ", "
                + target.length() + " bytes)")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        val last = haystack.size - needle.size
        for (start in 0..last) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return -1
    }

    private companion object {
        val DEX_ENTRY = Regex("^classes\\d*\\.dex$")
        val REQUIRED_DESCRIPTORS = listOf(
            "Lorg/krvyft/agentcall/bridge/BridgeMain;",
            "Lorg/krvyft/agentcall/bridge/AgentCallCli;",
            "Lorg/krvyft/agentcall/bridge/audio/AudioWorkerMain;",
            "Lkotlin/Unit;",
            "Lcom/google/gson/Gson;",
        )
    }
}

androidComponents {
    onVariants(selector().withName("release")) { variant ->
        val releaseApk = variant.artifacts.get(SingleArtifact.APK)
        tasks.register<PackageRuntimeTask>("packageRuntime") {
            group = "build"
            description = "Collect the release APK dex entries into the app_process runtime container"
            apkDirectory.set(releaseApk)
            containerJar.set(layout.buildDirectory.file("outputs/agentcall/agentcall-bridge.jar"))
        }
    }
}
