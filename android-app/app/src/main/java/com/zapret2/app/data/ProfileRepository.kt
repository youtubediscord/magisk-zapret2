package com.zapret2.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileRepository {
    suspend fun loadActive(): PresetProfileDocument?
    suspend fun loadStrategies(scope: StrategyCatalogScope, availableBlobs: Set<String>): List<StrategyCatalogEntry>?
    suspend fun loadListEntries(selectorLine: String): List<ProfileListEntry>?
    suspend fun setEnabled(document: PresetProfileDocument, profileIndex: Int, enabled: Boolean): PresetMutationOutcome
    suspend fun rename(document: PresetProfileDocument, profileIndex: Int, name: String): PresetMutationOutcome
    suspend fun replaceSelector(
        document: PresetProfileDocument,
        profileIndex: Int,
        selectorIndex: Int,
        relativePath: String,
    ): PresetMutationOutcome
    suspend fun replaceStrategy(document: PresetProfileDocument, profileIndex: Int, strategy: StrategyCatalogEntry): PresetMutationOutcome
    suspend fun move(document: PresetProfileDocument, fromIndex: Int, toIndex: Int): PresetMutationOutcome
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProfileRepositoryModule {
    @Binds
    abstract fun bindProfileRepository(implementation: DefaultProfileRepository): ProfileRepository
}

@Singleton
class DefaultProfileRepository @Inject constructor(
    private val presets: PresetRepository,
    private val hostlists: HostlistRepository,
) : ProfileRepository {
    override suspend fun loadActive(): PresetProfileDocument? = withContext(Dispatchers.IO) {
        val active = presets.loadCatalog()?.selection?.activePresetFile ?: return@withContext null
        val source = presets.readCompatible(active) ?: return@withContext null
        PresetProfileParser.parse(active, source)
    }

    override suspend fun loadStrategies(
        scope: StrategyCatalogScope,
        availableBlobs: Set<String>,
    ): List<StrategyCatalogEntry>? =
        withContext(Dispatchers.IO) {
            val path = "${ServiceLifecycleController.MODULE_DIR}/zapret2/strategy-catalogs/${scope.fileName}"
            RootFileIo.readSecureRegularText(path, MAX_CATALOG_BYTES)
                ?.let(::parseStrategyCatalog)
                ?.filter { availableBlobs.containsAll(it.requiredBlobs) }
        }

    override suspend fun loadListEntries(selectorLine: String): List<ProfileListEntry>? = withContext(Dispatchers.IO) {
        val expectsIpSet = selectorLine.startsWith("--ipset=") || selectorLine.startsWith("--ipset-exclude=")
        if (!expectsIpSet && !selectorLine.startsWith("--hostlist=") &&
            !selectorLine.startsWith("--hostlist-exclude=")
        ) {
            return@withContext null
        }
        hostlists.listFiles().getOrNull()?.asSequence()
            ?.filter { profileListEntryMatchesSelector(it.fileName, selectorLine) }
            ?.map { ProfileListEntry(fileName = it.fileName, relativePath = "lists/${it.fileName}") }
            ?.sortedBy(ProfileListEntry::fileName)
            ?.toList()
    }

    override suspend fun setEnabled(
        document: PresetProfileDocument,
        profileIndex: Int,
        enabled: Boolean,
    ): PresetMutationOutcome = save(document, PresetProfileParser.setEnabled(document, profileIndex, enabled))

    override suspend fun rename(
        document: PresetProfileDocument,
        profileIndex: Int,
        name: String,
    ): PresetMutationOutcome = save(document, PresetProfileParser.rename(document, profileIndex, name))

    override suspend fun replaceSelector(
        document: PresetProfileDocument,
        profileIndex: Int,
        selectorIndex: Int,
        relativePath: String,
    ): PresetMutationOutcome {
        val selector = document.profiles.getOrNull(profileIndex)?.selectors?.getOrNull(selectorIndex)
            ?: return PresetMutationOutcome.Rejected(PresetIssue.MALFORMED_PROTOCOL)
        val fileName = relativePath.removePrefix("lists/")
        if (relativePath != "lists/$fileName" ||
            isIpSetHostlistFileName(fileName) !=
            (selector.startsWith("--ipset=") || selector.startsWith("--ipset-exclude="))
        ) {
            return PresetMutationOutcome.Rejected(PresetIssue.MALFORMED_PROTOCOL)
        }
        return save(document, PresetProfileParser.replaceSelector(document, profileIndex, selectorIndex, relativePath))
    }

    override suspend fun replaceStrategy(
        document: PresetProfileDocument,
        profileIndex: Int,
        strategy: StrategyCatalogEntry,
    ): PresetMutationOutcome {
        if (!document.declaredBlobs.containsAll(strategy.requiredBlobs)) {
            return PresetMutationOutcome.Rejected(PresetIssue.DEPENDENCY_MISSING)
        }
        return save(document, PresetProfileParser.replaceStrategy(document, profileIndex, strategy.arguments))
    }

    override suspend fun move(
        document: PresetProfileDocument,
        fromIndex: Int,
        toIndex: Int,
    ): PresetMutationOutcome = save(document, PresetProfileParser.move(document, fromIndex, toIndex))

    private suspend fun save(document: PresetProfileDocument, updated: String?): PresetMutationOutcome {
        if (updated == null) return PresetMutationOutcome.Rejected(PresetIssue.MALFORMED_PROTOCOL)
        return presets.save(
            fileName = document.fileName,
            expectedContent = document.sourceText,
            content = updated,
            applyAfterSave = false,
        )
    }

    private companion object {
        const val MAX_CATALOG_BYTES = 1024 * 1024
    }
}

internal fun parseStrategyCatalog(source: String): List<StrategyCatalogEntry>? {
    val result = mutableListOf<StrategyCatalogEntry>()
    var id: String? = null
    var name = ""
    var author = ""
    var label = ""
    var description = ""
    var requiredBlobs = emptySet<String>()
    val seenMetadata = mutableSetOf<String>()
    val arguments = mutableListOf<String>()
    fun flush(): Boolean {
        val currentId = id ?: return true
        if (name.isBlank() || arguments.isEmpty()) return false
        val referencedBlobs = arguments.flatMap { argument ->
            STRATEGY_BLOB_REFERENCE.findAll(argument).map { it.groupValues[1] }.toList()
        }.filterNot { it.startsWith("0x") || it in BUILTIN_STRATEGY_BLOBS }.toSet()
        result += StrategyCatalogEntry(
            currentId,
            name,
            author,
            label,
            description,
            arguments.toList(),
            requiredBlobs + referencedBlobs,
        )
        return true
    }
    for (raw in source.replace("\r\n", "\n").replace('\r', '\n').lineSequence()) {
        val line = raw.trim()
        when {
            line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
            line.startsWith("[") && line.endsWith("]") -> {
                if (!flush()) return null
                id = line.substring(1, line.length - 1).takeIf { it.matches(CATALOG_IDENTIFIER) }
                    ?: return null
                name = ""; author = ""; label = ""; description = ""; requiredBlobs = emptySet()
                seenMetadata.clear(); arguments.clear()
            }
            line.startsWith("name = ") -> {
                if (id == null || !seenMetadata.add("name")) return null
                name = line.removePrefix("name = ").takeIf(String::isNotBlank) ?: return null
            }
            line.startsWith("author = ") -> {
                if (id == null || !seenMetadata.add("author")) return null
                author = line.removePrefix("author = ").takeIf(String::isNotBlank) ?: return null
            }
            line.startsWith("label = ") -> {
                if (id == null || !seenMetadata.add("label")) return null
                label = line.removePrefix("label = ").takeIf(String::isNotBlank) ?: return null
            }
            line.startsWith("description = ") -> {
                if (id == null || !seenMetadata.add("description")) return null
                description = line.removePrefix("description = ").takeIf(String::isNotBlank) ?: return null
            }
            line.startsWith("blobs = ") -> {
                if (id == null || !seenMetadata.add("blobs")) return null
                val values = line.removePrefix("blobs = ").split(',').map(String::trim)
                if (values.isEmpty() || values.any { !it.matches(CATALOG_IDENTIFIER) } ||
                    values.distinct().size != values.size
                ) {
                    return null
                }
                requiredBlobs = values.toSet()
            }
            line.startsWith("--lua-desync=") -> {
                if (id == null || line.removePrefix("--lua-desync=").isBlank()) return null
                arguments += line
            }
            else -> return null
        }
    }
    if (!flush() || result.map { it.id }.distinct().size != result.size) return null
    return result
}

internal fun profileListEntryMatchesSelector(fileName: String, selectorLine: String): Boolean = when {
    selectorLine.startsWith("--ipset=") || selectorLine.startsWith("--ipset-exclude=") ->
        isIpSetHostlistFileName(fileName)
    selectorLine.startsWith("--hostlist=") || selectorLine.startsWith("--hostlist-exclude=") ->
        !isIpSetHostlistFileName(fileName)
    else -> false
}

private val CATALOG_IDENTIFIER = Regex("[A-Za-z0-9_.-]+")
private val STRATEGY_BLOB_REFERENCE = Regex("(?:^|:)(?:blob|fake_blob|pattern|seqovl_pattern)=([A-Za-z0-9_.-]+)")
private val BUILTIN_STRATEGY_BLOBS = setOf("fake_default_http", "fake_default_quic", "fake_default_tls")
