import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.wemade.teslamacro"
    compileSdk = 35

    defaultConfig {
        // 로컬 설정만 읽는다. 공개 저장소에는 토큰을 넣지 않고, 없는 빌드는 도로 매칭을 끈다.
        val localSettings = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use(::load)
        }
        val mapToken = localSettings.getProperty("roadMatchToken", "")
        require(mapToken.matches(Regex("[A-Za-z0-9_-]{0,256}"))) { "roadMatchToken 형식을 확인하세요" }
        buildConfigField("String", "ROAD_MATCH_TOKEN", "\"$mapToken\"")
        applicationId = "com.wemade.teslamacro"
        minSdk = 26
        targetSdk = 35
        versionCode = 207
        versionName = "0.9.94"
    }

    // 실기기 배포는 ARM 태블릿만 대상으로 하므로 두 ARM ABI를 따로 뽑는다.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 기기에 깔린 앱이 debug 키로 서명돼 있다. 키가 바뀌면 자가 업데이트가 서명 불일치로
            // 막혀 사용자가 지우고 다시 깔아야 한다 — 같은 키를 그대로 쓴다
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // 업데이트 확인에서 현재 버전(VERSION_NAME) 비교용
    }
}

// CLI 기본 검증은 이미지를 만들지 않고, 명시 요청이 있을 때만 렌더링 테스트를 실행한다.
val allowSnapshots = providers.gradleProperty("allowSnapshots").map { it.toBoolean() }.getOrElse(false)
tasks.withType<Test>().configureEach {
    if (!allowSnapshots) {
        exclude("**/*ScreenshotTest*", "**/WideFontScaleTest*", "**/PortraitTabletTest*")
    }
}

dependencies {
    implementation(project(":tesla-ble"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // 휴대 모드의 의도적 BLE 해제 뒤에도 보행·차량 이동을 구분한다.
    implementation("com.google.android.gms:play-services-location:21.0.1")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
