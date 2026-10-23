package com.rafa.gw4bridge

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the BLE connection to the iPhone 15 Pro, subscribes to ANCS Data Source & Notification Source,
 * queues GATT operations sequentially, and dispatches notification events and Control Point actions.
 */
@SuppressLint("MissingPermission")
class BleAncsManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(statusText: String, connected: Boolean, deviceName: String?)
        fun onNotificationReceived(deviceAddress: String, notification: AncsProtocol.ParsedNotification)
        fun onNotificationRemoved(uid: Int)
    }

    companion object {
        private const val TAG = "GW4BridgeBLE"
        // Standard Bluetooth SIG Health / Fitness Services (100% offline BLE to iOS Apple Health)
        val SERVICE_HEART_RATE: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        val CHAR_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        val CHAR_BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002A38-0000-1000-8000-00805f9b34fb")
        val CHAR_HEART_RATE_CONTROL_POINT: UUID = UUID.fromString("00002A39-0000-1000-8000-00805f9b34fb")

        val SERVICE_DEVICE_INFO: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        val CHAR_MANUFACTURER_NAME: UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb")
        val CHAR_MODEL_NUMBER: UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")

        val SERVICE_RSC: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        val CHAR_RSC_MEASUREMENT: UUID = UUID.fromString("00002A53-0000-1000-8000-00805f9b34fb")
        val CHAR_RSC_FEATURE: UUID = UUID.fromString("00002A54-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeGatt: BluetoothGatt? = null
    private var activeDevice: BluetoothDevice? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private var gattServer: BluetoothGattServer? = null
    private var hrMeasurementChar: BluetoothGattCharacteristic? = null
    private var rscMeasurementChar: BluetoothGattCharacteristic? = null
    private val subscribedServerDevices = mutableSetOf<BluetoothDevice>()
    private var lastHrBpm: Int = 72
    private var lastActiveKcal: Double = 0.0
    private var lastSteps: Int = 0
    private var lastDistanceKm: Double = 0.0
    private var lastIsWorkout: Boolean = false

    private val bleHealthTickRunnable = object : Runnable {
        override fun run() {
            pushBleHealthNotifications()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val pendingNotifications = ConcurrentHashMap<Int, AncsProtocol.ParsedNotification>()
    private val dataSourceAssembler = AncsProtocol.DataSourceAssembler()

    private sealed class GattOp {
        data class EnableNotification(val serviceUuid: UUID, val charUuid: UUID, val attempt: Int = 0) : GattOp()
        data class WriteCharacteristic(val serviceUuid: UUID, val charUuid: UUID, val payload: ByteArray, val attempt: Int = 0) : GattOp()
    }

    private val opQueue: Queue<GattOp> = LinkedList()
    private var currentOp: GattOp? = null
    private var isOpInFlight = false

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    Log.d(TAG, "Bond state changed: ${device?.name} -> $bondState")
                    if (bondState == BluetoothDevice.BOND_BONDED && device != null) {
                        connectToDevice(device)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        connectToBondedIphone()
                    }
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            context.registerReceiver(bondStateReceiver, filter)
        } catch (_: Exception) {}

        startHealthGattServer()
        connectToBondedIphone()
        startBleAdvertising()
    }

    fun stop() {
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (_: Exception) {}
        stopBleAdvertising()
        stopHealthGattServer()
        disconnect()
    }

    /**
     * Exposes standard Bluetooth SIG Heart Rate (0x180D with Energy Expended in kJ)
     * and Running Speed & Cadence (0x1814 with Total Distance in meters) GATT Services
     * so the iPhone 15 Pro (Apple Health / Fitness apps) can read & receive live
     * Heart Rate, Calories, Steps/Cadence, and Distance over 100% Bluetooth without Wi-Fi!
     */
    private fun startHealthGattServer() {
        if (gattServer != null) return
        try {
            val server = bluetoothManager.openGattServer(context, gattServerCallback) ?: return
            gattServer = server

            // 1. Heart Rate Service (0x180D) -> BPM + Energy Expended (Calories/kJ)
            val hrService = BluetoothGattService(SERVICE_HEART_RATE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val hrChar = BluetoothGattCharacteristic(
                CHAR_HEART_RATE_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            hrChar.addDescriptor(
                BluetoothGattDescriptor(
                    AncsProtocol.DESCRIPTOR_CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            val sensorLocChar = BluetoothGattCharacteristic(
                CHAR_BODY_SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                value = byteArrayOf(0x02) // 2 = Wrist (Pulso)
            }
            val hrCpChar = BluetoothGattCharacteristic(
                CHAR_HEART_RATE_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            hrService.addCharacteristic(hrChar)
            hrService.addCharacteristic(sensorLocChar)
            hrService.addCharacteristic(hrCpChar)
            hrMeasurementChar = hrChar
            server.addService(hrService)

            // 2. Device Information Service (0x180A) required by iOS Health Sensors scanner
            mainHandler.postDelayed({
                try {
                    val disService = BluetoothGattService(SERVICE_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                    val mfgChar = BluetoothGattCharacteristic(
                        CHAR_MANUFACTURER_NAME,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ
                    ).apply {
                        value = "Samsung".toByteArray(Charsets.UTF_8)
                    }
                    val modelChar = BluetoothGattCharacteristic(
                        CHAR_MODEL_NUMBER,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ
                    ).apply {
                        value = "Galaxy Watch4".toByteArray(Charsets.UTF_8)
                    }
                    disService.addCharacteristic(mfgChar)
                    disService.addCharacteristic(modelChar)
                    server.addService(disService)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed adding DIS service: ${e.message}")
                }
            }, 250)

            // 3. Running Speed & Cadence Service (0x1814) -> Cadence (steps/min) + Total Distance (m)
            mainHandler.postDelayed({
                try {
                    val rscService = BluetoothGattService(SERVICE_RSC, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                    val rscChar = BluetoothGattCharacteristic(
                        CHAR_RSC_MEASUREMENT,
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ
                    )
                    rscChar.addDescriptor(
                        BluetoothGattDescriptor(
                            AncsProtocol.DESCRIPTOR_CCCD,
                            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                        )
                    )
                    val rscFeat = BluetoothGattCharacteristic(
                        CHAR_RSC_FEATURE,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ
                    ).apply {
                        value = byteArrayOf(0x02, 0x00) // Total Distance Measurement Supported
                    }
                    rscService.addCharacteristic(rscChar)
                    rscService.addCharacteristic(rscFeat)
                    rscMeasurementChar = rscChar
                    server.addService(rscService)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed adding RSC service: ${e.message}")
                }
            }, 550)

            mainHandler.removeCallbacks(bleHealthTickRunnable)
            mainHandler.postDelayed(bleHealthTickRunnable, 1000L)
            Log.d(TAG, "Offline BLE Health GATT Server (0x180D + 0x180A + 0x1814) started")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start BLE Health GATT Server: ${e.message}")
        }
    }

    private fun stopHealthGattServer() {
        mainHandler.removeCallbacks(bleHealthTickRunnable)
        try {
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null
        subscribedServerDevices.clear()
    }

    fun broadcastHealthOverBle(heartRateBpm: Int, activeKcal: Double, stepsToday: Int, distanceKm: Double, isWorkout: Boolean) {
        lastHrBpm = heartRateBpm
        lastActiveKcal = activeKcal
        lastSteps = stepsToday
        lastDistanceKm = distanceKm
        lastIsWorkout = isWorkout
        pushBleHealthNotifications()
    }

    private fun pushBleHealthNotifications() {
        val server = gattServer ?: return
        val hrChar = hrMeasurementChar ?: return

        // Convert active kcal to kiloJoules (1 kcal = 4.184 kJ), clamped to uint16
        val energyKj = (lastActiveKcal * 4.184).toInt().coerceIn(0, 65535)
        // Flags: bit 0 = 0 (UINT8 BPM), bit 1-2 = 11 (sensor contact detected), bit 3 = 1 (Energy Expended present) -> 0x0E
        val hrPayload = byteArrayOf(
            0x0E.toByte(),
            (lastHrBpm.coerceIn(35, 240) and 0xFF).toByte(),
            (energyKj and 0xFF).toByte(),
            ((energyKj shr 8) and 0xFF).toByte()
        )
        hrChar.value = hrPayload

        // RSC Measurement (0x2A53):
        // Flags: bit 1 = 1 (Total Distance present), bit 2 = isWorkout (Walking=0, Running=1)
        val rscFlags = (0x02 or (if (lastIsWorkout) 0x04 else 0x00)).toByte()
        val speedRaw = if (lastIsWorkout) (2.6 * 256).toInt() else (1.1 * 256).toInt()
        val cadence = if (lastIsWorkout) 145 else 90
        val totalDistanceDm = (lastDistanceKm * 10000.0).toLong().coerceAtLeast(0L)
        val rscPayload = byteArrayOf(
            rscFlags,
            (speedRaw and 0xFF).toByte(),
            ((speedRaw shr 8) and 0xFF).toByte(),
            (cadence and 0xFF).toByte(),
            (totalDistanceDm and 0xFF).toByte(),
            ((totalDistanceDm shr 8) and 0xFF).toByte(),
            ((totalDistanceDm shr 16) and 0xFF).toByte(),
            ((totalDistanceDm shr 24) and 0xFF).toByte()
        )
        rscMeasurementChar?.value = rscPayload

        val targets = synchronized(subscribedServerDevices) {
            if (subscribedServerDevices.isNotEmpty()) subscribedServerDevices.toList()
            else activeDevice?.let { listOf(it) } ?: emptyList()
        }
        for (dev in targets) {
            try {
                server.notifyCharacteristicChanged(dev, hrChar, false)
                rscMeasurementChar?.let { server.notifyCharacteristicChanged(dev, it, false) }
            } catch (_: Exception) {}
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            synchronized(subscribedServerDevices) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    subscribedServerDevices.add(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribedServerDevices.remove(device)
                } else {}
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val valBytes = characteristic?.value ?: byteArrayOf(0)
            val slice = if (offset < valBytes.size) valBytes.copyOfRange(offset, valBytes.size) else byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (device != null) {
                synchronized(subscribedServerDevices) {
                    subscribedServerDevices.add(device)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    fun getBondedDevicesList(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun connectToBondedIphone() {
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        if (bonded.isEmpty()) {
            listener.onConnectionStateChanged("Aguardando pareamento Bluetooth com iPhone...", false, null)
            return
        }
        // Prefer a device with "iPhone" in its name, otherwise pick the first bonded device
        val target = bonded.firstOrNull { it.name?.contains("iPhone", ignoreCase = true) == true }
            ?: bonded.first()
        connectToDevice(target)
    }

    fun connectToDevice(device: BluetoothDevice) {
        if (activeGatt != null && activeDevice?.address == device.address) {
            Log.d(TAG, "Already connected/connecting to ${device.name}")
            return
        }
        disconnect()
        activeDevice = device
        listener.onConnectionStateChanged("Conectando ao ${device.name ?: device.address}...", false, device.name)
        Log.d(TAG, "Connecting GATT (TRANSPORT_LE) to ${device.name} (${device.address})")
        // Matches Merge's my2.k(): connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
        activeGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK
        )
    }

    fun disconnect() {
        synchronized(opQueue) {
            opQueue.clear()
            isOpInFlight = false
        }
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
    }

    fun performNotificationAction(uid: Int, positive: Boolean) {
        val actionId = if (positive) AncsProtocol.ACTION_POSITIVE else AncsProtocol.ACTION_NEGATIVE
        val payload = AncsProtocol.buildPerformNotificationActionCommand(uid, actionId)
        enqueueOp(
            GattOp.WriteCharacteristic(
                AncsProtocol.SERVICE_ANCS,
                AncsProtocol.CHAR_CONTROL_POINT,
                payload
            )
        )
    }

    private fun startBleAdvertising() {
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        if (isAdvertising) return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Main advertising packet (<= 31 bytes): Flags (3B) + two 16-bit Service UUIDs (6B) = 9 bytes!
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_HEART_RATE))
            .addServiceUuid(ParcelUuid(SERVICE_RSC))
            .build()

        // Scan response packet (<= 31 bytes): Device Name ("Galaxy Watch4 ...")
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
            isAdvertising = true
        } catch (e: Exception) {
            Log.w(TAG, "BLE advertising start failed: ${e.message}")
        }
    }

    private fun stopBleAdvertising() {
        if (!isAdvertising) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
        isAdvertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertising active for iOS discovery")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertising failed: $errorCode")
        }
    }

    private fun enqueueOp(op: GattOp) {
        synchronized(opQueue) {
            opQueue.add(op)
            if (!isOpInFlight) {
                processNextOp()
            }
        }
    }

    private fun completeCurrentOp() {
        synchronized(opQueue) {
            currentOp = null
            isOpInFlight = false
            processNextOp()
        }
    }

    private fun retryOrCompleteCurrentOp(status: Int) {
        synchronized(opQueue) {
            val op = currentOp
            currentOp = null
            isOpInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS && op != null) {
                val retried: GattOp? = when (op) {
                    is GattOp.EnableNotification -> if (op.attempt < 3) op.copy(attempt = op.attempt + 1) else null
                    is GattOp.WriteCharacteristic -> if (op.attempt < 2) op.copy(attempt = op.attempt + 1) else null
                }
                if (retried != null) {
                    Log.w(TAG, "Retrying GATT op after status=$status: $retried")
                    mainHandler.postDelayed({
                        enqueueOp(retried)
                    }, 900)
                    processNextOp()
                    return
                }
            }
            processNextOp()
        }
    }

    private fun processNextOp() {
        val gatt = activeGatt ?: return
        val next = opQueue.poll() ?: return
        currentOp = next
        isOpInFlight = true

        when (next) {
            is GattOp.EnableNotification -> {
                val service = gatt.getService(next.serviceUuid)
                val characteristic = service?.getCharacteristic(next.charUuid)
                if (characteristic == null) {
                    Log.w(TAG, "Characteristic ${next.charUuid} not found")
                    completeCurrentOp()
                    return
                }
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(AncsProtocol.DESCRIPTOR_CCCD)
                if (descriptor != null) {
                    val value = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    descriptor.value = value
                    val ok = gatt.writeDescriptor(descriptor)
                    Log.d(TAG, "Subscribing CCCD for ${next.charUuid}: $ok")
                    if (!ok) completeCurrentOp()
                } else {
                    completeCurrentOp()
                }
            }
            is GattOp.WriteCharacteristic -> {
                val service = gatt.getService(next.serviceUuid)
                val characteristic = service?.getCharacteristic(next.charUuid)
                if (characteristic == null) {
                    Log.w(TAG, "Write characteristic ${next.charUuid} not found")
                    completeCurrentOp()
                    return
                }
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = next.payload
                val ok = gatt.writeCharacteristic(characteristic)
                Log.d(TAG, "Writing ${next.payload.size} bytes to ${next.charUuid}: $ok")
                if (!ok) completeCurrentOp()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val devName = gatt.device?.name ?: gatt.device?.address ?: "iPhone"
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState device=$devName")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onConnectionStateChanged("Conectado ao $devName (Descobrindo ANCS...)", true, devName)
                val mtuRequested = gatt.requestMtu(185)
                if (!mtuRequested) {
                    try {
                        gatt.discoverServices()
                    } catch (_: Exception) {}
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onConnectionStateChanged("Desconectado de $devName (Reconectando...)", false, devName)
                synchronized(opQueue) {
                    opQueue.clear()
                    currentOp = null
                    isOpInFlight = false
                }
                try {
                    gatt.close()
                } catch (_: Exception) {}
                if (activeGatt == gatt) {
                    activeGatt = null
                }
                // Auto-reconnect after 5 seconds
                mainHandler.postDelayed({
                    activeDevice?.let { connectToDevice(it) }
                }, 5000)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
            try {
                gatt.discoverServices()
            } catch (_: Exception) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val devName = gatt.device?.name ?: "iPhone"
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val ancsService = gatt.getService(AncsProtocol.SERVICE_ANCS)
            if (ancsService == null) {
                Log.w(TAG, "ANCS Service not found yet on $devName; triggering bonding if needed")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    gatt.device.createBond()
                }
                listener.onConnectionStateChanged("Conectado a $devName (Aceite o pareamento no iPhone)", true, devName)
                return
            }

            // CRITICAL ORDER: Always subscribe to Data Source BEFORE Notification Source on iOS ANCS!
            enqueueOp(
                GattOp.EnableNotification(
                    AncsProtocol.SERVICE_GENERIC_ATTRIBUTE,
                     UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
                )
            )
            enqueueOp(
                GattOp.EnableNotification(
                    AncsProtocol.SERVICE_ANCS,
                    AncsProtocol.CHAR_DATA_SOURCE
                )
            )
            enqueueOp(
                GattOp.EnableNotification(
                    AncsProtocol.SERVICE_ANCS,
                    AncsProtocol.CHAR_NOTIFICATION_SOURCE
                )
            )
            listener.onConnectionStateChanged("Ativo: ANCS + Chamadas ($devName)", true, devName)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite char=${descriptor.characteristic.uuid} status=$status")
            retryOrCompleteCurrentOp(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite char=${characteristic.uuid} status=$status")
            retryOrCompleteCurrentOp(status)
        }

        @Deprecated("Deprecated in API 33, but used on Wear OS API 30-34")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            handleCharacteristicNotification(gatt, characteristic.uuid, bytes)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicNotification(gatt, characteristic.uuid, value)
        }
    }

    private fun handleCharacteristicNotification(
        gatt: BluetoothGatt,
        charUuid: UUID,
        bytes: ByteArray
    ) {
        when (charUuid) {
            AncsProtocol.CHAR_NOTIFICATION_SOURCE -> {
                val event = AncsProtocol.parseNotificationSource(bytes) ?: return
                Log.d(TAG, "ANCS NotificationSource event=$event")
                when (event.eventId) {
                    AncsProtocol.EVENT_REMOVED -> {
                        pendingNotifications.remove(event.notificationUid)
                        listener.onNotificationRemoved(event.notificationUid)
                    }
                    AncsProtocol.EVENT_ADDED, AncsProtocol.EVENT_MODIFIED -> {
                        // Skip old pre-existing notifications flood on initial connection unless it's an active call
                        if (event.isPreExisting && !event.isIncomingCall) {
                            return
                        }
                        val model = AncsProtocol.ParsedNotification(
                            uid = event.notificationUid,
                            eventId = event.eventId,
                            categoryId = event.categoryId,
                            eventFlags = event.eventFlags
                        )
                        pendingNotifications[event.notificationUid] = model
                        val cmd = AncsProtocol.buildGetNotificationAttributesCommand(event.notificationUid)
                        enqueueOp(
                            GattOp.WriteCharacteristic(
                                AncsProtocol.SERVICE_ANCS,
                                AncsProtocol.CHAR_CONTROL_POINT,
                                cmd
                            )
                        )
                    }
                }
            }
            AncsProtocol.CHAR_DATA_SOURCE -> {
                val completed = dataSourceAssembler.feedChunk(bytes, pendingNotifications)
                if (completed != null) {
                    Log.d(TAG, "ANCS Notification parsed: [${completed.friendlyAppName()}] ${completed.title}: ${completed.message}")
                    val addr = gatt.device?.address ?: "iphone"
                    listener.onNotificationReceived(addr, completed)
                }
            }
        }
    }
}
