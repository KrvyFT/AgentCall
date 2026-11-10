plugins {
    // AGP 内置 Kotlin：不应用 kotlin-android 插件。
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.krvyft.agentcall.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 36
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
    // Flow/PcmChunk 下行流契约所需。
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
