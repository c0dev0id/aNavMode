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
        buildConfigField("long",   "BUILD_TIME", "${System.currentTimeMillis()}L")
        val gitHash = try {
            val p = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short=7", "HEAD"))
            p.inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
        } catch (e: Exception) { "unknown" }
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "MIRROR_USER", "\"${project.findProperty("MAPMIRROR_USER") ?: ""}\"")
        buildConfigField("String", "MIRROR_KEY",  "\"${project.findProperty("MAPMIRROR_KEY")  ?: ""}\"")
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
    implementation("com.github.mapsforge.mapsforge:mapsforge-map-android:0.27.0")
    implementation("com.github.mapsforge.mapsforge:mapsforge-themes:0.27.0")
    implementation("com.github.mapsforge.mapsforge:mapsforge-poi-android:0.27.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
