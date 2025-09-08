package rip.sunrise.server.utils.extensions

import rip.sunrise.packets.clientbound.ScriptWrapper
import rip.sunrise.server.config.Config

fun Config.generateFakeScriptWrappers(): List<ScriptWrapper> {
    val existingStoreIds = scripts.map { it.metadata.d }.toSet()

    return purchasedScripts.filter { it.storeId !in existingStoreIds }.map {
        ScriptWrapper(
            0,
            "",
            it.name,
            0,
            0.0,
            "",
            "",
            "",
            "",
            "",
            it.storeId,
            it.scriptId,
            false
        )
    }
}

fun Config.isRealScriptId(scriptId: Int): Boolean {
    val existingScriptIds = scripts.map { it.metadata.x }.toSet()

    return scriptId in existingScriptIds
}