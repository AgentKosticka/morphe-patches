package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** Small native access adapters. All queue and presentation policy belongs in the extension. */
internal fun ProtoAbi.decode(bytes: String, result: String): String {
    val registry = if (result == "v1") "v2" else "v1"
    return """
        sget-object $result, $defaultInstance
        invoke-static {}, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
        move-result-object $registry
        invoke-static {$result, $bytes, $registry}, $parser
        move-result-object $result
        check-cast $result, $type
    """
}

internal fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    accessFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    body: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            accessFlags,
            null,
            null,
            MutableMethodImplementation(registers),
        ).toMutable().apply { addInstructions(0, body) }
    )
}

internal fun BytecodePatchContext.invokeKind(reference: MethodReference): String {
    val owner = classDefByOrNull(reference.definingClass)
    return when {
        reference.name == "<init>" -> "invoke-direct"
        reference is Method && AccessFlags.PRIVATE.isSet(reference.accessFlags) -> "invoke-direct"
        owner != null && AccessFlags.INTERFACE.isSet(owner.accessFlags) -> "invoke-interface"
        else -> "invoke-virtual"
    }
}

