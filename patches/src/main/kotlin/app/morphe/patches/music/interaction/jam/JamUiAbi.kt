package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.getReference
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val OBJECT = "Ljava/lang/Object;"
private const val OPTIONAL = "Lj$/util/Optional;"
private const val MAP = "Ljava/util/Map;"
private const val VIEW = "Landroid/view/View;"
private const val PLAYER_CONTROLS =
    "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControls;"
private const val PLAYER_TIME_BAR =
    "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;"
private const val WATCH_FRAGMENT =
    "Lcom/google/android/apps/youtube/music/watch/WatchFragment;"

internal data class JamUiAbi(
    val clock: ClockAbi,
    val palette: PaletteAbi,
    val playback: PlaybackAbi,
    val currentItem: CurrentItemAbi,
    val nowPlaying: NowPlayingAbi,
    val artwork: ArtworkAbi,
    val queueRow: QueueRowAbi,
    val buttons: List<ButtonAbi>,
)

internal data class ClockAbi(
    val mediaState: MethodReference,
    val timeBarType: String,
    val timeBaseType: String,
    val setModel: MethodReference,
    val dragging: MethodReference,
    val modelType: String,
    val concreteModelType: String,
    val position: FieldReference,
    val duration: FieldReference,
    val trailingPosition: FieldReference,
    val seekMarkers: List<FieldReference>,
    val active: FieldReference,
    val seek: MethodReference,
)

internal data class PaletteAbi(
    val type: String,
    val entry: MethodReference,
    val extractor: FieldReference,
    val extract: MethodReference,
    val publisher: FieldReference,
    val publish: MethodReference,
    val localPublish: MethodReference,
)

internal data class PlaybackAbi(
    val command: ProtoAbi,
    val routers: List<PlaybackRouterAbi>,
)

internal data class PlaybackRouterAbi(
    val type: String,
    val dispatch: MethodReference,
)

internal data class CurrentItemAbi(
    val type: String,
    val accessor: MethodReference,
)

internal data class NowPlayingAbi(
    val presenter: NowPlayingPresenterAbi,
    val menuEntry: MethodReference,
    val queueBindings: List<QueueBindingAbi>,
)

internal data class NowPlayingPresenterAbi(
    val type: String,
    val entry: MethodReference,
)

internal data class QueueBindingAbi(
    val type: String,
    val entry: MethodReference,
    val itemLookup: MethodReference,
    val refresh: MethodReference,
)

internal data class ArtworkAbi(
    val type: String,
    val update: MethodReference,
    val image: FieldReference,
)

internal data class QueueRowAbi(
    val type: String,
    val item: FieldReference,
    val rootView: MethodReference,
    val bind: MethodReference,
    val click: MethodReference,
    val menuAccessor: MethodReference,
    val menuPresenter: FieldReference,
    val menuContext: FieldReference,
    val menuPayload: QueueItemMenuPayloadAbi,
    val menuDispatch: MethodReference,
)

internal data class ButtonAbi(
    val type: String,
    val click: MethodReference,
)

/** Resolves presentation hooks from queue and platform relationships, never host obfuscation names. */
internal fun BytecodePatchContext.resolveJamUiAbi(queue: JamQueueAbi): JamUiAbi {
    val clock = resolveClock()
    val palette = resolvePalette()
    val playback = resolvePlayback()
    val currentItem = resolveCurrentItem()
    val artwork = resolveArtwork()
    val nowPlaying = resolveNowPlaying(currentItem, queue.item)
    val queueRow = resolveQueueRow(queue.item, nowPlaying.menuEntry)
    val buttons = resolveButtons()
    return JamUiAbi(clock, palette, playback, currentItem, nowPlaying, artwork, queueRow, buttons)
}

private fun BytecodePatchContext.resolveClock(): ClockAbi {
    val mediaState = MediaSessionStateFingerprint.matchSingle().originalMethod
    val timeBar = classDefByOrNull(PLAYER_TIME_BAR)
        ?: error("Unable to resolve Jam playback time bar")
    val baseCandidates = hierarchy(timeBar.type).flatMap { owner ->
        owner.methods.filter { method ->
            method.parameters().size == 1 && method.parameters().single().isReferenceType() &&
                method.returnType == "V" && owner.fields.any { it.type == method.parameters().single() } &&
                classDefByOrNull(method.parameters().single())?.methods?.count {
                    it.parameters().isEmpty() && it.returnType == "J"
                } ?: 0 >= 3
        }
    }
    val setModel = baseCandidates.requireSingle("Jam playback clock model setter")
    val timeBase = classDefBy(setModel.definingClass)
    val dragging = timeBase.methods.filter {
        it.parameters().isEmpty() && it.returnType == "Z"
    }.requireSingle("Jam playback clock drag-state accessor")
    val model = classDefBy(setModel.parameters().single())
    val concrete = concreteImplementationsOf(model.type).mapNotNull { candidate ->
        val setter = candidate.methods.singleOrNull {
            it.parameters() == listOf("J", "J", "J", "J") && it.returnType == "V"
        } ?: return@mapNotNull null
        candidate to setter
    }.requireSingle("Jam playback clock mutable model")
    val modelWrites = concrete.second.referenceInstructions().filter { it.opcode == Opcode.IPUT_WIDE }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == concrete.first.type }
        .distinctBy { it.fieldKey() }
    require(modelWrites.size == 4) { "Unable to resolve Jam playback clock model fields" }
    val markerFields = model.methods.filter { it.parameters().isEmpty() && it.returnType == "I" }
        .takeLast(2).map { getter ->
            concrete.first.methods.filter { it.sameSignature(getter) }.flatMap { it.fieldReferences() }
                .filter { it.definingClass == concrete.first.type && it.type == "I" }
                .requireSingle("Jam playback clock seek marker")
        }
    val activeGetter = model.methods.filter { it.parameters().isEmpty() && it.returnType == "Z" }
        .lastOrNull().requireValue("Jam playback clock active-state accessor")
    val active = concrete.first.methods.filter { it.sameSignature(activeGetter) }
        .flatMap { it.fieldReferences() }
        .filter { it.definingClass == concrete.first.type && it.type == "Z" }
        .requireSingle("Jam playback clock active-state field")
    val seek = resolveSeek(timeBar)
    return ClockAbi(
        mediaState,
        timeBar.type,
        timeBase.type,
        setModel,
        dragging,
        model.type,
        concrete.first.type,
        modelWrites.first(),
        modelWrites.last(),
        modelWrites[2],
        markerFields,
        active,
        seek,
    )
}

private fun BytecodePatchContext.resolveSeek(timeBar: ClassDef): MethodReference {
    val controls = timeBarOwnerFingerprint(timeBar.type).matchAll()
        .map { it.originalClassDef }
        .distinctBy { it.type }
    val controlInterfaces = controls.flatMap { implementedInterfaces(it.type) }.toSet()
    require(controlInterfaces.isNotEmpty()) { "Unable to resolve Jam playback-control listener interfaces" }
    val candidates = seekForwarderFingerprint(controlInterfaces).matchAll()
        .map { it.originalMethod }
        .filter { method ->
            method.methodReferences().any { forwarded ->
                forwarded.parameters() == method.parameters() && forwarded.returnType == "V"
            }
        }
    return candidates.requireSingle("Jam playback seek forwarder")
}

private fun BytecodePatchContext.resolvePalette(): PaletteAbi {
    val candidates = PalettePublicationFingerprint.matchAll().flatMap { match ->
        val source = match.originalClassDef
        val entry = match.originalMethod
            source.fields.flatMap { extractor ->
                val extractorClass = classDefByOrNull(extractor.type) ?: return@flatMap emptyList()
                extractorClass.methods.filter { method ->
                    method.parameters() == listOf("Landroid/graphics/Bitmap;") && method.returnType.isReferenceType()
                }.flatMap { extract ->
                    val paletteType = extract.returnType
                    source.fields.flatMap { publisher ->
                        val publisherClass = classDefByOrNull(publisher.type) ?: return@flatMap emptyList()
                        publisherClass.methods.filter { method ->
                            method.parameters() == listOf(OBJECT) && method.returnType == "V"
                        }.flatMap { publish ->
                            source.methods.filter { local ->
                                AccessFlags.PUBLIC.isSet(local.accessFlags) &&
                                    local.parameters() == listOf(paletteType) && local.returnType == "V" &&
                                    entry.methodReferences().any { it.sameMethod(publish) } &&
                                    entry.methodReferences().any { it.sameMethod(local) }
                            }.map { local -> PaletteAbi(source.type, entry, extractor, extract, publisher, publish, local) }
                        }
                    }
                }
            }
    }
    return candidates.requireSingle("Jam player palette pipeline")
}

private fun BytecodePatchContext.resolvePlayback(): PlaybackAbi {
    val anchor = AccountScopedCommandRouterFingerprint.matchSingle()
    val outer = anchor.originalClassDef
    val peer = classDefBy(anchor.originalMethod.returnType)
    val outerDispatch = outer.methods.filter { method ->
        val parameters = method.parameters()
        parameters.size == 2 && parameters[0].isReferenceType() && parameters[1] == MAP &&
            method.returnType == "V" &&
            method.methodReferences().any { reference ->
                reference.parameters() == parameters && reference.returnType == "V" &&
                    classDefByOrNull(reference.definingClass)
                        ?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
            } &&
            method.methodReferences().any { reference ->
                reference.returnType == "Z" && reference.parameters().lastOrNull() == parameters[0]
            }
    }.requireSingle("Jam account-scoped command-router dispatch")
    val forwarder = outerDispatch.methodReferences().filter { reference ->
        reference.parameters() == outerDispatch.parameters() && reference.returnType == "V" &&
            classDefByOrNull(reference.definingClass)
                ?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
    }.distinctBy { it.methodKey() }.requireSingle("Jam account-scoped command-router forwarder")
    val peerDispatch = peer.methods.filter { method ->
        method.parameters() == outerDispatch.parameters() && method.returnType == "V" &&
            method.methodReferences().any { it.sameMethod(forwarder) }
    }.requireSingle("Jam account-scoped peer command-router dispatch")
    val command = resolveProto(outerDispatch.parameters().first(), "account command-router endpoint")
    fun router(owner: ClassDef, dispatch: Method): PlaybackRouterAbi {
        return PlaybackRouterAbi(owner.type, dispatch)
    }
    return PlaybackAbi(
        command,
        listOf(router(outer, outerDispatch), router(peer, peerDispatch)),
    )
}

private fun BytecodePatchContext.resolveCurrentItem(): CurrentItemAbi {
    val source = CurrentPlaybackItemSourceFingerprint.matchSingle().originalMethod
    val sourceTypes = source.fieldReferences().filter { it.definingClass == WATCH_FRAGMENT }
        .map { it.type }.toSet()
    val accessor = source.methodReferences().filter { method ->
        method.parameters().isEmpty() && method.returnType == OPTIONAL && method.definingClass in sourceTypes
    }.distinctBy { it.methodKey() }.requireSingle("Jam current playback item accessor")
    return CurrentItemAbi(accessor.definingClass, accessor)
}

private fun BytecodePatchContext.resolveNowPlaying(
    current: CurrentItemAbi,
    item: QueueItemAbi,
): NowPlayingAbi {
    val menuMatch = nowPlayingMenuEntryFingerprint(current.type, current.accessor).matchSingle()
    val menuEntry = menuMatch.originalMethod
    val presenterMatch = nowPlayingRefreshFingerprint(current.accessor).matchSingle()
    val presenter = NowPlayingPresenterAbi(
        presenterMatch.originalClassDef.type,
        presenterMatch.originalMethod,
    )
    val bindings = queueBindingFingerprint(item.videoId.definingClass).matchAll().mapNotNull { match ->
        val candidate = match.originalClassDef
        val entry = match.originalMethod
        run {
            val lookup = entry.methodReferences().filter { reference ->
                reference.parameters() == listOf("I") && reference.returnType == item.videoId.definingClass
            }.distinctBy { it.methodKey() }.singleOrNull() ?: return@mapNotNull null
            val ownerTypes = hierarchy(candidate.type).map { it.type }.toSet()
            val refresh = candidate.methods.flatMap { it.methodReferences() }.filter { reference ->
                reference.name != "<init>" && reference.name != "<clinit>" &&
                reference.parameters().isEmpty() && reference.returnType == "V" &&
                    reference.definingClass in ownerTypes
            }.distinctBy { it.methodKey() }.singleOrNull() ?: return@mapNotNull null
            QueueBindingAbi(candidate.type, entry, lookup, refresh)
        }
    }
    require(bindings.isNotEmpty()) { "Unable to resolve Jam queue item binding presenters" }
    return NowPlayingAbi(presenter, menuEntry, bindings)
}

private fun BytecodePatchContext.resolveArtwork(): ArtworkAbi {
    val match = NowPlayingArtworkFingerprint.matchSingle()
    val image = match.originalMethod.fieldReferences().filter {
        it.definingClass == match.originalClassDef.type && it.type == AUTO_CROP_IMAGE_VIEW
    }.distinctBy { it.fieldKey() }.requireSingle("Jam now-playing artwork view")
    return ArtworkAbi(match.originalClassDef.type, match.originalMethod, image)
}

private fun BytecodePatchContext.resolveQueueRow(
    item: QueueItemAbi,
    nowPlayingEntry: MethodReference,
): QueueRowAbi {
    val nowPlayingMethod = classDefBy(nowPlayingEntry.definingClass).methods.single {
        it.sameMethod(nowPlayingEntry)
    }
    val menuDispatch = nowPlayingMethod.methodReferences().filter { method ->
        method.parameters().size == 4 && method.parameters()[1] == VIEW && method.returnType == "V"
    }.distinctBy { it.methodKey() }.requireSingle("Jam watch-page menu dispatch")
    val itemTypes = (listOf(item.type) + item.implementations.map { it.type }).distinct()
    val candidates = itemTypes.flatMap { itemType ->
        queueRowBindingFingerprint(itemType).matchAllOrNull().orEmpty()
    }.mapNotNull { match ->
        val row = match.originalClassDef
        val bind = match.originalMethod
        val itemField = bind.referenceInstructions().filter { it.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { it.getReference<FieldReference>() }
            .filter { it.definingClass == row.type && it.type in itemTypes }
            .distinctBy { it.fieldKey() }
            .singleOrNull() ?: return@mapNotNull null
        val root = row.methods.singleOrNull { it.parameters().isEmpty() && it.returnType == VIEW }
            ?: return@mapNotNull null
        val click = row.methods.singleOrNull { method ->
            method.parameters() == listOf(VIEW) && method.returnType == "Z"
        } ?: return@mapNotNull null
        val metadata = classDefBy(item.metadataType)
        val menuAccessor = metadata.methods.singleOrNull {
            it.parameters().isEmpty() && it.returnType == menuDispatch.parameters().first()
        } ?: return@mapNotNull null
        val menuPayload = nowPlayingMethod.methodReferences().filter { method ->
            method.parameters().isEmpty() && method.returnType == menuAccessor.returnType &&
                classDefByOrNull(method.definingClass)
                    ?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true &&
                item.implementations.all { implementation ->
                    implementsType(implementation.type, method.definingClass)
                }
        }.distinctBy { it.methodKey() }.map {
            QueueItemMenuPayloadAbi(it.definingClass)
        }.singleOrNull() ?: return@mapNotNull null
        val presenter = row.fields.singleOrNull { it.type == menuDispatch.definingClass }
            ?: return@mapNotNull null
        val context = row.fields.singleOrNull { it.type == menuDispatch.parameters()[3] }
            ?: return@mapNotNull null
        QueueRowAbi(row.type, itemField, root, bind, click, menuAccessor, presenter, context, menuPayload, menuDispatch)
    }.distinctBy { it.type to it.bind.methodKey() }
    return candidates.requireSingle("Jam queue row and menu presenter")
}

private fun BytecodePatchContext.resolveButtons(): List<ButtonAbi> {
    val controls = classDefByOrNull(PLAYER_CONTROLS)
        ?: error("Unable to resolve Jam stable playback controls")
    return (
        playbackControlsClickFingerprint(controls.type).matchAll() +
            playbackButtonClickFingerprint(controls.type).matchAll()
        ).map { match -> ButtonAbi(match.originalClassDef.type, match.originalMethod) }
        .distinctBy { it.type }
}

private fun BytecodePatchContext.hierarchy(type: String): List<ClassDef> = generateSequence(classDefByOrNull(type)) {
    it.superclass?.let(::classDefByOrNull)
}.toList()

private fun BytecodePatchContext.implementedInterfaces(type: String): Set<String> {
    val interfaces = mutableSetOf<String>()
    fun visit(candidate: String) {
        classDefByOrNull(candidate)?.interfaces?.forEach { parent ->
            if (interfaces.add(parent)) visit(parent)
        }
    }
    visit(type)
    return interfaces
}

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Method.referenceInstructions(): List<ReferenceInstruction> =
    instructionsOrNull?.filterIsInstance<ReferenceInstruction>().orEmpty()

private fun Method.methodReferences(): List<MethodReference> = referenceInstructions()
    .mapNotNull { it.getReference<MethodReference>() }

private fun Method.fieldReferences(): List<FieldReference> = referenceInstructions()
    .mapNotNull { it.getReference<FieldReference>() }

private fun Method.sameMethod(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && parameters() == other.parameters() &&
        returnType == other.returnType

private fun Method.sameSignature(other: MethodReference): Boolean =
    name == other.name && parameters() == other.parameters() && returnType == other.returnType

private fun MethodReference.sameMethod(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && parameters() == other.parameters() &&
        returnType == other.returnType

private fun FieldReference.sameField(other: FieldReference): Boolean =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun FieldReference.fieldKey(): String = "$definingClass->$name:$type"

private fun MethodReference.methodKey(): String =
    "$definingClass->$name(${parameters().joinToString()})$returnType"

private fun String.isReferenceType(): Boolean = startsWith("L") || startsWith("[")

private fun <T> Iterable<T>.requireSingle(concept: String): T {
    val values = toList()
    require(values.size == 1) { "Unable to resolve $concept: expected one candidate, found ${values.size}" }
    return values.single()
}

private fun <T> T?.requireValue(concept: String): T = this ?: error("Unable to resolve $concept")
