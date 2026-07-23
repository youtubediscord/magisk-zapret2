package com.zapret2.app.data

import kotlinx.coroutines.CancellationException
import javax.inject.Inject

internal sealed interface HostsOverlaySnapshot {
    data class Present(val content: String) : HostsOverlaySnapshot
    data object Missing : HostsOverlaySnapshot
    data object Unsafe : HostsOverlaySnapshot
}

internal sealed interface HostsOverlayMutationOutcome {
    data class Applied(val effectiveContent: String) : HostsOverlayMutationOutcome
    data object SourceChanged : HostsOverlayMutationOutcome
    data object Failed : HostsOverlayMutationOutcome
}

/** Owns the systemless hosts overlay and its exact rollback snapshot. */
class HostsOverlayRepository @Inject constructor() {

    internal fun readEffective(): String? = readEffective(snapshotOverlay())

    internal fun readEffective(overlay: HostsOverlaySnapshot): String? = when (overlay) {
        is HostsOverlaySnapshot.Present -> overlay.content
        HostsOverlaySnapshot.Missing -> RootFileIo.readSecureRegularText(SYSTEM_HOSTS, MAX_HOSTS_BYTES)
            ?.takeIf(::isContentSizeAllowed)
        HostsOverlaySnapshot.Unsafe -> null
    }

    internal fun snapshotOverlay(): HostsOverlaySnapshot {
        val quoted = RootFileIo.shellQuote(OVERLAY_HOSTS)
        val probe = RootCommandExecutor.execute(
            "if [ ! -e $quoted ] && [ ! -L $quoted ]; then echo MISSING; else echo PRESENT; fi",
        )
        return when (probe.out.singleOrNull().takeIf { probe.isSuccess }) {
            "MISSING" -> HostsOverlaySnapshot.Missing
            "PRESENT" -> RootFileIo.readSecureRegularText(OVERLAY_HOSTS, MAX_HOSTS_BYTES)
                ?.takeIf(::isContentSizeAllowed)
                ?.let(HostsOverlaySnapshot::Present) ?: HostsOverlaySnapshot.Unsafe
            else -> HostsOverlaySnapshot.Unsafe
        }
    }

    internal fun writeIfUnchanged(
        expectedOverlay: HostsOverlaySnapshot,
        expectedEffectiveContent: String,
        content: String,
        delimiterPrefix: String = "__ZAPRET_HOSTS_EOF__",
    ): HostsOverlayMutationOutcome {
        val liveOverlay = snapshotOverlay()
        if (liveOverlay == HostsOverlaySnapshot.Unsafe) return HostsOverlayMutationOutcome.Failed
        if (liveOverlay != expectedOverlay) return HostsOverlayMutationOutcome.SourceChanged
        val liveEffective = readEffective(liveOverlay) ?: return HostsOverlayMutationOutcome.Failed
        if (liveEffective != expectedEffectiveContent) return HostsOverlayMutationOutcome.SourceChanged
        val written = try {
            write(content, delimiterPrefix)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!written) return HostsOverlayMutationOutcome.Failed
        val persisted = try {
            snapshotOverlay()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HostsOverlaySnapshot.Unsafe
        }
        return if (persisted is HostsOverlaySnapshot.Present &&
            canonicalProtectedText(persisted.content) == canonicalProtectedText(content)
        ) {
            HostsOverlayMutationOutcome.Applied(persisted.content)
        } else {
            HostsOverlayMutationOutcome.Failed
        }
    }

    internal fun removeIfUnchanged(
        expectedOverlay: HostsOverlaySnapshot,
        expectedEffectiveContent: String,
    ): HostsOverlayMutationOutcome {
        val liveOverlay = snapshotOverlay()
        if (liveOverlay == HostsOverlaySnapshot.Unsafe) return HostsOverlayMutationOutcome.Failed
        if (liveOverlay != expectedOverlay) return HostsOverlayMutationOutcome.SourceChanged
        val liveEffective = readEffective(liveOverlay) ?: return HostsOverlayMutationOutcome.Failed
        if (liveEffective != expectedEffectiveContent) return HostsOverlayMutationOutcome.SourceChanged
        val removed = try {
            remove()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!removed) return HostsOverlayMutationOutcome.Failed
        val overlayAfterRemove = try {
            snapshotOverlay()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HostsOverlaySnapshot.Unsafe
        }
        if (overlayAfterRemove != HostsOverlaySnapshot.Missing) return HostsOverlayMutationOutcome.Failed
        val effectiveAfterRemove = try {
            readEffective(overlayAfterRemove)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return HostsOverlayMutationOutcome.Failed
        return HostsOverlayMutationOutcome.Applied(effectiveAfterRemove)
    }

    private fun write(content: String, delimiterPrefix: String): Boolean {
        val normalized = normalizedForWrite(content)
        if (!isContentSizeAllowed(normalized) || !isValidContent(normalized)) return false
        return writeNormalized(normalized, delimiterPrefix)
    }

    private fun writeNormalized(content: String, delimiterPrefix: String): Boolean {
        return RootFileIo.ensureDirectory(OVERLAY_HOSTS.substringBeforeLast('/')) &&
            RootFileIo.writeTextAtomically(OVERLAY_HOSTS, content, delimiterPrefix, fileMode = "0644")
    }

    private fun remove(): Boolean = RootFileIo.removeFile(OVERLAY_HOSTS)

    internal fun isContentSizeAllowed(content: String): Boolean =
        normalizedForWrite(content).toByteArray(Charsets.UTF_8).size <= MAX_HOSTS_BYTES

    internal fun isValidContent(content: String): Boolean = HostsFileSyntax.isValidFile(content)

    private fun normalizedForWrite(content: String): String = canonicalProtectedText(content) + "\n"

    internal fun restore(snapshot: HostsOverlaySnapshot): Boolean = when (snapshot) {
        is HostsOverlaySnapshot.Present -> {
            val normalized = normalizedForWrite(snapshot.content)
            isContentSizeAllowed(normalized) &&
                writeNormalized(normalized, "__ZAPRET_HOSTS_ROLLBACK_EOF__")
        }
        HostsOverlaySnapshot.Missing -> remove()
        HostsOverlaySnapshot.Unsafe -> false
    }

    internal companion object {
        const val SYSTEM_HOSTS = "/system/etc/hosts"
        const val OVERLAY_HOSTS = "/data/adb/modules/zapret2/system/etc/hosts"
        const val MAX_HOSTS_BYTES = 1024 * 1024
    }
}
