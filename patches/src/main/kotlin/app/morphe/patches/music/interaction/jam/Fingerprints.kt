package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val AUTO_CROP_IMAGE_VIEW =
    "Lcom/google/android/apps/youtube/music/ui/image/AutoCropImageView;"

private const val OBJECT = "Ljava/lang/Object;"
private const val OPTIONAL = "Lj$/util/Optional;"
private const val VIEW = "Landroid/view/View;"

/**
 * The queue operation emits stable diagnostic strings from YouTube Music's queue manager.
 * Every other queue member is derived from this semantic entry point in [JamAbi].
 */
internal object QueueEnqueueFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf(
        "com/google/android/apps/youtube/music/player/queue/MusicPlaybackQueueOperationsManager",
        "enqueue",
        "enqueue item, QueueTarget: %s, position: %s",
    ),
)

/**
 * The platform MediaSession call is stable even when YouTube Music's state adapter is renamed.
 */
internal object MediaSessionStateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/support/v4/media/session/PlaybackStateCompat;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "setPlaybackState",
            parameters = listOf("Landroid/media/session/PlaybackState;"),
            returnType = "V",
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
        )
    ),
)

/**
 * The now-playing presenter writes a bitmap into YouTube Music's stable artwork view.
 * The field access ties the call to the presenter itself rather than a reusable image helper.
 */
internal object NowPlayingArtworkFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/graphics/Bitmap;"),
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            type = AUTO_CROP_IMAGE_VIEW,
            opcode = Opcode.IGET_OBJECT,
        ),
        methodCall(
            definingClass = AUTO_CROP_IMAGE_VIEW,
            name = "setImageBitmap",
            parameters = listOf("Landroid/graphics/Bitmap;"),
            returnType = "V",
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
        ),
    ),
)

/** Identify the native full-player presenter by its stable view resources. */
internal object PlayerMetadataViewsFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_page"),
        resourceLiteral(ResourceType.ID, "mini_player_title"),
        resourceLiteral(ResourceType.ID, "mini_player_subtitle"),
    ),
)

/** Rebind the presenter from the current-item source when a Jam snapshot changes. */
internal fun nowPlayingRefreshFingerprint(currentAccessor: MethodReference) = Fingerprint(
    classFingerprint = PlayerMetadataViewsFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(methodCall(reference = currentAccessor)),
)

/** Only the resolved player presenter is intercepted, never unrelated app labels. */
internal fun nowPlayingTextWritesFingerprint(presenterType: String) = Fingerprint(
    definingClass = presenterType,
    filters = listOf(methodCall(
        name = "setText",
        parameters = listOf("Ljava/lang/CharSequence;"),
        returnType = "V",
        opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
    )),
)

/**
 * Account-scoped endpoint routing uses these stable diagnostics while resolving the fragment
 * delegate. The matched method's return type is the peer router implementation.
 */
internal object AccountScopedCommandRouterFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    strings = listOf(
        "com.google.android.apps.youtube.app.endpoint.routers.AccountScopeCommandRouterFragment",
        "Expected delegate to be AccountScopedCommandRouterImpl, but was ",
    ),
)

/** Finds the current-item comparator from the resolved queue item identity contract. */
internal fun queueItemSelectionFingerprint(
    itemType: String,
    persistentId: MethodReference,
) = Fingerprint(
    returnType = "Z",
    parameters = listOf(itemType, "Z"),
    filters = listOf(
        fieldAccess(definingClass = "this", type = itemType, opcode = Opcode.IGET_OBJECT),
        methodCall(
            definingClass = itemType,
            name = persistentId.name,
            parameters = persistentId.parameterTypes.map { it.toString() },
            returnType = persistentId.returnType,
        ),
    ),
)

/** Finds the Watch-page entry point that reads the current-item source. */
internal object CurrentPlaybackItemSourceFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watch/WatchFragment;",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(name = "requireActivity", parameters = emptyList()),
        methodCall(
            definingClass = OPTIONAL,
            name = "isPresent",
            parameters = emptyList(),
            returnType = "Z",
        ),
    ),
)

/** Finds components that own the stable time-bar view. */
internal fun timeBarOwnerFingerprint(timeBarType: String) = Fingerprint(
    filters = listOf(
        fieldAccess(definingClass = "this", type = timeBarType, opcode = Opcode.IGET_OBJECT),
    ),
)

/** Finds the seek callback that forwards a position and native seek context. */
internal fun seekForwarderFingerprint(controlTypes: Set<String>) = Fingerprint(
    returnType = "V",
    parameters = listOf("J", "L"),
    filters = listOf(
        methodCall(parameters = listOf("J", "L"), returnType = "V"),
    ),
    custom = { _, classDef -> classDef.fields.any { it.type in controlTypes } },
)

/** Finds the palette source's publish-then-apply entry point. */
internal object PalettePublicationFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(parameters = listOf(OBJECT), returnType = "V"),
        methodCall(definingClass = "this", parameters = listOf("L"), returnType = "V"),
    ),
)

/** Finds the stable playback-control click callback. */
internal fun playbackControlsClickFingerprint(controlsType: String) = Fingerprint(
    definingClass = controlsType,
    name = "onClick",
    returnType = "V",
    parameters = listOf(VIEW),
)

/** Finds click wrappers that delegate to the stable playback controls. */
internal fun playbackButtonClickFingerprint(controlsType: String) = Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf(VIEW),
    custom = { _, classDef -> classDef.fields.any { it.type == controlsType } },
)

/** Finds an observable queue lane's listener-set mutation implementation. */
internal fun queueLaneListenerMutationFingerprint(
    listenerType: String,
    operation: String,
) = Fingerprint(
    returnType = "V",
    parameters = listOf(listenerType),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/Set;",
            name = operation,
            parameters = listOf(OBJECT),
            returnType = "Z",
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
        ),
    ),
)

/** Finds methods that consume the resolved artwork-list field. */
internal fun thumbnailEntryUsageFingerprint(artworkList: FieldReference) = Fingerprint(
    filters = listOf(fieldAccess(reference = artworkList)),
)

/** Finds the watch-page current-item menu entry point. */
internal fun nowPlayingMenuEntryFingerprint(
    currentType: String,
    currentAccessor: MethodReference,
) = Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watch/WatchFragment;",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(name = "requireActivity", parameters = emptyList()),
        fieldAccess(definingClass = "this", type = currentType, opcode = Opcode.IGET_OBJECT),
        methodCall(reference = currentAccessor),
        methodCall("Lj$/util/Optional;->isPresent()Z"),
    ),
)

/** Finds now-playing queue binders that retrieve a concrete native queue item. */
internal fun queueBindingFingerprint(itemType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf("L", "I"),
    filters = listOf(
        methodCall(parameters = listOf("I"), returnType = itemType),
    ),
)

/** Finds a queue-row binder for one of the resolved concrete queue item types. */
internal fun queueRowBindingFingerprint(itemType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", itemType),
    filters = listOf(
        fieldAccess(definingClass = "this", type = itemType, opcode = Opcode.IPUT_OBJECT),
    ),
)
