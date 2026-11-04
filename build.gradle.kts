buildscript {
    repositories { google(); mavenCentral() }
    // Kotlin 2.4 metadata requires R8 >= 9.1.29; AGP 8.13's bundled R8 predates it.
    dependencies { classpath("com.android.tools:r8:9.1.57") }
}
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.application) apply false
}
allprojects { group = "app.futbolio"; version = providers.gradleProperty("futbolioVersion").get() }
subprojects { dependencyLocking { lockAllConfigurations() } }

val verifyModuleDependencies by tasks.registering {
    group = "verification"
    description = "Enforce the approved inward-only project dependency graph."
    doLast {
        val allowed = mapOf(
            ":domain" to emptySet<String>(),
            ":application" to setOf(":domain"),
            ":engine" to setOf(":application", ":domain"),
            ":desktop" to setOf(":engine", ":application", ":domain"),
            ":android" to setOf(":engine", ":application", ":domain"),
        )
        subprojects.forEach { module ->
            module.configurations.filter { !it.name.contains("test", ignoreCase = true) }.forEach { configuration ->
                configuration.dependencies.withType<org.gradle.api.artifacts.ProjectDependency>().forEach { dependency ->
                    check(dependency.dependencyProject.path in allowed.getValue(module.path)) {
                        "Forbidden project dependency: ${module.path} -> ${dependency.dependencyProject.path}"
                    }
                }
            }
        }
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyModuleDependencies) }
}
