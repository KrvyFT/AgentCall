plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.krvyft.agentcall"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.krvyft.agentcall"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
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

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Xposed framework 由用户安装；compileOnly 保证 API 只参与编译，不进入 APK。
    compileOnly(libs.xposed.api)

    // 远程语音回合：OkHttp 负责 HTTPS/超时/响应上限；MockWebServer 只在 JVM 单测中使用。
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    // cancelledTurnNeverWritesAudio 需要虚拟调度器断言取消后的上行写入次数。
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
