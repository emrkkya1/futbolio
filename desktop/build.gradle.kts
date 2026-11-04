import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); alias(libs.plugins.kotlin.compose); alias(libs.plugins.compose) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
// Host selectors/native binaries differ by OS. Their versions are still selected
// by the pinned Compose plugin and locked skiko-awt dependency, not floating here.
dependencyLocking {
    ignoredDependencies.addAll(listOf("org.jetbrains.compose.desktop:desktop-jvm-*", "org.jetbrains.skiko:skiko-awt-runtime-*"))
}
dependencies {
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(libs.desktop.material)
    testImplementation(kotlin("test-junit"))
    testImplementation(testFixtures(project(":engine")))
    testImplementation(compose.desktop.uiTestJUnit4)
}
tasks.test {
    systemProperty("java.awt.headless", "false")
    systemProperty("futbolio.offlineTests", "true")
    testLogging { events("passed", "failed", "skipped") }
}
tasks.register<Test>("headlessTest") {
    description = "Run offline desktop host/unit tests without a display or Compose UI tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    exclude("**/DesktopUiTest*.class")
    systemProperty("java.awt.headless", "true")
    systemProperty("futbolio.offlineTests", "true")
    testLogging { events("passed", "failed", "skipped") }
}
compose.desktop { application {
    mainClass = "app.futbolio.desktop.MainKt"
    nativeDistributions {
        providers.gradleProperty("futbolio.packageOutput").orNull?.let { outputBaseDir.set(project.file(it)) }
        targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
        packageName = "Futbolio"
        packageVersion = project.version.toString()
        modules("java.net.http", "java.desktop", "jdk.unsupported")
        windows {
            iconFile.set(project.file("icons/futbolio.ico"))
            menuGroup = "Futbolio"; shortcut = true; upgradeUuid = "c9b9d214-09cf-4d03-809d-530bed876aaa"
        }
    }
} }
