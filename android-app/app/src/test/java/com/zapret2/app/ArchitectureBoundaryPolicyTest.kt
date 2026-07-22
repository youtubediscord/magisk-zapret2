package com.zapret2.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ArchitectureBoundaryPolicyTest {

    @Test
    fun presentationLayerNeverBypassesTypedPrivilegedRepositories() {
        val files = listOf("viewmodel", "ui").flatMap { directory ->
            productionFile(directory).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(files.isNotEmpty())
        val forbidden = listOf(
            "com.topjohnwu.superuser.Shell",
            "Shell.cmd(",
            "RootFileIo.",
            "ServiceLifecycleController.executeRoot(",
            "ProcessBuilder(",
        )
        files.forEach { file ->
            val source = file.readText()
            forbidden.forEach { token ->
                assertFalse("${file.name} bypasses the privileged boundary with $token", token in source)
            }
        }
    }

    @Test
    fun russianAndDefaultStringCatalogsHaveTheSameKeys() {
        val defaultKeys = resourceKeys(resourceFile("values/strings.xml"))
        val russianKeys = resourceKeys(resourceFile("values-ru/strings.xml"))

        assertEquals(defaultKeys, russianKeys)
    }

    @Test
    fun everyLazyCollectionDeclaresStableKeyAndContentType() {
        val calls = productionFile("ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("\\b(?:items|itemsIndexed)\\s*\\(").findAll(file.readText()).map { match ->
                    val source = file.readText()
                    val end = source.indexOf(") {", match.range.first)
                    check(end > match.range.first) { "Unterminated lazy collection in ${file.name}" }
                    file.name to source.substring(match.range.first, end)
                }
            }.toList()
        assertTrue(calls.isNotEmpty())
        calls.forEach { (fileName, header) ->
            assertTrue("$fileName lazy collection has no stable key", "key =" in header)
            assertTrue("$fileName lazy collection has no content type", "contentType =" in header)
        }
    }

    private fun resourceKeys(file: File): Set<Pair<String, String>> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return buildSet {
            repeat(root.childNodes.length) { index ->
                val element = root.childNodes.item(index) as? Element ?: return@repeat
                val name = element.getAttribute("name").takeIf(String::isNotBlank) ?: return@repeat
                if (element.getAttribute("translatable") == "false") return@repeat
                add(element.tagName to name)
            }
        }
    }

    private fun productionFile(relativePath: String): File = repositoryPath(
        "android-app/app/src/main/java/com/zapret2/app/$relativePath",
    )

    private fun resourceFile(relativePath: String): File = repositoryPath(
        "android-app/app/src/main/res/$relativePath",
    )

    private fun repositoryPath(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository path: $relativePath")
    }
}
