import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Chaves de API vêm de HondaDeck/local.properties, que NÃO vai para o Git (.gitignore).
// O repositório é público: uma chave escrita no código fica exposta para qualquer pessoa.
//   GEMINI_API_KEY=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localKey(name: String): String =
    (localProps.getProperty(name) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.autodeck.headunit"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.autodeck.hondadeck"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "3.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Chave padrão do Copilot embutida no APK: o tablet já sai configurado.
        // Uma chave digitada nas Configurações do app tem prioridade.
        buildConfigField("String", "GEMINI_API_KEY", "\"${localKey("GEMINI_API_KEY")}\"")

        manifestPlaceholders["redirectSchemeName"] = "hondadeck"
        manifestPlaceholders["redirectHostName"] = "spotify-callback"
        manifestPlaceholders["redirectPathPattern"] = ".*"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProps.getProperty("spotify.clientId", "")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"${localProps.getProperty("spotify.redirectUri", "hondadeck://spotify-callback")}\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/autodeck-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    // Duas versões do mesmo app (mesmo applicationId e assinatura: uma substitui a
    // outra mantendo os dados). Veja HondaDeck/INSTALACAO.md.
    //  - completo: instalada pelo USB (ADB) em casa. Declara o NotificationListenerService
    //    (src/completo/AndroidManifest.xml) -> WhatsApp, capa e título da música.
    //  - qr: baixada pelo QR code/navegador. Sem o listener, porque com ele o Android
    //    bloqueia a instalação de APKs vindos da internet.
    flavorDimensions += "instalacao"
    productFlavors {
        create("completo") {
            dimension = "instalacao"
            isDefault = true // variante que o Android Studio abre por padrão
            buildConfigField("boolean", "HAS_NOTIFICATION_LISTENER", "true")
        }
        create("qr") {
            dimension = "instalacao"
            buildConfigField("boolean", "HAS_NOTIFICATION_LISTENER", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.spotify.android:auth:2.1.2")

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
