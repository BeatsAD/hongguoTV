import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
val updateChannel = Properties().apply { rootProject.file("distribution/channel.properties").inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)
require(updateChannel.getProperty("applicationId") == "com.hongguotv.nativeapp.playbackfix")
require(updateChannel.getProperty("repository").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")))

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 50
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.10.4"
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
        getByName("debug") { applicationIdSuffix = ".playbackfix"; versionNameSuffix = "-playbackfix" }
        getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("distribution") {
            initWith(getByName("release"))
            applicationIdSuffix = ".playbackfix"
            versionNameSuffix = "-playbackfix"
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
