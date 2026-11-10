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
                // I3：之前只收缩 androidx/kotlin/kotlinx，app 自己的代码根本没有进入 R8，
                // mapping.txt 里没有任何 org.krvyft 条目，“release 缩水/保留规则生效”的
                // 证据是空洞的。这里显式覆盖全部包（等价于默认值），让 app 代码真的被
                // 收缩/改名；必须保留的入口与隐藏回调由 keepRules/rules.keep 证明。
                packageScope = setOf("**")
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

    // APP 侧控制协议负载（audio.channel/handover/脱敏配置）与 :core 使用同一 Gson 版本；
    // :core 把 Gson 声明为 implementation，不会传递到 app 的编译类路径，因此这里显式声明。
    implementation(libs.gson)

    // 远程语音回合：OkHttp 负责 HTTPS/超时/响应上限；MockWebServer 只在 JVM 单测中使用。
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    // cancelledTurnNeverWritesAudio 需要虚拟调度器断言取消后的上行写入次数。
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
