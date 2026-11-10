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
    // 公开契约（AudioSession.downlink: Flow<PcmChunk>）暴露协程类型，必须是 api：
    // app/bridge 需要在自己的编译类路径上看到同一份 coroutines，否则消费方能编译失败或解析到别的版本。
    api(libs.kotlinx.coroutines.core)

    // spec §7：完整号码按可配置地区规范化，禁止自写号段/后缀近似匹配。
    implementation(libs.libphonenumber)

    // 本地控制协议与配置 JSON 的唯一实现（版本目录固定 2.11.0）。
    // Gson 类型不出现在公开签名中，消费方只看到 RpcRequest/RpcResponse/AgentConfig。
    implementation(libs.gson)

    testImplementation(libs.junit)
}