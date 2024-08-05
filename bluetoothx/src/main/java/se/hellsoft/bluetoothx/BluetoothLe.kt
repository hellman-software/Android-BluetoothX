/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.hellsoft.bluetoothx

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.ERROR
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.DoNotInline
import androidx.annotation.IntDef
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import android.bluetooth.le.ScanResult as FwkScanResult

/**
 * Entry point for BLE related operations. This class provides a way to perform Bluetooth LE
 * operations such as scanning, advertising, and connection with a respective [BluetoothDevice].
 */
class BluetoothLe constructor(private val context: Context) {

    companion object {
        /** Advertise started successfully. */
        const val ADVERTISE_STARTED: Int = 101

        /** Advertise failed to start because the data is too large. */
        const val ADVERTISE_FAILED_DATA_TOO_LARGE: Int = 102

        /** Advertise failed to start because the advertise feature is not supported. */
        const val ADVERTISE_FAILED_FEATURE_UNSUPPORTED: Int = 103

        /** Advertise failed to start because of an internal error. */
        const val ADVERTISE_FAILED_INTERNAL_ERROR: Int = 104

        /** Advertise failed to start because of too many advertisers. */
        const val ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: Int = 105
    }

    @Target(AnnotationTarget.TYPE)
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ADVERTISE_STARTED,
        ADVERTISE_FAILED_DATA_TOO_LARGE,
        ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
        ADVERTISE_FAILED_INTERNAL_ERROR,
        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
    )
    annotation class AdvertiseResult

    private object BluetoothLeApi34Impl {
        @SuppressLint("NewApi")
        @JvmStatic
        @DoNotInline
        fun setDiscoverable(
            builder: AdvertiseSettings.Builder,
            isDiscoverable: Boolean
        ): AdvertiseSettings.Builder {
            builder.setDiscoverable(isDiscoverable)
            return builder
        }
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val bluetoothAdapter = bluetoothManager?.adapter

    @VisibleForTesting
    val client: GattClient by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GattClient(context)
    }

    @VisibleForTesting
    var onStartScanListener: OnStartScanListener? = null

    fun deviceFromAddress(address: BluetoothAddress): BluetoothDevice? {
        return bluetoothAdapter?.getRemoteDevice(address.address)?.let { BluetoothDevice(it) }
    }

    /**
     * Returns a _cold_ [Flow] to start Bluetooth LE scanning.
     * Scanning is used to discover advertising devices nearby.
     *
     * @param filters [ScanFilter]s for finding exact Bluetooth LE devices
     *
     * @return a _cold_ [Flow] of [ScanResult] that matches with the given scan filter
     */
    @RequiresPermission("android.permission.BLUETOOTH_SCAN")
    fun scan(filters: List<ScanFilter> = emptyList()): Flow<ScanResult> = callbackFlow {
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: FwkScanResult) {
                trySend(ScanResult(result))
            }

            override fun onScanFailed(errorCode: Int) {
                // TODO(b/270492198): throw precise exception
                cancel("onScanFailed() called with: errorCode = $errorCode")
            }
        }

        val bleScanner = bluetoothAdapter?.bluetoothLeScanner
        val fwkFilters = filters.map { it.fwkScanFilter }
        val scanSettings = ScanSettings.Builder().build()
        bleScanner?.startScan(fwkFilters, scanSettings, callback)
        onStartScanListener?.onStartScan(bleScanner)

        awaitClose {
            bleScanner?.stopScan(callback)
        }
    }

    /**
     * Scope for operations as a GATT client role.
     *
     * @see BluetoothLe.connectGatt
     */
    interface GattClientScope {
        /**
         * A flow of GATT services discovered from the remote device.
         *
         * If the services of the remote device has changed, the new services will be
         * discovered and emitted automatically.
         */
        val servicesFlow: StateFlow<List<GattService>>

        /**
         * GATT services recently discovered from the remote device.
         *
         * Note that this can be changed, subscribe to [servicesFlow] to get notified
         * of services changes.
         */
        val services: List<GattService> get() = servicesFlow.value

        /**
         * Gets the service of the remote device by UUID.
         *
         * If multiple instances of the same service exist, the first instance of the services
         * is returned.
         */
        fun getService(uuid: UUID): GattService?

        /**
         * Reads the characteristic value from the server.
         *
         * @param characteristic a remote [GattCharacteristic] to read
         * @return the value of the characteristic
         */
        suspend fun readCharacteristic(characteristic: GattCharacteristic):
                Result<ByteArray>

        /**
         * Writes the characteristic value to the server.
         *
         * @param characteristic a remote [GattCharacteristic] to write
         * @param value a value to be written.
         * @return the result of the write operation
         */
        suspend fun writeCharacteristic(
            characteristic: GattCharacteristic,
            value: ByteArray
        ): Result<Unit>

        /**
         * Returns a _cold_ [Flow] that contains the indicated value of the given characteristic.
         */
        fun subscribeToCharacteristic(characteristic: GattCharacteristic): Flow<ByteArray>
    }

    /**
     * Connects to the GATT server on the remote Bluetooth device and
     * invokes the given [block] after the connection is made.
     *
     * The block may not be run if connection fails.
     *
     * @param device a [BluetoothDevice] to connect to
     * @param block a block of code that is invoked after the connection is made
     *
     * @throws CancellationException if connect failed or it's canceled
     * @return a result returned by the given block if the connection was successfully finished
     *         or a failure with the corresponding reason
     *
     */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    suspend fun <R> connectGatt(
        device: BluetoothDevice,
        phyMode: Int = android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK,
        block: suspend GattClientScope.(mtuSize: Int) -> R
    ): R {
        return client.connect(device, phyMode, block)
    }

    @SuppressLint("MissingPermission")
    suspend fun createBond(device: BluetoothDevice): Flow<Pair<Int, Int>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val bondState = intent.getIntExtra(EXTRA_BOND_STATE, BOND_NONE)
                val previousBondState = intent.getIntExtra(EXTRA_PREVIOUS_BOND_STATE, BOND_NONE)
                trySend(previousBondState to bondState)
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
        device.fwkDevice.createBond()

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    @VisibleForTesting
    fun interface OnStartScanListener {
        fun onStartScan(scanner: BluetoothLeScanner?)
    }
}
