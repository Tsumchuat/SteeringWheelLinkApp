package com.gwc.steeringwheel

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.util.Log
import java.util.UUID

class BleService : Service() {

    private val SERVICE_UUID = UUID.fromString(
        "12345678-1234-1234-1234-1234567890ab"
    )

    private val CHAR_UUID = UUID.fromString(
        "abcd1234-5678-1234-5678-abcdef123456"
    )

    private val CCCD_UUID = UUID.fromString(
        "00002902-0000-1000-8000-00805f9b34fb"
    )

    private var gatt: BluetoothGatt? = null

    private var deviceAddress: String? = null
    private var deviceName: String = "Unknown Device"

    private var reconnecting = false
    private var connected = false

    private val handler = Handler(Looper.getMainLooper())

    /*
     * Reconnect every 5 seconds after a disconnect.
     */
    private val reconnectRunnable = object : Runnable {

        override fun run() {

            if (connected) {
                reconnecting = false
                return
            }

            val address = deviceAddress

            if (address == null) {
                reconnecting = false
                return
            }
            sendUpdate(
                "Reconnecting to $deviceName...",
                ""
            )

            Log.d(
                "BLE",
                "Attempting reconnect to $address"
            )

            connectToDevice(address)

            /*
             * Schedule the next attempt.
             *
             * This keeps retrying every 5 seconds until
             * the connection succeeds.
             */
            if (!connected) {
                handler.postDelayed(
                    this,
                    5000L
                )
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForeground(
            1,
            createNotification("Connecting...")
        )

        val address =
            intent?.getStringExtra("deviceAddress")

        val name =
            intent?.getStringExtra("deviceName")

        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        deviceAddress = address
        deviceName = name ?: "Unknown Device"

        deviceAddress = address

        /*
         * Cancel any previous reconnect cycle.
         */
        handler.removeCallbacks(reconnectRunnable)
        reconnecting = false

        connectToDevice(address)

        return START_STICKY
    }

    private fun connectToDevice(address: String) {

        /*
         * Close the previous GATT connection before
         * attempting another one.
         */
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null

        try {

            val adapter =
                BluetoothAdapter.getDefaultAdapter()

            val device =
                adapter.getRemoteDevice(address)

            Log.d(
                "BLE",
                "Connecting to $deviceName (${device.address})"
            )

            sendUpdate(
                "Connecting to $deviceName...",
                ""
            )

            gatt = device.connectGatt(
                this,
                false,
                gattCallback
            )

        } catch (e: Exception) {

            Log.e(
                "BLE",
                "Connection failed",
                e
            )
        }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                if (
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    connected = true
                    reconnecting = false

                    /*
                     * Stop the reconnect timer.
                     */
                    handler.removeCallbacks(
                        reconnectRunnable
                    )

                    sendUpdate(
                        "Connected to $deviceName",
                        ""
                    )

                    Log.d(
                        "BLE",
                        "Connected to $deviceName (${g.device.address})"
                    )

                    g.discoverServices()

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    connected = false

                    sendUpdate(
                        "Disconnected from $deviceName",
                        "Reconnecting in 5 seconds..."
                    )
                    Log.d(
                        "BLE",
                        "Disconnected"
                    )

                    /*
                     * Clean up this GATT instance.
                     */
                    try {
                        g.close()
                    } catch (_: Exception) {
                    }

                    if (!reconnecting) {

                        reconnecting = true

                        /*
                         * First reconnect attempt happens
                         * after 5 seconds.
                         */
                        handler.postDelayed(
                            reconnectRunnable,
                            5000L
                        )
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    sendUpdate(
                        "Service discovery failed",
                        ""
                    )

                    return
                }

                val service =
                    g.getService(SERVICE_UUID)

                if (service == null) {

                    sendUpdate(
                        "BLE service not found",
                        ""
                    )

                    return
                }

                val characteristic =
                    service.getCharacteristic(CHAR_UUID)

                if (characteristic == null) {

                    sendUpdate(
                        "BLE characteristic not found",
                        ""
                    )

                    return
                }

                g.setCharacteristicNotification(
                    characteristic,
                    true
                )

                val descriptor =
                    characteristic.getDescriptor(
                        CCCD_UUID
                    )

                if (descriptor == null) {

                    sendUpdate(
                        "Notification descriptor not found",
                        ""
                    )

                    return
                }

                descriptor.value =
                    BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE

                g.writeDescriptor(descriptor)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {

                val data = characteristic.value

                if (data.size < 4)
                    return

                val pot =
                    (data[1].toInt() shl 8) or
                            (data[0].toInt() and 0xFF)

                val button1 =
                    data[2].toInt() == 1

                val button2 =
                    data[3].toInt() == 1

                sendUpdate(
                    "Connected",
                    "Pot: $pot  B1: $button1  B2: $button2"
                )

                handleData(
                    pot,
                    button1,
                    button2
                )
            }
        }

    private var lastPot = -1
    private var lastButton1 = false
    private var lastButton2 = false

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


    private fun sendUpdate(
        status: String,
        data: String
    ) {

        val intent =
            Intent("BLE_UPDATE")

        intent.setPackage(packageName)

        intent.putExtra(
            "status",
            status
        )

        intent.putExtra(
            "data",
            data
        )

        sendBroadcast(intent)

        startForeground(
            1,
            createNotification("Connecting to $deviceName...")
        )
    }

    private fun createNotification(
        text: String
    ): Notification {

        val channelId = "ble_channel"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    channelId,
                    "BLE Service",
                    NotificationManager.IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }

        return Notification.Builder(
            this,
            channelId
        )
            .setContentTitle("BLE Connected")
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.stat_sys_data_bluetooth
            )
            .build()
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null
        connected = false
        reconnecting = false

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}