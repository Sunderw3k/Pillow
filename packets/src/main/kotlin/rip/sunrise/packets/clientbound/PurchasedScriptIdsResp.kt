package rip.sunrise.packets.clientbound

import java.io.Serializable
import java.util.TreeSet

data class PurchasedScriptIdsResp(val p: TreeSet<Int>) : Serializable
