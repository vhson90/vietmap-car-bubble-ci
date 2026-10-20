plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.velohud.auto"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.velohud.auto"
        minSdk = 23 // Tương thích từ Android 6.0 đến Android 15 (Mọi dòng Android Car Box & Emulator)
        targetSdk = 34
        versionCode = 5
        versionName = "3.1.0"
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
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Không nén file velohud_offline.db và mp3 để copy/phát trực tiếp qua AssetFileDescriptor siêu tốc
    androidResources {
        noCompress += listOf("db", "sqlite", "mp3", "bin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Thư viện phục vụ kiểm thử tự động JUnit & SQLite trên Cloud CI
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
    testImplementation("org.json:json:20240303")
}
