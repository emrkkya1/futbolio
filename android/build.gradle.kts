plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "app.futbolio.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.futbolio.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "app.futbolio.android.SmokeInstrumentation"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        val keyPath = System.getenv("FUTBOLIO_KEYSTORE")
        if (!keyPath.isNullOrBlank()) create("production") {
            storeFile = file(keyPath)
            storePassword = System.getenv("FUTBOLIO_STORE_PASSWORD")
            keyAlias = System.getenv("FUTBOLIO_KEY_ALIAS")
            keyPassword = System.getenv("FUTBOLIO_KEY_PASSWORD")
        }
    }
    buildTypes { release {
        isMinifyEnabled = true; isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.findByName("production") ?: if (providers.gradleProperty("developmentSigning").orNull == "true") signingConfigs.getByName("debug") else null
    } }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":engine"))
    implementation(libs.coroutines.android)
    implementation(libs.activity.compose)
    implementation(platform(libs.android.compose.bom))
    implementation(libs.android.compose.ui)
    implementation(libs.android.compose.material)
    implementation(libs.android.compose.tooling)
    implementation(libs.tv.material)
    implementation(libs.datastore)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    androidTestImplementation(testFixtures(project(":engine")))
}
