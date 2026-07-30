package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.UUID

/**
 * A single RFCOMM connection to the target. Abstracting the socket behind this interface is
 * what makes [AttackEngine]'s worker/retry/stop/stats logic unit-testable off-device: tests
 * substitute a fake, production uses [BluetoothRfcommConnection].
 */
interface RfcommConnection {
    /** True once [connect] has succeeded and the link is still up. */
    val isConnected: Boolean

    /** Blocking connect; throws [java.io.IOException] on failure. */
    fun connect()

    /** Blocking write of the whole buffer; throws [java.io.IOException] if the link drops. */
    fun write(bytes: ByteArray)

    /** Idempotent close; also unblocks a thread parked in [connect]/[write]. */
    fun close()
}

/** Creates [RfcommConnection]s to a fixed target for a given service-record UUID. */
interface RfcommConnectionFactory {
    /** May throw [java.io.IOException] if a socket can't be created. */
    fun create(uuid: UUID): RfcommConnection
}

/** Real factory backed by a [BluetoothDevice]'s insecure RFCOMM sockets. */
class BluetoothRfcommConnectionFactory(
    private val device: BluetoothDevice,
) : RfcommConnectionFactory {
    @SuppressLint("MissingPermission")
    override fun create(uuid: UUID): RfcommConnection =
        BluetoothRfcommConnection(device.createInsecureRfcommSocketToServiceRecord(uuid))
}

/** Real [RfcommConnection] wrapping a [BluetoothSocket]. */
class BluetoothRfcommConnection(
    private val socket: BluetoothSocket,
) : RfcommConnection {

    override val isConnected: Boolean
        get() = socket.isConnected

    @SuppressLint("MissingPermission")
    override fun connect() {
        socket.connect()
    }

    override fun write(bytes: ByteArray) {
        socket.outputStream.write(bytes)
    }

    override fun close() {
        socket.close()
    }
}
