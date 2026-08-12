import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.wemade.teslable"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 프로토콜 검증에 실제 protobuf 인코딩이 필요하므로 단위 테스트에서도 안드로이드 리소스를 흉내 낸다
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Tesla 공식 .proto를 그대로 쓴다. 손으로 옮기면 필드 번호 하나 틀려도 조용히 실패한다
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // 안드로이드는 리플렉션 없는 lite 런타임을 쓴다
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    // 앱 쪽에서 생성된 protobuf 타입을 직접 조립하므로 런타임을 노출한다
    api(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
