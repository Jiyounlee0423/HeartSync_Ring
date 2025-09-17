import org.gradle.kotlin.dsl.coreLibraryDesugaring
import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.heartsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.heartsync"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation ("androidx.core:core-splashscreen:1.0.1")

    // Firebase BoM (버전은 BoM이 관리)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))

    // Firestore KTX
    implementation("com.google.firebase:firebase-firestore-ktx")

    // (선택) Auth 쓰면 uid 얻기용
    implementation("com.google.firebase:firebase-auth-ktx")

    // 코루틴이 없다면 추가
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.navigation:navigation-compose:2.8.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // (이미 있을 수도 있음) 코루틴 기본
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ★ 중요: Firebase Task를 코루틴 await()로 바꿔주는 확장 모듈
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // (권장) lifecycleScope 쓰면 필요
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // (이미 있을 가능성 큼) Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.3.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

}
