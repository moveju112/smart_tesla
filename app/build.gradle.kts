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
        applicationId = "com.wemade.teslamacro"
        minSdk = 26
        targetSdk = 35
        versionCode = 131
        versionName = "0.9.18"

        // 공개 버전에서는 외부 네이버 지도만 사용하므로 KNSDK 키를 포함하지 않는다.
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"\"")
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

dependencies {
    implementation(project(":tesla-ble"))

    // 카카오내비 SDK — 과속·구간단속·보호구역 안내의 유일한 출처.
    // 공개 저장소라 자격증명은 없고, 앱 키만 local.properties에서 온다
    implementation("com.kakaomobility.knsdk:knsdk:1.12.8-hotfix03")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

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
