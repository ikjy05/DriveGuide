import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "zone.dal.driveguide"
    // 주행 중 화면 제한(CarUxRestrictionsManager)
    useLibrary("android.car")
    compileSdk = 35

    defaultConfig {
        applicationId = "zone.dal.driveguide"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 공공데이터포털 한국관광공사_국문 관광정보 서비스_GW 인증키 (Decoding 키)
        buildConfigField("String", "TOUR_API_KEY", "\"${localProps.getProperty("TOUR_API_KEY", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar의 org.json은 단위 테스트에서 동작하지 않는다.
}
