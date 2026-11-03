package app.futbolio.engine.architecture

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File
import kotlin.test.*

/** Kotlin PSI, not a text/grep heuristic: comments and string literals are not imports. */
@OptIn(org.jetbrains.kotlin.CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
class DependencyBoundaryTest {
    private fun forbidden(module: String, imported: String): Boolean {
        val forbidden = listOf("android", "androidx", "java.awt", "javax.swing", "io.ktor", "okhttp3", "org.jsoup", "kotlinx.serialization", "app.futbolio.engine", "app.futbolio.desktop") +
            if (module == "domain") listOf("app.futbolio.application", "kotlinx.coroutines", "java.io") else emptyList()
        return forbidden.any { imported == it || imported.startsWith("$it.") }
    }

    @Test fun innerModulesDoNotImportFrameworksOrOutwardModules() {
        val root = File(requireNotNull(System.getProperty("futbolio.projectRoot")))
        val disposable = Disposer.newDisposable()
        try {
            val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
            val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val parser = KtPsiFactory(environment.project, false)
            // An in-memory negative fixture proves the rule recognizes real Kotlin imports.
            // Nothing forbidden is added to the production module or left behind on disk.
            val negative = parser.createFile("Violation.kt", "package app.futbolio.domain.fixture\nimport io.ktor.server.application.Application\n")
            assertTrue(negative.importDirectives.any { forbidden("domain", it.importedFqName!!.asString()) })
            val harmless = parser.createFile("Harmless.kt", "package app.futbolio.domain.fixture\n// import okhttp3.Call\nval text = \"import org.jsoup.Jsoup\"\n")
            assertTrue(harmless.importDirectives.isEmpty())
            for (module in listOf("domain", "application")) {
                val files = File(root, "$module/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
                assertTrue(files.isNotEmpty(), "$module is a real module, not an empty architectural placeholder")
                val parsedFiles = files.map { file -> file to parser.createFile(file.name, file.readText()) }
                val packages = parsedFiles.map { it.second.packageFqName.asString() }.toSet()
                val graph = packages.associateWith { linkedSetOf<String>() }
                for ((file, parsed) in parsedFiles) {
                    assertTrue(parsed.packageFqName.asString().startsWith("app.futbolio.$module."), file.path)
                    for (directive in parsed.importDirectives) {
                        val imported = directive.importedFqName?.asString().orEmpty()
                        assertFalse(forbidden(module, imported), "${file.path}: forbidden import $imported")
                        val target = packages.filter { imported == it || imported.startsWith("$it.") }.maxByOrNull { it.length }
                        if (target != null && target != parsed.packageFqName.asString()) graph.getValue(parsed.packageFqName.asString()) += target
                    }
                }
                fun visit(node: String, path: Set<String>) {
                    assertFalse(node in path, "Package cycle in $module: ${path.joinToString(" -> ")} -> $node")
                    graph.getValue(node).forEach { visit(it, path + node) }
                }
                packages.forEach { visit(it, emptySet()) }
            }
            val providersRoot = File(root, "engine/src/main/kotlin/app/futbolio/engine/adapters/providers")
            val ownPackages = listOf("ppv", "papaz", "taraftarium", "futbolvivo", "stream720")
            for (name in ownPackages) {
                for (file in File(providersRoot, name).walkTopDown().filter { it.extension == "kt" }) {
                    val parsed = parser.createFile(file.name, file.readText())
                    for (directive in parsed.importDirectives) {
                        val imported = directive.importedFqName?.asString().orEmpty()
                        assertFalse(ownPackages.filterNot { it == name }.any { imported.startsWith("app.futbolio.engine.adapters.providers.$it.") },
                            "${file.path}: provider-to-provider import $imported")
                    }
                }
            }
        } finally { Disposer.dispose(disposable) }
    }
}
