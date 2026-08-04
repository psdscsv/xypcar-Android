import org.gradle.kotlin.dsl.invoke
import java.util.Properties
import java.io.FileInputStream

// 1. 读取 local.properties 文件
val localProperties = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) {
    localProperties.load(FileInputStream(localFile))
}
// 2. 获取 Key，如果没取到则给一个空字符串（防止编译报错）
val amapKey = localProperties.getProperty("AMAP_KEY") ?: ""

android {
    defaultConfig {
        // 3. 定义占位符，名为 AMAP_KEY
        manifestPlaceholders["AMAP_KEY"] = amapKey
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.psd.xypcar"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.psd.xypcar"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // 签名配置
    signingConfigs {
        create("release") {
            storeFile = file("C:\\Users\\tanru\\projects\\android\\mykey\\test3")   // 例如: "D:/keys/my-release-key.jks"
            storePassword = "123456"
            keyAlias = "key0"
            keyPassword = "123456"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            // 关联签名配置
            signingConfig = signingConfigs.getByName("release")
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(files("libs/AMap3DMap_11.1.200_AMapSearch_9.7.4_AMapLocation_11.1.200_20260424.aar"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}