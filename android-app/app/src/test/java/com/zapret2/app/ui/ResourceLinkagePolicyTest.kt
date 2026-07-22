package com.zapret2.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ResourceLinkagePolicyTest {

    @Test
    fun productionReferencesResolveAgainstThePostLegacyResourceTree() {
        val main = repositoryDirectory("android-app/app/src/main")
        val resources = main.resolve("res")
        val definitions = resourceDefinitions(resources)
        val references = buildList {
            main.resolve("java").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { source ->
                    KOTLIN_REFERENCE.findAll(source.readText()).forEach { match ->
                        add(ResourceReference(match.groupValues[1], match.groupValues[2], source))
                    }
                }
            (resources.walkTopDown().filter { it.isFile && it.extension == "xml" } +
                sequenceOf(main.resolve("AndroidManifest.xml")))
                .forEach { source ->
                    XML_REFERENCE.findAll(source.readText()).forEach { match ->
                        add(ResourceReference(match.groupValues[1], match.groupValues[2], source))
                    }
                }
        }
        val missing = references
            .filterNot { ResourceName(it.type, it.name) in definitions }
            .map { "${it.type}/${it.name} <- ${it.source.relativeTo(main)}" }
            .distinct()
            .sorted()

        assertTrue("Production references missing resources: $missing", missing.isEmpty())
        assertEquals(521, references.filter { it.source.extension == "kt" }.distinctBy {
            ResourceName(it.type, it.name)
        }.size)
        assertEquals(22, references.count { it.source.extension == "xml" })
    }

    private fun resourceDefinitions(resources: File): Set<ResourceName> = buildSet {
        resources.listFiles().orEmpty()
            .filter(File::isDirectory)
            .forEach { directory ->
                val type = directory.name.substringBefore('-')
                if (type == "values") {
                    directory.listFiles().orEmpty()
                        .filter { it.isFile && it.extension == "xml" }
                        .forEach { valuesFile -> addValuesDefinitions(valuesFile) }
                } else {
                    directory.listFiles().orEmpty()
                        .filter(File::isFile)
                        .forEach { resource -> add(ResourceName(type, resource.nameWithoutExtension)) }
                }
            }
    }

    private fun MutableSet<ResourceName>.addValuesDefinitions(file: File) {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file)
            .documentElement
        repeat(root.childNodes.length) { index ->
            val element = root.childNodes.item(index) as? Element ?: return@repeat
            val name = element.getAttribute("name").takeIf(String::isNotBlank) ?: return@repeat
            val type = if (element.tagName == "item") {
                element.getAttribute("type").takeIf(String::isNotBlank) ?: return@repeat
            } else {
                element.tagName
            }
            add(ResourceName(type, name))
        }
    }

    private fun repositoryDirectory(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isDirectory) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository path: $relativePath")
    }

    private data class ResourceName(val type: String, val name: String)

    private data class ResourceReference(
        val type: String,
        val name: String,
        val source: File,
    )

    private companion object {
        val KOTLIN_REFERENCE = Regex(
            """\bR\.([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)""",
        )
        val XML_REFERENCE = Regex(
            """(?<!@android:)@(string|plurals|color|style|drawable|mipmap|xml|font|anim|animator|menu|navigation)/([A-Za-z_][A-Za-z0-9_.]*)""",
        )
    }
}
