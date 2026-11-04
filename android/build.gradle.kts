plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }

fun androidVersionCode(version: String): Int {
    require(version.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
        "futbolioVersion must be a stable major.minor.patch version without leading zeros"
    }
    val parts = version.split('.').map { it.toLongOrNull() ?: error("Version component exceeds supported range") }
    val (major, minor, patch) = parts
    require(major in 0..2099 && minor in 0..999 && patch in 0..999) {
        "Supported version ranges: major 0..2099, minor/patch 0..999"
    }
    val code = major * 1_000_000 + minor * 1_000 + patch
    require(code in 1..2_100_000_000L) { "Android versionCode must be positive and within Google's 2,100,000,000 limit" }
    return code.toInt()
}

val verifyVersioning by tasks.registering {
    group = "verification"
    description = "Check derived Android version codes, ordering and supported version limits."
    doLast {
        mapOf("0.0.1" to 1, "0.3.1" to 3001, "0.3.2" to 3002, "0.3.999" to 3999,
            "0.4.0" to 4000, "0.999.999" to 999999, "1.0.0" to 1000000,
            "2099.999.999" to 2099999999).forEach { (version, expected) ->
            check(androidVersionCode(version) == expected) { "Unexpected code for $version" }
        }
        listOf("0.0.0", "2100.0.0", "0.1000.0", "0.0.1000", "01.2.3", "1.02.3", "1.2.03",
            "-1.0.0", "1.2", "1.2.3-beta", "1.2.3+build", "999999999999999999999.0.0").forEach { version ->
            check(runCatching { androidVersionCode(version) }.isFailure) { "Unsupported version accepted: $version" }
        }
    }
}
tasks.named("check") { dependsOn(verifyVersioning) }

android {
    namespace = "app.futbolio.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.futbolio.android"
        minSdk = 26
        targetSdk = 35
        versionCode = androidVersionCode(project.version.toString())
        versionName = project.version.toString()
        testInstrumentationRunner = "app.futbolio.android.SmokeInstrumentation"
    }
    buildFeatures { compose = true }
    testOptions.unitTests.all { it.systemProperty("futbolio.offlineTests", "true") }
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
