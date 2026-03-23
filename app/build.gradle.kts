plugins {
    id("com.android.application")
}

android {
    namespace = "de.codevoid.aNavMode"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.codevoid.aNavMode"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("org.mapsforge:mapsforge-map-android:0.21.0")
    implementation("org.mapsforge:mapsforge-themes:0.21.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
