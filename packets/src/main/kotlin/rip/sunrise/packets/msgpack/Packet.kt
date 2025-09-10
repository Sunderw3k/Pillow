package rip.sunrise.packets.msgpack

import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker

const val WRAPPED_PACKET_ID: Byte = 127

abstract class Packet<T : Packet<T>> {
    abstract val id: Byte
    var counter: Int = -1

    /**
     * Packs a MsgPack packet. The format is
     * ```
     * PacketID - Byte
     * Counter - Int
     * Length - BinaryHeader
     * Payload - Byte Array
     * ```
     *
     * In case [wrap] is true (what the client does since 3.30.36), it sets `packetId` to [WRAPPED_PACKET_ID], and the data is
     * ```
     * currentTime - Extension Timestamp
     * wrappedPacket - MsgPack Packet
     * ```
     */
    fun pack(counter: Int, wrap: Boolean): ByteArray {
        return MessagePack.newDefaultBufferPacker().use { packer ->
            if (wrap) {
                packer.packByte(WRAPPED_PACKET_ID)
                packer.packTimestamp(System.currentTimeMillis())
            }

            packer.packByte(id) // packet id
            packer.packInt(counter) // counter

            val innerBytes = MessagePack.newDefaultBufferPacker().use {
                packInner(it); it
            }.toByteArray()

            packer.packBinaryHeader(innerBytes.size) // data size
            packer.writePayload(innerBytes) // data

            packer.toByteArray()
        }
    }

    protected abstract fun packInner(packer: MessagePacker)
}

interface PacketUnpacker<T : Packet<T>> {
    fun unpack(unpacker: MessageUnpacker): T
}

fun <T : Packet<T>> MessageUnpacker.unpackWith(packetUnpacker: PacketUnpacker<T>): T {
    val counter = unpackInt()
    unpackBinaryHeader() // NOTE: Packet size

    val packet = packetUnpacker.unpack(this)
    packet.counter = counter

    assert(!hasNext()) { "Failed to consume whole stream" }
    return packet
}