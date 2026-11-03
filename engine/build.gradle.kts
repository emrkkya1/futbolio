plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); `java-library`; `java-test-fixtures` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(project(":application"))
    api(libs.coroutines)
    api(libs.serialization)
    implementation(libs.ktor.server)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(kotlin("test-junit"))
    testImplementation(testFixtures(project(":engine")))
    testImplementation(libs.kotlin.compiler)
    testImplementation(libs.coroutines.test)
}
val liveTest by sourceSets.creating
configurations[liveTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[liveTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies { add(liveTest.implementationConfigurationName, sourceSets.test.get().output) }
kotlin.target.compilations.getByName("liveTest").associateWith(kotlin.target.compilations.getByName("test"))
kotlin.target.compilations.getByName("testFixtures").associateWith(kotlin.target.compilations.getByName("main"))

tasks.test {
    systemProperty("futbolio.projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("futbolio.offlineTests", "true")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
tasks.register<Test>("liveTest") {
    description = "Explicit internet-backed provider verification; never part of test/check."
    group = "verification"
    testClassesDirs = liveTest.output.classesDirs
    classpath = liveTest.runtimeClasspath
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
