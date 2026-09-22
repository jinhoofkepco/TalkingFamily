import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// The private key is kept outside the checkout. CI supplies the same identity through Secrets.
val familySigning = Properties().apply {
    file("${System.getProperty("user.home")}/.config/talkingfamily/signing.properties")
        .takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(environment: String, property: String): String? =
    System.getenv(environment)?.takeIf { it.isNotBlank() }
        ?: familySigning.getProperty(property)?.takeIf { it.isNotBlank() }
val familyStore = signingValue("TALKINGFAMILY_KEYSTORE_FILE", "storeFile")
val familyStorePassword = signingValue("TALKINGFAMILY_STORE_PASSWORD", "storePassword")
val familyKeyAlias = signingValue("TALKINGFAMILY_KEY_ALIAS", "keyAlias")
val familyKeyPassword = signingValue("TALKINGFAMILY_KEY_PASSWORD", "keyPassword")
val familySigningComplete = listOf(familyStore, familyStorePassword, familyKeyAlias, familyKeyPassword).all { it != null }
val verifyFamilySigning = tasks.register("verifyFamilySigning") {
    group = "verification"
    description = "Refuse release signing unless the preserved family app certificate is present."
    doLast {
        check(familySigningComplete) { "Missing TalkingFamily signing configuration. See docs/SIGNING.md; never generate a replacement key for updates." }
        val store = KeyStore.getInstance("PKCS12")
        try {
            file(familyStore!!).inputStream().use { store.load(it, familyStorePassword!!.toCharArray()) }
            check(store.isKeyEntry(familyKeyAlias)) { "Signing alias is not a private key." }
            check(store.getKey(familyKeyAlias, familyKeyPassword!!.toCharArray()) != null) { "Private signing key is missing." }
        } catch (_: Exception) {
            error("Cannot unlock the TalkingFamily signing key. Check the private configuration; credentials are not logged.")
        }
        val certificate = checkNotNull(store.getCertificate(familyKeyAlias)) { "Signing certificate is missing." }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        val expected = rootProject.file("signing-certificate.sha256").readText().trim().lowercase()
        check(fingerprint == expected) { "Signing identity changed. Refusing to create an APK that cannot update the installed family app." }
    }
}
android {
    namespace = "kr.family.homeway"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.family.homeway"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.6.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (familySigningComplete) create("family") {
            storeFile = file(familyStore!!)
            storeType = "PKCS12"
            storePassword = familyStorePassword
            keyAlias = familyKeyAlias
            keyPassword = familyKeyPassword
        }
    }
    buildTypes {
        debug {
            if (familySigningComplete) signingConfig = signingConfigs.getByName("family")
        }
        release {
            if (familySigningComplete) signingConfig = signingConfigs.getByName("family")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
tasks.matching { it.name == "preReleaseBuild" || (familySigningComplete && it.name == "preDebugBuild") }
    .configureEach { dependsOn(verifyFamilySigning) }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
