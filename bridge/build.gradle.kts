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
