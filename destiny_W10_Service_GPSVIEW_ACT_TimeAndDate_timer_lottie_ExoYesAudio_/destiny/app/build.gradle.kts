plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.destiny"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.destiny"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core) // 建議加入這個，用於 UI 測試

    // 定位服務，osmdroid 也會用到
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // 網路請求 (第一頁天氣功能需要)
    implementation("com.android.volley:volley:1.2.1")

    // 本地廣播 (Service 與 Activity 通訊需要)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // 免費地圖函式庫
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Lottie 動畫函式庫
    implementation("com.airbnb.android:lottie:6.4.1")

    // ExoPlayer 核心函式庫
    implementation("androidx.media3:media3-exoplayer:1.3.1")

}
