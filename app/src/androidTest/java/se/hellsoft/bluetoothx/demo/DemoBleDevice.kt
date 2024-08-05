package se.hellsoft.bluetoothx.demo

import android.content.Context
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import se.hellsoft.bluetoothx.BluetoothAddress
import se.hellsoft.bluetoothx.BluetoothDevice
import se.hellsoft.bluetoothx.BluetoothLe
import se.hellsoft.bluetoothx.ScanFilter
import java.util.UUID

class DemoBleDevice(context: Context) {

    companion object {
        private val GATT_SERVICE = UUID.fromString("98BD0001-0B0E-421A-84E5-DDBF75DC6DE4")
        private val GATT_NOTIFICATION_CHAR = UUID.fromString("98BD0003-0B0E-421A-84E5-DDBF75DC6DE4")
        private val GATT_WRITE_CHAR = UUID.fromString("98BD0002-0B0E-421A-84E5-DDBF75DC6DE4")
    }

    private val bluetoothLe = BluetoothLe(context)
    private val _dataFromDevice = MutableSharedFlow<ByteArray>()
    val receivedData: Flow<ByteArray> = _dataFromDevice

    private val writeChannel = Channel<ByteArray>(capacity = Channel.BUFFERED)

    suspend fun scan(deviceAddress: BluetoothAddress): BluetoothDevice? {
        return bluetoothLe
            .scan(listOf(ScanFilter(deviceAddress)))
            .filter { it.isConnectable() }
            .map { it.device }
            .firstOrNull()
    }

    suspend fun connect(device: BluetoothDevice) = coroutineScope {
        bluetoothLe.connectGatt(device) { mtuSize ->
            val service = getService(GATT_SERVICE)
            if (service == null) {
                cancel("GATT Service not found!")
                return@connectGatt
            }

            launch {
                val characteristic = service.getCharacteristic(GATT_NOTIFICATION_CHAR)
                if (characteristic == null) {
                    cancel("GATT Characteristic for writing not found!")
                    return@launch
                }

                writeChannel.receiveAsFlow()
                    .collect { data ->
                        if (data.size > mtuSize) {
                            splitByteArrayIntoChunks(data, mtuSize).forEach { chunk ->
                                writeCharacteristic(characteristic, chunk)
                            }
                        } else {
                            writeCharacteristic(characteristic, data)
                        }
                    }
            }

            val characteristic = service.getCharacteristic(GATT_WRITE_CHAR)
            if (characteristic == null) {
                cancel("GATT Characteristic for reading not found!")
                return@connectGatt
            }
            subscribeToCharacteristic(characteristic).collect { _dataFromDevice.emit(it) }
        }
    }

    suspend fun writeData(data: ByteArray) = writeChannel.send(data)

    private fun splitByteArrayIntoChunks(
        byteArray: ByteArray,
        chunkSize: Int
    ): Array<ByteArray> {
        val numOfChunks = (byteArray.size + chunkSize - 1) / chunkSize

        if (numOfChunks <= 1) {
            return arrayOf(byteArray)
        }

        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until numOfChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, byteArray.size)
            chunks.add(byteArray.copyOfRange(start, end))
        }

        return chunks.toTypedArray()
    }
}
