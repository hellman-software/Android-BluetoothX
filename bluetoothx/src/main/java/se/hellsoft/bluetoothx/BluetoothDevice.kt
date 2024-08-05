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
import android.bluetooth.BluetoothDevice as FwkDevice
import androidx.annotation.RequiresPermission
import androidx.annotation.RestrictTo
import java.util.UUID

/**
 * Represents a remote Bluetooth device. A BluetoothDevice lets you query information about it, such
 * as the name and bond state of the device.
 *
 * @property id the unique id for this BluetoothDevice
 * @property name the name for this BluetoothDevice
 * @property bondState the bondState for this BluetoothDevice
 *
 */
class BluetoothDevice internal constructor(
    internal val fwkDevice: FwkDevice
) {
    val id: UUID = UUID.randomUUID()

    @get:RequiresPermission(
        anyOf = ["android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_CONNECT"]
    )
    val name: String?
        get() = fwkDevice.name

    @get:RequiresPermission(
        anyOf = ["android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_CONNECT"]
    )
    val bondState: Int
        get() = fwkDevice.bondState
}
