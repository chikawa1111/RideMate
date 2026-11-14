import java.util.Properties

// 키는 local.properties에만 둔다(Git 제외). 없으면 공개 테스트 값을 쓴다.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ridemate.passengercare"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ridemate.passengercare"
        // 공모전 개발 가이드: minSdk 28 이상
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "VEHICLE_SDK_VERSION",
            "\"${libs.versions.vehicleSdk.get()}\""
        )

        buildConfigField(
            "String",
            "EX_API_KEY",
            "\"${localProps.getProperty("EX_API_KEY", "test")}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 심사용 release APK: 별도 키 없이 설치되도록 디버그 키로 서명한다(마켓 배포 시 정식 키로 교체)
            signingConfig = signingConfigs.getByName("debug")
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
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.vehicle.sdk)
    implementation(libs.navi.helper)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
