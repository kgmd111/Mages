package org.mlm.mages.matrix

import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.remote.RemoteBinding
import io.github.mlmgames.settings.core.remote.RemotePullPolicy
import io.github.mlmgames.settings.core.remote.RemoteSettingsStore
import io.github.mlmgames.settings.core.remote.RemoteUnsupportedException
import io.github.mlmgames.settings.core.remote.SettingsRemoteSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.AppSettingsSchema

const val MEDIA_PREVIEWS_FIELD = "mediaPreviews"
const val BLOCK_INVITES_FIELD = "blockInvites"

/** MSC4278 media previews and MSC4380 invite blocking, so both follow the account. */
class MatrixRemoteSettingsStore(
    private val port: () -> MatrixPort,
) : RemoteSettingsStore {
    /**
     * There is no account data change notification, so nothing arrives here after the initial
     * read. A change made on another client lands the next time the account is opened.
     */
    override val changes: Flow<Map<String, String>> = emptyFlow()

    /**
     * A field the account never set is omitted rather than returned as a default, which is
     * what keeps an absent remote from overwriting the local choice. A failure is left to
     * propagate so the sync reports it instead of mirroring a value it never read.
     */
    override suspend fun read(fields: List<String>): Map<String, String> {
        if (fields.isEmpty()) return emptyMap()
        fields.forEach { requireSupported(it) }

        val matrix = runCatching { port() }.getOrNull() ?: return emptyMap()
        if (!matrix.isLoggedIn()) return emptyMap()

        val values = mutableMapOf<String, String>()
        if (MEDIA_PREVIEWS_FIELD in fields) {
            matrix.mediaPreviewConfig()?.let { values[MEDIA_PREVIEWS_FIELD] = it.name }
        }
        if (BLOCK_INVITES_FIELD in fields) {
            matrix.inviteBlocked()?.let { values[BLOCK_INVITES_FIELD] = it.toString() }
        }
        return values
    }

    override suspend fun write(field: String, value: String) {
        when (field) {
            MEDIA_PREVIEWS_FIELD -> {
                // The sync round-trips the settings enum's entry name, so [MediaPreviewMode] has
                // to name its entries the same way or this throws rather than writing a wrong value.
                val mode = runCatching { MediaPreviewMode.valueOf(value) }
                    .getOrElse { throw RemoteUnsupportedException("Unknown media preview mode: $value") }
                port().setMediaPreviewConfig(mode).getOrThrow()
            }
            BLOCK_INVITES_FIELD -> {
                val blocked = value.toBooleanStrictOrNull()
                    ?: throw RemoteUnsupportedException("Unknown invite block value: $value")
                port().setInviteBlocked(blocked).getOrThrow()
            }
            else -> requireSupported(field)
        }
    }

    private fun requireSupported(field: String) {
        if (field != MEDIA_PREVIEWS_FIELD && field != BLOCK_INVITES_FIELD) {
            throw RemoteUnsupportedException("Unsupported remote field: $field")
        }
    }
}

fun accountDataSettingsSync(
    repository: SettingsRepository<AppSettings>,
    port: () -> MatrixPort,
): SettingsRemoteSync<AppSettings> = SettingsRemoteSync(
    repository = repository,
    schema = AppSettingsSchema,
    store = MatrixRemoteSettingsStore(port),
    bindings = listOf(
        RemoteBinding(field = MEDIA_PREVIEWS_FIELD),
        RemoteBinding(field = BLOCK_INVITES_FIELD),
    ),
    pullPolicy = RemotePullPolicy.ONCE_PER_ATTACH,
)
