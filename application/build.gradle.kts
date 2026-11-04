plugins { alias(libs.plugins.kotlin.jvm); `java-library` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

val generateProductVersion by tasks.registering {
    val productVersion = project.version.toString()
    val generated = layout.buildDirectory.dir("generated/sources/productVersion/kotlin")
    inputs.property("productVersion", productVersion)
    outputs.dir(generated)
    doLast {
        val file = generated.get().file("app/futbolio/application/api/Product.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package app.futbolio.application.api\n\nobject Product { const val VERSION = \"$productVersion\" }\n")
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateProductVersion) }

dependencies {
    api(project(":domain"))
    api(libs.coroutines)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
