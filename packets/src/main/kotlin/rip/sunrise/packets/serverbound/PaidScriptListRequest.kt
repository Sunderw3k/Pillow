package rip.sunrise.packets.serverbound

import java.io.Serializable

/**
 * a -> account session token
 */
data class PaidScriptListRequest(val a: String) : Serializable
