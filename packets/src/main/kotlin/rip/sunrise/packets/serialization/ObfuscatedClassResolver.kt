package rip.sunrise.packets.serialization

import io.netty.handler.codec.serialization.ClassResolver
import io.netty.handler.codec.serialization.ClassResolvers
import rip.sunrise.packets.clientbound.*
import rip.sunrise.packets.serverbound.*

object ObfuscatedClassResolver : ClassResolver {
    val defaultResolver: ClassResolver = ClassResolvers.cacheDisabled(null)

    val map = mapOf(
        "b1" to FreeScriptListRequest::class,
        "aR" to PaidScriptListRequest::class,
        "ba" to ScriptSessionRequest::class,
        "bq" to ScriptStartRequest::class,
        "a1" to ScriptOptionsRequest::class,
        "B5" to EncryptedScriptRequest::class,
        "bC" to GetActiveInstancesRequest::class,
        "aZ" to GetTotalInstancesRequest::class,
        "bG" to AuthenticationCodeRequest::class,
        "a7" to PurchasedScriptIdsRequest::class,

        "ah" to GetInstancesResp::class,
        "a9" to ScriptSessionResp::class,
        "Ad" to ScriptStartResp::class,
        "a3" to ScriptListResp::class,
        "b3" to ScriptWrapper::class,
        "bd" to ScriptOptionsResp::class,
        "aJ" to EncryptedScriptResp::class,
        "bR" to AuthenticationCodeResp::class,
        "b0" to PurchasedScriptIdsResp::class,
    ).map { (k, v) -> "org.dreambot.$k" to v.java }.toMap()

    override fun resolve(className: String?): Class<*> {
        return map[className] ?: defaultResolver.resolve(className)
    }
}