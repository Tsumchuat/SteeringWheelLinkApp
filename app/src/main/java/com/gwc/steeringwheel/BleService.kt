package com.gwc.steeringwheel

import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.os.*
import android.util.Log
import java.util.*

class BleService : Service() {

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHAR_UUID = UUID.fromString("abcd1234-5678-1234-5678-abcdef123456")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startForeground(1, createNotification("Connecting..."))

        val address = intent?.getStringExtra("deviceAddress") ?: return START_NOT_STICKY
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)

        gatt = device.connectGatt(this, false, gattCallback)

        return START_STICKY
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sendUpdate("Connected", "")
                g.discoverServices()
            } else {
                sendUpdate("Disconnected", "")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: return

            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value
            if (data.size < 4) return

            val pot = (data[1].toInt() shl 8) or (data[0].toInt() and 0xFF)
            val button1 = data[2].toInt() == 1
            val button2 = data[3].toInt() == 1

            sendUpdate("Connected", "Pot: $pot  B1: $button1  B2: $button2")

            handleData(pot, button1, button2)
        }
    }

    private var lastPot = -1

    private fun handleData(pot: Int, b1: Boolean, b2: Boolean) {

        if (pot != lastPot) {
            lastPot = pot
            onPotChanged(pot)
        }

        if (b1) onButton1Pressed()
        if (b2) onButton2Pressed()
    }

    private fun onPotChanged(value: Int) {
        Log.d("BLE", "Pot changed: $value")
    }

    private fun onButton1Pressed() {
        Log.d("BLE", "Button 1 pressed")
    }

    private fun onButton2Pressed() {
        Log.d("BLE", "Button 2 pressed")
    }

    private fun sendUpdate(status: String, data: String) {
        val intent = Intent("BLE_UPDATE")
        intent.setPackage(packageName)   // 🔥 CRITICAL FIX
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
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle("BLE Connected")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onBind(intent: Intent?) = null
}