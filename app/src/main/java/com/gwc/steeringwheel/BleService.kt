package com.gwc.steeringwheel

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.util.Log
import java.util.*

class BleService : Service() {

    private val TAG = "BleService"

    private val SERVICE_UUID: UUID =
        UUID.fromString("12345678-1234-1234-1234-1234567890ab")

    private val CHAR_UUID: UUID =
        UUID.fromString("abcd1234-5678-1234-5678-abcdef123456")

    private val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    private val handler: Handler = Handler(Looper.getMainLooper())

    private var reconnectAttempts = 0
    private val BASE_DELAY_MS = 1000L

    private var lastPacketTime: Long = 0
    private val CONNECTION_TIMEOUT = 3000L

    private var lastPot = -1

    private var lastButton1 = false
    private var lastButton2 = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startForeground(1, createNotification("Connecting..."))

        val address = intent?.getStringExtra("deviceAddress")
        if (address == null) {
            Log.e(TAG, "No device address")
            return START_NOT_STICKY
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        device = adapter.getRemoteDevice(address)

        connect()

        return START_STICKY
    }

    private fun connect() {

        val d = device ?: return

        Log.d(TAG, "Connecting to ${d.address}")

        gatt?.close()
        gatt = null

        reconnectAttempts = 0

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            d.connectGatt(this, false, gattCallback)
        }
    }

    private fun scheduleReconnect() {

        val delay = BASE_DELAY_MS * (reconnectAttempts + 1)

        handler.postDelayed({

            reconnectAttempts++
            Log.d(TAG, "Reconnect attempt $reconnectAttempts")

            connect()

        }, delay)
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {

            // 🔥 HANDLE 133 + OTHER ERRORS
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT error $status")

                g.close()
                gatt = null

                sendUpdate("Disconnected", "Error $status")

                scheduleReconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {

                Log.d(TAG, "BLE connected")

                reconnectAttempts = 0
                gatt = g

                lastPacketTime = System.currentTimeMillis()

                sendUpdate("Connected", "Discovering services")

                startWatchdog()

                g.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                Log.d(TAG, "BLE disconnected")

                sendUpdate("Disconnected", "Device lost")

                g.close()
                gatt = null

                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service not found")
                return
            }

            val characteristic = service.getCharacteristic(CHAR_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Characteristic not found")
                return
            }

            g.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(it)
            }

            sendUpdate("Connected", "Notifications enabled")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {

            lastPacketTime = System.currentTimeMillis()

            val data = characteristic.value ?: return
            if (data.size < 4) return

            val pot = (data[1].toInt() shl 8) or (data[0].toInt() and 0xFF)

            val button1 = data[2].toInt() == 1
            val button2 = data[3].toInt() == 1

            sendUpdate(
                "Connected",
                "Pot: $pot   B1: $button1   B2: $button2"
            )

            handleData(pot, button1, button2)
        }
    }

    private fun startWatchdog() {

        handler.post(object : Runnable {

            override fun run() {

                val now = System.currentTimeMillis()

                if (now - lastPacketTime > CONNECTION_TIMEOUT) {

                    Log.d(TAG, "Connection timeout")

                    sendUpdate("Disconnected", "No data")

                    gatt?.disconnect()
                    gatt?.close()
                    gatt = null

                    scheduleReconnect()

                    return
                }

                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun handleData(pot: Int, b1: Boolean, b2: Boolean) {

        // Pot change detection
        if (pot != lastPot) {
            lastPot = pot
            setCallVolumeFromPot(pot)
        }

        // ✅ FIXED: Proper push-to-talk (press + release)
        if (b1 && !lastButton1) {
            sendMacroDroidPTT(true)
        } else if (!b1 && lastButton1) {
            sendMacroDroidPTT(false)
        }

        // Button 2 edge detection
        if (b2 && !lastButton2) {
            onButton2Pressed()
        }

        lastButton1 = b1
        lastButton2 = b2
    }

    private fun setCallVolumeFromPot(pot: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)

        val buffer = 0.05
        val minPot = (4095 * buffer).toInt()
        val maxPot = (4095 * (1 - buffer)).toInt()

        val clampedPot = pot.coerceIn(minPot, maxPot)

        val scaled = ((clampedPot - minPot).toDouble() / (maxPot - minPot) * (maxVolume - 1)).toInt() + 1

        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, scaled, AudioManager.FLAG_SHOW_UI)

        Log.d("BLE", "Pot $pot (clamped $clampedPot) → Call Volume $scaled")
    }

    // 🔥 NEW PTT EVENT
    private fun sendMacroDroidPTT(pressed: Boolean) {
        val intent = Intent("com.gwc.steeringwheel.BUTTON1")
        intent.putExtra("pressed", pressed)
        sendBroadcast(intent)

        Log.d("BLE", "PTT pressed=$pressed")
    }

    private fun onButton2Pressed() {
        Log.d("BLE", "Button2 pressed")
        sendMacroDroidEvent("com.gwc.steeringwheel.BUTTON2")
    }

    private fun sendMacroDroidEvent(action: String, value: Int = 0) {
        val intent = Intent(action)
        intent.putExtra("value", value)
        sendBroadcast(intent)

        Log.d("BLE", "MacroDroid event: $action value=$value")
    }

    private fun sendUpdate(status: String, data: String) {

        val intent = Intent("BLE_UPDATE")
        intent.setPackage(packageName)

        intent.putExtra("status", status)
        intent.putExtra("data", data)

        sendBroadcast(intent)

        startForeground(1, createNotification(status))
    }

    private fun createNotification(text: String): Notification {

        val channelId = "ble_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "BLE Service",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle("Steering Wheel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onBind(intent: Intent?) = null
}