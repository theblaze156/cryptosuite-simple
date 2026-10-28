import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val ks = Properties().apply { val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
    namespace = "com.cryptosuite.simple"
    compileSdk = 34
    defaultConfig { applicationId = "com.cryptosuite.simple"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    signingConfigs { create("rel") {
        storeFile = rootProject.file(ks.getProperty("storeFile", "release.jks")); storePassword = ks.getProperty("storePassword")
        keyAlias = ks.getProperty("keyAlias"); keyPassword = ks.getProperty("keyPassword") } }
    buildTypes { getByName("release") {
        isMinifyEnabled = true; isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("rel") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
