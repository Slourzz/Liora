plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.liora.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.liora.android"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Jetpack Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // Haze: alternativa de desenfoque para el modo simple y para API 24.
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)

    // PrismalAGSL (material óptico del cristal). El material de Kyant0 queda
    // conservado en la rama backup/kyant0-material-20261019.
    implementation(project(":prismal"))

    // Runtime JavaScript de las extensiones externas. Rhino es Java puro
    // (MPL-2.0): no añade JNI ni binarios nativos, y su límite de
    // instrucciones da un tiempo de ejecución real por llamada.
    implementation(libs.mozilla.rhino)

    testImplementation(libs.junit)
    // org.json real para las pruebas unitarias: en la JVM el android.jar
    // solo trae los stubs que lanzan al usarse.
    testImplementation(libs.json)
}
