package se.hellsoft.bluetoothx

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import se.hellsoft.bluetoothx.GattCharacteristic.Companion.PROPERTY_WRITE
import se.hellsoft.bluetoothx.GattCharacteristic.Companion.PROPERTY_WRITE_NO_RESPONSE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import android.bluetooth.BluetoothDevice as FwkDevice
import android.bluetooth.BluetoothGattCharacteristic as FwkCharacteristic
import android.bluetooth.BluetoothGattDescriptor as FwkDescriptor
import android.bluetooth.BluetoothGattService as FwkService

class GattClient(private val context: Context) {
    internal interface FrameworkAdapter {
        var bluetoothGatt: BluetoothGatt?
        fun connectGatt(
            context: Context,
            device: FwkDevice,
            callback: BluetoothGattCallback,
            phyMode: Int
        ): Boolean

        fun requestMtu(mtu: Int)

        fun discoverServices()

        fun getServices(): List<FwkService>
        fun getService(uuid: UUID): FwkService?
        fun readCharacteristic(characteristic: FwkCharacteristic)
        fun writeCharacteristic(
            characteristic: FwkCharacteristic,
            value: ByteArray,
            writeType: Int
        )

        fun writeDescriptor(descriptor: FwkDescriptor, value: ByteArray)

        fun setCharacteristicNotification(characteristic: FwkCharacteristic, enable: Boolean)

        fun closeGatt()
    }

    companion object {
        private const val GATT_MAX_MTU = 515
        private const val DEFAULT_MTU = 20
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private val CCCD_UID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    internal var fwkAdapter: FrameworkAdapter =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) FrameworkAdapterApi33()
        else FrameworkAdapterBase()

    private sealed interface CallbackResult {
        class OnCharacteristicRead(
            val characteristic: GattCharacteristic,
            val value: ByteArray,
            val status: Int
        ) : CallbackResult

        class OnCharacteristicWrite(
            val characteristic: GattCharacteristic,
            val status: Int
        ) : CallbackResult

        class OnDescriptorRead(
            val descriptor: FwkDescriptor,
            val value: ByteArray,
            val status: Int
        ) : CallbackResult

        class OnDescriptorWrite(
            val descriptor: FwkDescriptor,
            val status: Int
        ) : CallbackResult
    }

    private interface SubscribeListener {
        fun onCharacteristicNotification(value: ByteArray)
        fun finish()
    }

    @SuppressLint("MissingPermission")
    suspend fun <R> connect(
        device: BluetoothDevice,
        phyMode: Int,
        block: suspend BluetoothLe.GattClientScope.(mtuSize: Int) -> R
    ): R = coroutineScope {
        val connectResult = CompletableDeferred<Unit>(parent = coroutineContext.job)
        val callbackResultsFlow =
            MutableSharedFlow<CallbackResult>(extraBufferCapacity = Int.MAX_VALUE)
        val subscribeMap: MutableMap<FwkCharacteristic, SubscribeListener> = mutableMapOf()
        val subscribeMutex = Mutex()
        val attributeMap = AttributeMap()
        val servicesFlow = MutableStateFlow<List<GattService>>(listOf())
        var mtuSize = DEFAULT_MTU

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    fwkAdapter.requestMtu(GATT_MAX_MTU)
                } else {
                    cancel("connect failed")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtuSize = mtu
                    fwkAdapter.discoverServices()
                } else {
                    cancel("mtu request failed")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                attributeMap.updateWithFrameworkServices(fwkAdapter.getServices())
                if (status == BluetoothGatt.GATT_SUCCESS) connectResult.complete(Unit)
                else cancel("service discover failed")
                servicesFlow.tryEmit(attributeMap.getServices())
                if (connectResult.isActive) {
                    if (status == BluetoothGatt.GATT_SUCCESS) connectResult.complete(Unit)
                    else connectResult.cancel("service discover failed")
                }
            }

            override fun onServiceChanged(gatt: BluetoothGatt) {
                // TODO: under API 31, we have to subscribe to the service changed characteristic.
                fwkAdapter.discoverServices()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                onCharacteristicRead(gatt, characteristic, characteristic.value, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: FwkCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                attributeMap.fromFwkCharacteristic(characteristic)?.let {
                    callbackResultsFlow.tryEmit(
                        CallbackResult.OnCharacteristicRead(it, value, status)
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: FwkCharacteristic,
                status: Int
            ) {
                attributeMap.fromFwkCharacteristic(characteristic)?.let {
                    callbackResultsFlow.tryEmit(
                        CallbackResult.OnCharacteristicWrite(it, status)
                    )
                }
            }

            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: FwkDescriptor,
                status: Int,
                value: ByteArray
            ) {
                callbackResultsFlow.tryEmit(
                    CallbackResult.OnDescriptorRead(descriptor, value, status)
                )
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: FwkDescriptor,
                status: Int
            ) {
                callbackResultsFlow.tryEmit(CallbackResult.OnDescriptorWrite(descriptor, status))
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                onCharacteristicChanged(gatt, characteristic, characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: FwkCharacteristic,
                value: ByteArray
            ) {
                launch {
                    subscribeMutex.withLock {
                        subscribeMap[characteristic]?.onCharacteristicNotification(value)
                    }
                }
            }
        }

        if (!fwkAdapter.connectGatt(context, device.fwkDevice, callback, phyMode)) {
            throw CancellationException("failed to connect")
        }

        withTimeout(CONNECT_TIMEOUT_MS) {
            connectResult.await()
        }

        val gattScope = object : BluetoothLe.GattClientScope {

            val taskMutex = Mutex()
            suspend fun <R> runTask(block: suspend () -> R): R {
                taskMutex.withLock {
                    return block()
                }
            }

            override val servicesFlow: StateFlow<List<GattService>> = servicesFlow.asStateFlow()

            override fun getService(uuid: UUID): GattService? {
                return fwkAdapter.getService(uuid)?.let { attributeMap.fromFwkService(it) }
            }

            override suspend fun readCharacteristic(characteristic: GattCharacteristic):
                    Result<ByteArray> {
                if (characteristic.properties and GattCharacteristic.PROPERTY_READ == 0) {
                    return Result.failure(IllegalArgumentException("can't read the characteristic"))
                }
                return runTask {
                    fwkAdapter.readCharacteristic(characteristic.fwkCharacteristic)
                    val res = takeMatchingResult<CallbackResult.OnCharacteristicRead>(
                        callbackResultsFlow
                    ) {
                        it.characteristic == characteristic
                    }

                    if (res.status == BluetoothGatt.GATT_SUCCESS) Result.success(res.value)
                    // TODO: throw precise reason if we can gather the info
                    else Result.failure(CancellationException("fail"))
                }
            }

            override suspend fun writeCharacteristic(
                characteristic: GattCharacteristic,
                value: ByteArray
            ): Result<Unit> {
                val writeType =
                    if (characteristic.properties and PROPERTY_WRITE_NO_RESPONSE != 0)
                        FwkCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else if (characteristic.properties and PROPERTY_WRITE != 0)
                        FwkCharacteristic.WRITE_TYPE_DEFAULT
                    else return Result.failure(
                        IllegalArgumentException("can't write to the characteristic")
                    )

                return runTask {
                    fwkAdapter.writeCharacteristic(
                        characteristic.fwkCharacteristic, value, writeType
                    )
                    val res = takeMatchingResult<CallbackResult.OnCharacteristicWrite>(
                        callbackResultsFlow
                    ) {
                        it.characteristic == characteristic
                    }
                    if (res.status == BluetoothGatt.GATT_SUCCESS) Result.success(Unit)
                    // TODO: throw precise reason if we can gather the info
                    else Result.failure(CancellationException("fail"))
                }
            }

            override fun subscribeToCharacteristic(characteristic: GattCharacteristic):
                    Flow<ByteArray> {
                if (characteristic.properties and GattCharacteristic.PROPERTY_NOTIFY == 0) {
                    return emptyFlow()
                }
                val cccd = characteristic.fwkCharacteristic.getDescriptor(CCCD_UID)
                    ?: return emptyFlow()

                return callbackFlow {
                    val listener = object : SubscribeListener {
                        override fun onCharacteristicNotification(value: ByteArray) {
                            trySend(value)
                        }

                        override fun finish() {
                            close()
                        }
                    }
                    if (!registerSubscribeListener(characteristic.fwkCharacteristic, listener)) {
                        throw IllegalStateException("already subscribed")
                    }

                    runTask {
                        fwkAdapter.setCharacteristicNotification(
                            characteristic.fwkCharacteristic, /*enable=*/true
                        )

                        fwkAdapter.writeDescriptor(cccd, FwkDescriptor.ENABLE_NOTIFICATION_VALUE)
                        val res = takeMatchingResult<CallbackResult.OnDescriptorWrite>(
                            callbackResultsFlow
                        ) {
                            it.descriptor == cccd
                        }
                        if (res.status != BluetoothGatt.GATT_SUCCESS) {
                            cancel("failed to set notification")
                        }
                    }

                    this.awaitClose {
                        launch {
                            unregisterSubscribeListener(characteristic.fwkCharacteristic)
                        }
                        fwkAdapter.setCharacteristicNotification(
                            characteristic.fwkCharacteristic, /*enable=*/false
                        )

                        fwkAdapter.writeDescriptor(cccd, FwkDescriptor.DISABLE_NOTIFICATION_VALUE)
                    }
                }
            }

            private suspend fun registerSubscribeListener(
                characteristic: FwkCharacteristic,
                callback: SubscribeListener
            ): Boolean {
                subscribeMutex.withLock {
                    if (subscribeMap.containsKey(characteristic)) {
                        return false
                    }
                    subscribeMap[characteristic] = callback
                    return true
                }
            }

            private suspend fun unregisterSubscribeListener(
                characteristic: FwkCharacteristic
            ) {
                subscribeMutex.withLock {
                    subscribeMap.remove(characteristic)
                }
            }
        }

        coroutineContext.job.invokeOnCompletion { fwkAdapter.closeGatt() }

        gattScope.block(mtuSize)
    }

    private suspend inline fun <reified R : CallbackResult> takeMatchingResult(
        flow: SharedFlow<CallbackResult>,
        crossinline predicate: (R) -> Boolean
    ): R {
        return flow.filter { it is R && predicate(it) }.first() as R
    }

    private open class FrameworkAdapterBase : FrameworkAdapter {
        override var bluetoothGatt: BluetoothGatt? = null

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun connectGatt(
            context: Context,
            device: FwkDevice,
            callback: BluetoothGattCallback,
            phyMode: Int
        ): Boolean {
            bluetoothGatt = device.connectGatt(
                context,
                false, // We will only do direct connections
                callback,
                FwkDevice.TRANSPORT_LE,
                phyMode
            )
            return bluetoothGatt != null
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun requestMtu(mtu: Int) {
            bluetoothGatt?.requestMtu(mtu)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun discoverServices() {
            bluetoothGatt?.discoverServices()
        }

        override fun getServices(): List<FwkService> {
            return bluetoothGatt?.services ?: listOf()
        }

        override fun getService(uuid: UUID): FwkService? {
            return bluetoothGatt?.getService(uuid)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun readCharacteristic(characteristic: FwkCharacteristic) {
            bluetoothGatt?.readCharacteristic(characteristic)
        }

        @Suppress("DEPRECATION")
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeCharacteristic(
            characteristic: FwkCharacteristic,
            value: ByteArray,
            writeType: Int
        ) {
            characteristic.value = value
            bluetoothGatt?.writeCharacteristic(characteristic)
        }

        @Suppress("DEPRECATION")
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeDescriptor(descriptor: FwkDescriptor, value: ByteArray) {
            descriptor.value = value
            bluetoothGatt?.writeDescriptor(descriptor)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun setCharacteristicNotification(
            characteristic: FwkCharacteristic,
            enable: Boolean
        ) {
            bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun closeGatt() {
            bluetoothGatt?.close()
            bluetoothGatt?.disconnect()
        }
    }

    private open class FrameworkAdapterApi33 : FrameworkAdapterBase() {
        @SuppressLint("NewApi")
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeCharacteristic(
            characteristic: FwkCharacteristic,
            value: ByteArray,
            writeType: Int
        ) {
            bluetoothGatt?.writeCharacteristic(characteristic, value, writeType)
        }

        @SuppressLint("NewApi")
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeDescriptor(
            descriptor: FwkDescriptor,
            value: ByteArray
        ) {
            bluetoothGatt?.writeDescriptor(descriptor, value)
        }
    }

}