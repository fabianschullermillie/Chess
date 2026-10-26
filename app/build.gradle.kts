plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "dev.inkcalidraw"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.inkcalidraw"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Default sync server, baked in by CI (repository variable SYNC_URL); can be changed in the app.
        buildConfigField("String", "SYNC_URL", "\"${System.getenv("SYNC_URL") ?: ""}\"")
        versionName = "0.2-prototype." + (System.getenv("GITHUB_RUN_NUMBER") ?: "local")
    }
    // Prototype-only key committed to the repo so every CI build installs over the previous one.
    // Replace with a secret-backed key before publishing anywhere.
    signingConfigs {
        create("prototype") {
            storeFile = rootProject.file("prototype.keystore")
            storePassword = "inkcalidraw"; keyAlias = "prototype"; keyPassword = "inkcalidraw"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("prototype") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("prototype") }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.graphics:graphics-core:1.0.4")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
tasks.withType<Test>().configureEach {
    systemProperty("fixtureDir", rootProject.file("fixtures").absolutePath)
    systemProperty("syncUrl", System.getenv("SYNC_TEST_URL") ?: "http://127.0.0.1:8787")
}
