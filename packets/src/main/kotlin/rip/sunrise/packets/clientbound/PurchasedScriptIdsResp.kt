package rip.sunrise.packets.clientbound

import java.io.Serializable

data class PurchasedScriptIdsResp(val p: Set<Int>) : Serializable
