package rip.sunrise.packets.clientbound

import java.io.Serializable

/**
 * Revision info response
 * e -> revision data
 */
data class RevisionInfoResp(val e: String?) : Serializable