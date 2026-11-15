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
        versionCode = 2
        versionName = "0.2.0"

        // 공공데이터포털 한국관광공사_국문 관광정보 서비스_GW 인증키 (Decoding 키)
        buildConfigField("String", "TOUR_API_KEY", "\"${localProps.getProperty("TOUR_API_KEY", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // NaviHelper·TextToSpeech SDK가 AIDL로 Pleos 서비스와 통신하므로 코드 축소(R8)는 켜지 않는다.
            isMinifyEnabled = false
            // 아직 배포용 서명 키가 없어 debug 키로 서명한다. 기존 설치본 위에 그대로 덮어 설치된다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // 빌드는 JDK 21로 하고, 산출물 바이트코드는 compileOptions와 같은 17로 맞춘다.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // 음성 안내·읽어주기. Pleos 에뮬레이터·차량의 TTS 엔진은 안드로이드 기본 TextToSpeech로는 찾을 수 없어 이 SDK를 쓴다.
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 하단 플로팅 툴바(Material 3 Expressive FloatingToolbarLayout). 앱 테마는 그대로 두고 툴바에만 Material3 테마를 씌운다.
    implementation("com.google.android.material:material:1.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar의 org.json은 단위 테스트에서 동작하지 않는다.
}
