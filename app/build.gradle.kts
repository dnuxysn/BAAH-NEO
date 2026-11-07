import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val neoSigning = rootProject.file("neo-signing.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}
android {
    namespace = "com.baah.nativehost"
    compileSdk = 36
    compileSdkExtension = 19
    defaultConfig {
        applicationId = "com.baah.neo"
        minSdk = 28
        targetSdk = 36
        versionCode = 441
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    flavorDimensions += "edition"
    productFlavors {
        create("github") {
            dimension = "edition"
            buildConfigField("boolean", "PRIVATE_EDITION", "false")
            buildConfigField("int", "BRIDGE_PORT", "23320")
            buildConfigField("int", "WRAPPER_PORT", "23420")
            buildConfigField("int", "CONSOLE_PORT", "8020")
        }
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
    }
    androidResources { noCompress += "xz" }
    if (neoSigning != null) {
        val neoKey = signingConfigs.create("neoDevelopment") {
            storeFile = rootProject.file(neoSigning.getProperty("storeFile"))
            storePassword = neoSigning.getProperty("storePassword")
            keyAlias = neoSigning.getProperty("keyAlias")
            keyPassword = neoSigning.getProperty("keyPassword")
        }
        buildTypes.configureEach { signingConfig = neoKey }
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")
    implementation("org.eclipse.angus:angus-activation:2.0.3")
    implementation("jakarta.activation:jakarta.activation-api:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    testImplementation("junit:junit:4.13.2")
}
