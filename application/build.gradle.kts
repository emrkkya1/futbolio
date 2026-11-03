plugins { alias(libs.plugins.kotlin.jvm); `java-library` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(project(":domain"))
    api(libs.coroutines)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
