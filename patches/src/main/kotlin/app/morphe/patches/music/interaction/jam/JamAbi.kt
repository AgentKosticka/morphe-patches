package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.getReference
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
private const val HANDLER = "Landroid/os/Handler;"
private const val OPTIONAL = "Lj$/util/Optional;"
private const val REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"
private const val WATCH_FRAGMENT =
    "Lcom/google/android/apps/youtube/music/watch/WatchFragment;"

/**
 * Native members resolved from stable queue relationships.  The patch installs bridges from
 * these references; extension code never observes a YouTube Music implementation name.
 */
internal data class JamQueueAbi(
    val managerType: String,
    val constructor: MethodReference,
    val enqueue: MethodReference,
    val command: ProtoAbi,
    val executor: FieldReference,
    val storage: QueueStorageAbi,
    val displays: QueueDisplaysAbi,
    val callback: QueueCallbackAbi,
    val item: QueueItemAbi,
    val remove: MethodReference,
    val menu: QueueMenuAbi,
    val selection: MethodReference,
    val mutation: QueueMutationAbi,
)

internal data class ProtoAbi(
    val type: String,
    val defaultInstance: FieldReference,
    val parser: MethodReference,
)

internal data class QueueStorageAbi(
    val field: FieldReference,
    val type: String,
    val items: MethodReference,
    val lane: MethodReference,
    val currentIndex: MethodReference,
    val mode: MethodReference,
    val localMode: FieldReference,
    val laneType: String,
    val laneMove: MethodReference,
    val listenerType: String,
    val attachListener: MethodReference,
    val detachListener: MethodReference,
)

internal data class QueueDisplaysAbi(
    val primary: QueueDisplayAbi,
    val autoplay: QueueDisplayAbi,
)

internal data class QueueDisplayAbi(
    val managerField: FieldReference,
    val type: String,
    val list: FieldReference,
    val handler: FieldReference,
    val refresh: MethodReference,
    val currentIndex: MethodReference,
    val commitMove: MethodReference,
    val pendingMove: FieldReference,
)

internal data class QueueCallbackAbi(
    val type: String,
    val constructor: MethodReference,
    val manager: FieldReference,
    val success: MethodReference,
    val failure: MethodReference,
    val responseType: String,
    val responseItems: FieldReference,
)

internal data class QueueItemAbi(
    val type: String,
    val videoId: MethodReference,
    val persistentId: MethodReference,
    val metadataType: String,
    val title: MethodReference,
    val artist: MethodReference,
    val artwork: MethodReference,
    val artworkList: FieldReference,
    val thumbnailType: String,
    val thumbnailUrl: FieldReference,
    val implementations: List<QueueItemImplementationAbi>,
    val createItem: QueueItemImplementationAbi,
    val itemProto: ProtoAbi,
    val factory: FieldReference,
    val menuPayload: QueueItemMenuPayloadAbi? = null,
)

internal data class QueueItemImplementationAbi(
    val type: String,
)

internal data class QueueItemMenuPayloadAbi(
    val type: String,
)

internal data class QueueMenuAbi(
    val dispatcher: FieldReference,
    val dispatch: MethodReference,
    val responseType: String,
    val responseItems: FieldReference,
)

internal data class QueueMutationAbi(
    val provider: FieldReference,
    val providerMethod: MethodReference,
    val notifierType: String,
    val move: MethodReference,
)

/**
 * Resolves Jam's native queue ABI once, from the queue-operation fingerprint outward.  Every
 * candidate is constrained by an observed relationship and fails with a subsystem-specific
 * message instead of silently selecting an arbitrary host member.
 */
internal fun BytecodePatchContext.resolveJamQueueAbi(): JamQueueAbi {
    val queueMatch = QueueEnqueueFingerprint.matchSingle()
    val manager = queueMatch.originalClassDef
    val enqueue = queueMatch.originalMethod
    val command = resolveProto(enqueue.parameters().singleOrNull(), "queue command")
    val managerConstructor = manager.methods.filter { it.name == "<init>" }
        .requireSingle("Jam queue manager constructor")
    val executor = manager.fields.filter { it.type == EXECUTOR }
        .requireSingle("Jam queue executor")
    val storage = resolveStorage(manager, enqueue)
    val displays = resolveDisplays(manager, managerConstructor, storage)
    val remove = manager.methods.filter { method ->
        method.parameters().size == 1 && method.parameters().single().isReferenceType() && method.returnType == "V" &&
            method.fieldReferences().map { it.key() }.containsAll(
                listOf(displays.primary.managerField.key(), displays.autoplay.managerField.key())
            )
    }.requireSingle("Jam native queue removal operation")
    val item = resolveItem(manager, remove, command)
    val callback = resolveCallback(enqueue, manager.type)
    val menu = resolveMenu(manager, command)
    val selection = resolveSelection(item.type, item.persistentId)
    val mutation = resolveMutation(manager, remove, displays.primary, item.type)

    return JamQueueAbi(
        manager.type,
        managerConstructor,
        enqueue,
        command,
        executor,
        storage,
        displays,
        callback,
        item,
        remove,
        menu,
        selection,
        mutation,
    )
}

internal fun BytecodePatchContext.resolveProto(type: String?, concept: String): ProtoAbi {
    require(!type.isNullOrBlank() && type.isReferenceType()) {
        "Unable to resolve Jam $concept protobuf type"
    }
    val message = classDefByOrNull(type)
        ?: error("Unable to resolve Jam $concept protobuf class")
    val defaultInstance = message.fields.filter { field ->
        field.type == type && AccessFlags.STATIC.isSet(field.accessFlags) &&
            AccessFlags.PUBLIC.isSet(field.accessFlags)
    }.requireSingle("Jam $concept protobuf default instance")
    val parser = generateSequence(message) { current ->
        current.superclass?.let(::classDefByOrNull)
    }.flatMap { it.methods.asSequence() }.filter { method ->
        method.name == "parseFrom" &&
            method.parameters() == listOf(method.definingClass, "[B", REGISTRY) &&
            method.returnType == method.definingClass &&
            AccessFlags.STATIC.isSet(method.accessFlags)
    }.toList().requireSingle("Jam $concept protobuf parser")
    return ProtoAbi(type, defaultInstance, parser)
}

private fun BytecodePatchContext.resolveStorage(
    manager: ClassDef,
    enqueue: Method,
): QueueStorageAbi {
    val candidates = manager.fields.filter { field ->
        val storage = classDefByOrNull(field.type) ?: return@filter false
        val listAccessor = storage.methods.any { method ->
            method.parameters() == listOf("I") && method.returnType == LIST
        }
        val lanes = storage.methods.filter { method ->
            method.parameters() == listOf("I") && method.returnType.isReferenceType() &&
                method.returnType != LIST
        }
        listAccessor && lanes.any { lane ->
            val laneClass = classDefByOrNull(lane.returnType) ?: return@any false
            laneClass.methods.any { it.parameters() == listOf("I", "I") && it.returnType == "V" } &&
                laneClass.methods.any { it.parameters() == listOf("I", "I") && it.returnType == LIST }
        }
    }
    val field = candidates.requireSingle("Jam queue storage reachable from queue manager")
    val storage = classDefBy(field.type)
    val items = storage.methods.filter { it.parameters() == listOf("I") && it.returnType == LIST }
        .requireSingle("Jam queue snapshot accessor")
    val lane = storage.methods.filter { method ->
        method.parameters() == listOf("I") && method.returnType.isReferenceType() && method.returnType != LIST &&
            classDefByOrNull(method.returnType)?.methods?.any {
                it.parameters() == listOf("I", "I") && it.returnType == "V"
            } == true
    }.requireSingle("Jam queue observable-lane accessor")
    val laneClass = classDefBy(lane.returnType)
    val laneMove = laneClass.methods.filter {
        it.parameters() == listOf("I", "I") && it.returnType == "V"
    }.requireSingle("Jam native queue move operation")
    fun Method.delegatesToQueueState(): Boolean = methodReferences().any { reference ->
        reference.parameters().isEmpty() && reference.returnType == returnType &&
            storage.fields.any { field -> field.type == reference.definingClass } &&
            classDefByOrNull(reference.definingClass)
                ?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
    }
    val currentIndex = storage.methods.filter { method ->
        method.parameters().isEmpty() && method.returnType == "I" && method.delegatesToQueueState()
    }.requireSingle("Jam native current-index accessor")
    val mode = storage.methods.filter { method ->
        method.parameters().isEmpty() && classDefByOrNull(method.returnType)
            ?.let { AccessFlags.ENUM.isSet(it.accessFlags) } == true && method.delegatesToQueueState()
    }.requireSingle("Jam native queue mode accessor")
    val localMode = resolveLocalMode(mode)

    val listenerReferences = laneClass.methods.filter { method ->
        method.parameters().size == 1 && method.returnType == "V" &&
            manager.type != method.parameters().single() && method.parameters().single().isReferenceType()
    }
    val displayListenerCandidates = listenerReferences.filter { candidate ->
        manager.fields.any { managerField ->
            val display = classDefByOrNull(managerField.type) ?: return@any false
            candidate.parameters().single() in display.interfaces
        }
    }
    val listenerType = displayListenerCandidates.map { it.parameters().single() }.distinct()
        .requireSingle("Jam displayed-queue listener interface")
    val listenerMethods = displayListenerCandidates.filter { it.parameters() == listOf(listenerType) }
    fun listenerMutation(operation: String): List<Method> {
        val mutations = queueLaneListenerMutationFingerprint(listenerType, operation).matchAll()
            .filter { match -> implementsType(match.originalClassDef.type, lane.returnType) }
            .map { it.originalMethod }
        return listenerMethods.filter { declaration ->
            mutations.any { implementation ->
                implementation.name == declaration.name &&
                    implementation.parameters() == declaration.parameters() &&
                    implementation.returnType == declaration.returnType
            }
        }
    }
    val attach = listenerMutation("add").requireSingle("Jam queue listener attach operation")
    val detach = listenerMutation("remove").requireSingle("Jam queue listener detach operation")

    check(enqueue.fieldReferences().any { it.key() == field.key() } ||
        manager.methods.any { it.fieldReferences().any { ref -> ref.key() == field.key() } }) {
        "Unable to confirm Jam queue storage from queue-operation path"
    }
    return QueueStorageAbi(
        field,
        storage.type,
        items,
        lane,
        currentIndex,
        mode,
        localMode,
        lane.returnType,
        laneMove,
        listenerType,
        attach,
        detach,
    )
}

private fun BytecodePatchContext.resolveLocalMode(mode: MethodReference): FieldReference {
    val enum = classDefBy(mode.returnType)
    require(AccessFlags.ENUM.isSet(enum.accessFlags)) { "Jam queue mode is not an enum" }
    val instructions = enum.methods.singleOrNull { it.name == "<clinit>" }
        ?.instructionsOrNull?.toList()
        ?: error("Unable to inspect Jam queue mode enum initialization")
    val localModes = instructions.indices.mapNotNull { index ->
        val literal = (instructions[index] as? ReferenceInstruction)?.getReference<StringReference>()
        if (literal?.string != "LOCAL") return@mapNotNull null
        instructions.drop(index).firstOrNull { instruction ->
            instruction.opcode == Opcode.SPUT_OBJECT &&
                (instruction as? ReferenceInstruction)?.getReference<FieldReference>()?.type == enum.type
        }?.let { instruction ->
            (instruction as ReferenceInstruction).getReference<FieldReference>()
        }
    }.distinctBy { it.key() }
    return localModes.requireSingle("Jam native local queue mode enum constant")
}

private fun BytecodePatchContext.resolveDisplays(
    manager: ClassDef,
    constructor: Method,
    storage: QueueStorageAbi,
): QueueDisplaysAbi {
    val fields = manager.fields.filter { field ->
        val display = classDefByOrNull(field.type) ?: return@filter false
        display.fields.any { it.type == storage.type } && display.fields.any { it.type == HANDLER } &&
            display.fields.any { it.type == storage.laneType }
    }
    require(fields.size == 2) {
        "Unable to resolve Jam displayed queue controllers: expected main and autoplay, found ${fields.size}"
    }
    val lanes = displayLaneStores(constructor, fields)
    val primary = resolveDisplay(fields.single { lanes.getValue(it.key()) == 0 }, storage)
    val autoplay = resolveDisplay(fields.single { lanes.getValue(it.key()) == 1 }, storage)
    return QueueDisplaysAbi(primary, autoplay)
}

private fun BytecodePatchContext.displayLaneStores(
    constructor: Method,
    fields: List<FieldReference>,
): Map<String, Int> {
    val instructions = constructor.instructionsOrNull?.toList()
        ?: error("Unable to inspect Jam displayed queue controller construction")
    return fields.associate { field ->
        val storeIndex = instructions.indices.filter { index ->
            instructions[index].opcode == Opcode.IPUT_OBJECT &&
                (instructions[index] as? ReferenceInstruction)
                    ?.getReference<FieldReference>()?.sameField(field) == true
        }.requireSingle("Jam displayed queue controller lane store")
        val store = instructions[storeIndex] as? TwoRegisterInstruction
            ?: error("Unable to read Jam displayed queue controller lane store")
        val resultIndex = storeIndex - 1
        val result = instructions.getOrNull(resultIndex) as? OneRegisterInstruction
            ?: error("Unable to read Jam displayed queue controller factory result")
        require(instructions[resultIndex].opcode == Opcode.MOVE_RESULT_OBJECT && result.registerA == store.registerA) {
            "Unable to link Jam displayed queue controller factory result"
        }
        val factoryCall = instructions.getOrNull(resultIndex - 1) as? ReferenceInstruction
            ?: error("Unable to read Jam displayed queue controller factory call")
        val factory = factoryCall.getReference<MethodReference>()
            ?: error("Unable to resolve Jam displayed queue controller factory")
        require(factory.parameters() == listOf("I") && factory.returnType == field.type) {
            "Unable to confirm Jam displayed queue controller lane factory"
        }
        val laneRegister = factoryCall.argumentRegister(
            classDefByOrNull(factory.definingClass)?.methods?.singleOrNull { it.sameMethod(factory) }
                ?.let { AccessFlags.STATIC.isSet(it.accessFlags) } == true,
        ) ?: error("Unable to resolve Jam displayed queue controller lane argument")
        val lane = instructions.take(resultIndex - 1).asReversed().mapNotNull { instruction ->
            val literal = instruction as? NarrowLiteralInstruction ?: return@mapNotNull null
            val destination = instruction as? OneRegisterInstruction ?: return@mapNotNull null
            literal.narrowLiteral.takeIf { destination.registerA == laneRegister }
        }.firstOrNull() ?: error("Unable to resolve Jam displayed queue controller lane value")
        require(lane in 0..1) { "Unexpected Jam displayed queue controller lane $lane" }
        field.key() to lane
    }
}

private fun ReferenceInstruction.argumentRegister(staticCall: Boolean): Int? =
    registerAt(if (staticCall) 0 else 1)

private fun BytecodePatchContext.resolveDisplay(
    managerField: FieldReference,
    storage: QueueStorageAbi,
): QueueDisplayAbi {
    val display = classDefBy(managerField.type)
    val list = display.fields.filter { it.type == storage.laneType }
        .requireSingle("Jam displayed queue list field")
    val handler = display.fields.filter { it.type == HANDLER }
        .requireSingle("Jam displayed queue UI handler")
    val current = display.methods.filter { method ->
        method.parameters().isEmpty() && method.returnType == "I" &&
            method.methodReferences().any { it.sameMethod(storage.currentIndex) }
    }.requireSingle("Jam displayed queue current-index bridge")
    val refresh = display.methods.filter { method ->
        method.parameters().isEmpty() && method.returnType == "V" &&
            method.methodReferences().any { it.name == "subList" && it.parameters() == listOf("I", "I") && it.returnType == LIST } &&
            method.methodReferences().any { it.definingClass == LIST && it.name == "add" && it.parameters() == listOf("I", OBJECT) && it.returnType == "V" } &&
            method.methodReferences().any { it.definingClass == LIST && it.name == "remove" && it.parameters() == listOf("I") && it.returnType == OBJECT }
    }.requireSingle("Jam displayed queue refresh operation")
    val move = display.methods.filter { method ->
        method.parameters() == listOf("I", "I") && method.returnType == "V" &&
            method.methodReferences().any { it.sameMethod(storage.laneMove) }
    }.requireSingle("Jam displayed queue move commit")
    val pending = move.referenceInstructions().filter { it.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == display.type }
        .requireSingle("Jam displayed queue pending move field")
    return QueueDisplayAbi(managerField, display.type, list, handler, refresh, current, move, pending)
}

private fun BytecodePatchContext.resolveItem(
    manager: ClassDef,
    remove: Method,
    command: ProtoAbi,
): QueueItemAbi {
    val itemType = remove.parameters().singleOrNull().requireValue("Jam queue item type")
    val itemClasses = concreteImplementationsOf(itemType)
    require(itemClasses.isNotEmpty()) { "Unable to resolve concrete Jam queue item implementations" }
    val persistentId = interfaceMethods(itemType).filter { method ->
        method.parameters().isEmpty() && method.returnType == "J" &&
            classDefBy(method.definingClass).methods.any { companion ->
                companion.parameters().isEmpty() && companion.returnType == command.type
            }
    }.requireSingle("Jam queue item persistent-ID accessor")
    val videoId = interfaceMethods(itemType).filter { method ->
        method.parameters().isEmpty() && method.returnType == STRING &&
            classDefBy(method.definingClass)?.methods?.let { methods ->
                methods.count { it.parameters().isEmpty() && it.returnType == STRING } == 1 &&
                    methods.any { it.parameters().isEmpty() && it.returnType.isReferenceType() }
            } == true
    }.requireSingle("Jam queue item video-ID accessor")

    val sharedInterfaces = itemClasses.map { interfaceClosure(it.type) }
        .reduce { shared, next -> shared intersect next }
    val metadata = sharedInterfaces.mapNotNull(::classDefByOrNull).filter { candidate ->
        candidate.methods.count { it.parameters().isEmpty() && it.returnType == STRING } == 2 &&
            candidate.methods.any { method ->
                method.parameters().isEmpty() && method.returnType.isReferenceType() &&
                    classDefByOrNull(method.returnType)?.fields?.any { field ->
                        field.type == LIST || implementsType(field.type, LIST)
                    } == true
            }
    }.requireSingle("Jam queue item metadata interface")
    val textMethods = metadata.methods.filter {
        it.parameters().isEmpty() && it.returnType == STRING
    }
    val nowPlayingTextBinding = classDefBy(WATCH_FRAGMENT).methods.filter { method ->
        method.parameters() == listOf(OPTIONAL) && method.returnType == "V" &&
            textMethods.all { accessor ->
                method.methodReferences().any { it.sameMethod(accessor) }
            }
    }.requireSingle("Jam now-playing metadata text binding")
    val title = textMethods.filter { accessor ->
        nowPlayingTextBinding.methodReferences().count { it.sameMethod(accessor) } >= 2
    }.requireSingle("Jam queue title metadata accessor")
    val artist = (textMethods - title).requireSingle("Jam queue artist metadata accessor")
    val artworkFailures = mutableListOf<String>()
    val artworkCandidates = metadata.methods.mapNotNull { method ->
        if (!method.parameters().isEmpty() || !method.returnType.isReferenceType()) return@mapNotNull null
        val artworkClass = classDefByOrNull(method.returnType) ?: return@mapNotNull null
        val artworkList = artworkClass.fields.singleOrNull { field ->
            field.type == LIST || implementsType(field.type, LIST)
        } ?: return@mapNotNull null
        val thumbnailType = runCatching { resolveThumbnailType(artworkList) }
            .onFailure { artworkFailures += "$method: ${it.message}" }
            .getOrNull()
            ?: return@mapNotNull null
        Triple(method, artworkList, thumbnailType)
    }
    require(artworkCandidates.size == 1) {
        "Unable to resolve Jam queue artwork accessor: expected one candidate, found ${artworkCandidates.size}: " +
            "${artworkCandidates.joinToString()}; discarded ${artworkFailures.joinToString()}"
    }
    val artworkCandidate = artworkCandidates.single()
    val artwork = artworkCandidate.first
    val artworkList = artworkCandidate.second
    val thumbnailType = artworkCandidate.third
    val thumbnailUrl = classDefBy(thumbnailType).fields.filter { it.type == STRING }
        .requireSingle("Jam thumbnail URL field")

    val implementationCandidates = itemClasses.filter { itemClass ->
        implementsType(itemClass.type, metadata.type)
    }.map { itemClass ->
        QueueItemImplementationAbi(itemClass.type)
    }
    require(implementationCandidates.isNotEmpty()) {
        "Unable to resolve concrete Jam queue item implementations with metadata access"
    }
    val createItem = implementationCandidates.filter { implementation ->
        val itemClass = classDefBy(implementation.type)
        itemClass.methods.any { constructor ->
            constructor.name == "<init>" && constructor.parameters().size == 3 &&
                constructor.parameters().first() == "J" && constructor.parameters()[2].isReferenceType()
        }
    }.map { implementation ->
        val itemClass = classDefBy(implementation.type)
        val constructor = itemClass.methods.filter { candidate ->
            candidate.name == "<init>" && candidate.parameters().size == 3 &&
                candidate.parameters().first() == "J" && candidate.parameters()[2].isReferenceType()
        }.singleOrNull() ?: return@map null
        implementation to constructor
    }.mapNotNull { it }.filter { (_, constructor) ->
        manager.fields.any { it.type == constructor.parameters()[2] }
    }.requireSingle("Jam native queue item constructor")
    val itemProto = resolveProto(createItem.second.parameters()[1], "queue item")
    val factory = manager.fields.filter { it.type == createItem.second.parameters()[2] }
        .requireSingle("Jam native queue item factory")

    return QueueItemAbi(
        itemType,
        videoId,
        persistentId,
        metadata.type,
        title,
        artist,
        artwork,
        artworkList,
        thumbnailType,
        thumbnailUrl,
        implementationCandidates,
        createItem.first,
        itemProto,
        factory,
    )
}

private fun BytecodePatchContext.resolveThumbnailType(artworkList: FieldReference): String {
    val usages = thumbnailEntryUsageFingerprint(artworkList).matchAll()
        .map { it.originalMethod }
    val inspectedTypes = usages.flatMap(Method::typeReferences).distinct()
    val candidates = inspectedTypes.filter { type ->
        classDefByOrNull(type)?.isThumbnailEntry() == true
    }
    require(candidates.size == 1) {
        "Unable to resolve Jam thumbnail entry type: expected one candidate, found ${candidates.size}: " +
            "${candidates.joinToString()}; inspected ${inspectedTypes.joinToString()}"
    }
    return candidates.single()
}

private fun BytecodePatchContext.resolveCallback(
    enqueue: Method,
    managerType: String,
): QueueCallbackAbi {
    val callback = enqueue.methodReferences().filter { it.name == "<init>" }
        .mapNotNull { classDefByOrNull(it.definingClass) }
        .filter { candidate ->
            candidate.fields.any { it.type == managerType } &&
                candidate.methods.count { it.name != "<init>" && it.parameters().size == 1 && it.returnType == "V" } >= 2
        }.requireSingle("Jam queue mutation callback")
    val constructor = callback.methods.filter { method ->
        method.name == "<init>" && method.fieldReferences().any { it.type == managerType }
    }.requireSingle("Jam queue callback constructor")
    val manager = callback.fields.filter { it.type == managerType }
        .requireSingle("Jam queue callback manager field")
    val success = callback.methods.filter { method ->
        method.name != "<init>" && method.parameters().size == 1 && method.returnType == "V" &&
            method.typeReferences().any { type ->
                classDefByOrNull(type)?.fields?.any {
                    it.type == LIST || implementsType(it.type, LIST)
                } == true
            }
    }.requireSingle("Jam queue success callback")
    val responseType = success.typeReferences().filter { type ->
        classDefByOrNull(type)?.fields?.any {
            it.type == LIST || implementsType(it.type, LIST)
        } == true
    }.requireSingle("Jam queue completion response type")
    val responseItems = classDefBy(responseType).fields.filter {
        it.type == LIST || implementsType(it.type, LIST)
    }
        .requireSingle("Jam queue completion item list")
    val failure = callback.methods.filter { method ->
        method.name != "<init>" && method.parameters().size == 1 && method.returnType == "V" &&
            !method.sameMethod(success)
    }.requireSingle("Jam queue failure callback")
    return QueueCallbackAbi(callback.type, constructor, manager, success, failure, responseType, responseItems)
}

private fun BytecodePatchContext.resolveMenu(
    manager: ClassDef,
    command: ProtoAbi,
): QueueMenuAbi {
    val candidates = manager.fields.mapNotNull { field ->
        val dispatcher = classDefByOrNull(field.type) ?: return@mapNotNull null
        val dispatch = dispatcher.methods.singleOrNull { method ->
            method.parameters() == listOf(command.type, EXECUTOR) && method.returnType.isReferenceType()
        } ?: return@mapNotNull null
        field to dispatch
    }
    val (dispatcher, dispatch) = candidates.requireSingle("Jam native menu dispatcher")
    val dispatcherClass = classDefBy(dispatcher.type)
    val mapperCandidates = dispatch.typeReferences().mapNotNull(::classDefByOrNull).filter { candidate ->
        candidate.methods.any { it.parameters() == listOf(OBJECT) && it.returnType == OBJECT } &&
            candidate.methods.any { method ->
                method.name == "<init>" && method.parameters().any { parameter ->
                    dispatcherClass.fields.any { it.type == parameter }
                }
            }
    }
    val response = mapperCandidates.flatMap { mapper ->
        mapper.methods.filter { method ->
            method.parameters() == listOf(OBJECT) && method.returnType == OBJECT
        }.flatMap(Method::typeReferences)
    }.mapNotNull(::classDefByOrNull).filter { candidate ->
        val listFields = candidate.fields.filter { it.type == LIST || implementsType(it.type, LIST) }
        listFields.size == 1 && candidate.methods.any { method ->
            method.name == "<init>" && method.parameters().any { parameter ->
                parameter == LIST || implementsType(parameter, LIST)
            }
        }
    }.distinctBy { it.type }.requireSingle("Jam native menu response")
    val items = response.fields.filter { it.type == LIST || implementsType(it.type, LIST) }
        .requireSingle("Jam native menu response items")
    return QueueMenuAbi(dispatcher, dispatch, response.type, items)
}

private fun BytecodePatchContext.resolveSelection(
    itemType: String,
    persistentId: MethodReference,
): MethodReference = queueItemSelectionFingerprint(itemType, persistentId).matchSingle().originalMethod

private fun BytecodePatchContext.resolveMutation(
    manager: ClassDef,
    remove: Method,
    display: QueueDisplayAbi,
    itemType: String,
): QueueMutationAbi {
    val removalNotifier = remove.methodReferences().filter { method ->
        method.parameters().size == 2 && method.parameters()[0] in interfaceClosure(itemType) &&
            method.parameters()[1] == "Z" && method.returnType == "V"
    }.distinctBy { it.methodKey() }.requireSingle("Jam native queue removal notifier")
    val commitMove = classDefBy(display.commitMove.definingClass).methods.filter {
        it.sameMethod(display.commitMove)
    }.requireSingle("Jam displayed queue move commit")
    val move = classDefBy(removalNotifier.definingClass).methods.filter { method ->
        method.parameters().size == 2 && method.parameters()[0] == method.parameters()[1] &&
            method.returnType == "V" && commitMove.methodReferences().any { it.sameMethod(method) }
    }.requireSingle("Jam native queue move notifier")
    val instructions = remove.instructionsOrNull?.toList()
        ?: error("Unable to inspect Jam queue removal operation")
    val notifierIndices = instructions.indices.filter { index ->
        instructions[index].getReference<MethodReference>()?.sameMethod(removalNotifier) == true
    }
    val providers = notifierIndices.mapNotNull { index ->
        runCatching { resolveMutationProvider(manager, instructions, index, removalNotifier) }.getOrNull()
    }.distinctBy { (provider, method) -> "${provider.definingClass}->${provider.name}:${provider.type}:${method.methodKey()}" }
    val (provider, providerMethod) = providers.requireSingle("Jam native queue mutation provider")
    return QueueMutationAbi(provider, providerMethod, removalNotifier.definingClass, move)
}

private fun resolveMutationProvider(
    manager: ClassDef,
    instructions: List<Instruction>,
    notifierIndex: Int,
    notifier: MethodReference,
): Pair<FieldReference, MethodReference> {
    val notifierInstruction = instructions[notifierIndex] as? ReferenceInstruction
        ?: error("Unable to inspect Jam native queue removal notifier invocation")
    val notifierReceiver = notifierInstruction.registerAt(0)
        ?: error("Unable to resolve Jam native queue removal notifier receiver")
    val castIndex = instructions.indices.take(notifierIndex).lastOrNull { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.CHECK_CAST &&
            (instruction as? OneRegisterInstruction)?.registerA == notifierReceiver &&
            instruction.getReference<TypeReference>()?.type == notifier.definingClass
    } ?: error("Unable to resolve Jam native queue mutation notifier cast")
    val resultIndex = castIndex - 1
    require(resultIndex >= 1) { "Unable to resolve Jam native queue mutation provider result" }
    val result = instructions[resultIndex] as? OneRegisterInstruction
    require(instructions[resultIndex].opcode == Opcode.MOVE_RESULT_OBJECT && result?.registerA == notifierReceiver) {
        "Unable to resolve Jam native queue mutation provider result"
    }
    val callIndex = resultIndex - 1
    val providerCallInstruction = instructions[callIndex] as? ReferenceInstruction
        ?: error("Unable to inspect Jam native queue mutation provider call")
    val providerMethod = providerCallInstruction.getReference<MethodReference>()
        ?.takeIf { it.parameters().isEmpty() && it.returnType == OBJECT }
        ?: error("Unable to resolve Jam native queue mutation provider method")
    val providerReceiver = providerCallInstruction.registerAt(0)
        ?: error("Unable to resolve Jam native queue mutation provider receiver")
    val loadIndex = callIndex - 1
    val load = instructions[loadIndex] as? TwoRegisterInstruction
    val provider = instructions[loadIndex].getReference<FieldReference>()
        ?.takeIf {
            instructions[loadIndex].opcode == Opcode.IGET_OBJECT &&
                load?.registerA == providerReceiver &&
                it.definingClass == manager.type && it.type == providerMethod.definingClass
        }
        ?: error("Unable to resolve Jam native queue mutation provider field")
    return provider to providerMethod
}

internal fun BytecodePatchContext.concreteImplementationsOf(type: String): List<ClassDef> = buildList {
    classDefForEach { candidate ->
        if (!AccessFlags.INTERFACE.isSet(candidate.accessFlags) &&
            !AccessFlags.ABSTRACT.isSet(candidate.accessFlags) &&
            implementsType(candidate.type, type)
        ) {
            add(candidate)
        }
    }
}

internal fun BytecodePatchContext.interfaceClosure(type: String): Set<String> {
    val visited = mutableSetOf<String>()
    fun visit(candidate: String) {
        if (!visited.add(candidate)) return
        val classDef = classDefByOrNull(candidate) ?: return
        classDef.interfaces.forEach(::visit)
        classDef.superclass?.let(::visit)
    }
    visit(type)
    return visited
}

internal fun BytecodePatchContext.implementsType(type: String, parent: String): Boolean =
    parent in interfaceClosure(type)

private fun BytecodePatchContext.interfaceMethods(type: String): List<Method> = interfaceClosure(type)
    .flatMap { candidate ->
        classDefByOrNull(candidate)?.methods?.toList() ?: emptyList()
    }
    .distinctBy { it.methodKey() }

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Method.referenceInstructions(): List<ReferenceInstruction> =
    instructionsOrNull?.filterIsInstance<ReferenceInstruction>().orEmpty()

private fun Method.methodReferences(): List<MethodReference> = referenceInstructions()
    .mapNotNull { it.getReference<MethodReference>() }

private fun Method.fieldReferences(): List<FieldReference> = referenceInstructions()
    .mapNotNull { it.getReference<FieldReference>() }

private fun Method.typeReferences(): List<String> = referenceInstructions()
    .mapNotNull { it.getReference<TypeReference>()?.type }

private fun Method.sameMethod(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && parameters() == other.parameters() &&
        returnType == other.returnType

private fun MethodReference.sameMethod(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && parameters() == other.parameters() &&
        returnType == other.returnType

private fun FieldReference.sameField(other: FieldReference): Boolean =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun FieldReference.key(): String = "$definingClass->$name:$type"

private fun Method.methodKey(): String = "$definingClass->$name(${parameters().joinToString()})$returnType"

private fun MethodReference.methodKey(): String = "$definingClass->$name(${parameters().joinToString()})$returnType"

private fun ReferenceInstruction.registerAt(index: Int): Int? = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG)
        .take(registerCount).getOrNull(index)
    is RegisterRangeInstruction -> (startRegister + index).takeIf { index < registerCount }
    else -> null
}

private fun String.isReferenceType(): Boolean = startsWith("L") || startsWith("[")

private fun ClassDef.isThumbnailEntry(): Boolean {
    val instanceFields = fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
    return instanceFields.count { it.type == STRING } == 1 &&
        instanceFields.count { it.type == "I" } >= 2 &&
        instanceFields.all { it.type == STRING || it.type == "I" }
}

private fun <T> Iterable<T>.requireSingle(concept: String): T {
    val values = toList()
    require(values.size == 1) {
        "Unable to resolve $concept: expected one candidate, found ${values.size}: ${values.joinToString()}"
    }
    return values.single()
}

private fun <T> T?.requireValue(concept: String): T =
    this ?: error("Unable to resolve $concept")
