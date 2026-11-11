import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
val updateChannel = Properties().apply { rootProject.file("distribution/channel.properties").inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)
require(updateChannel.getProperty("applicationId") == "com.hongguotv.nativeapp.playbackfix")
require(updateChannel.getProperty("repository").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")))
if (gradle.startParameter.taskNames.any { it.contains("distribution", ignoreCase = true) }) {
    require((System.getenv("HONGGUOTV_VERSION_CODE")?.toIntOrNull() ?: 0) > updateChannel.getProperty("versionCodeFloor").toInt()) {
        "Allocate a build number first with scripts/tv-update.py version or scripts/build-kotlin.sh"
    }
    require(System.getenv("HONGGUOTV_VERSION_NAME") == updateChannel.getProperty("versionName")) {
        "Distribution version must match the channel version"
    }
}

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: (updateChannel.getProperty("versionCodeFloor").toInt() + 1)
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: updateChannel.getProperty("versionName")
        buildConfigField("boolean", "ONLINE_UPDATES", "false")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"${updateChannel.getProperty("repository")}\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".playbackfix"; versionNameSuffix = "-debug" }
        getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("distribution") {
            initWith(getByName("release"))
            applicationIdSuffix = ".playbackfix"
            buildConfigField("boolean", "ONLINE_UPDATES", "true")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
