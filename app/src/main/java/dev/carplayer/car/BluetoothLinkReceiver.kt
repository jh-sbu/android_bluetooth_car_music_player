package dev.carplayer.car

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.carplayer.appGraph

/**
 * Diagnostics only: records raw Bluetooth link events for the configured car in the event log,
 * so if auto-play doesn't happen you can tell whether the phone saw the car at all versus
 * CompanionDeviceManager not waking the app. (ACL broadcasts are exempt from the implicit
 * broadcast ban, so this manifest receiver fires even when the app isn't running.)
 */
class BluetoothLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val car = context.appGraph.carSettings.get()
        if (!device.address.equals(car.address, ignoreCase = true)) return
        val what = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
            else -> return
        }
        context.appGraph.eventLog.log("Bluetooth link to car $what")
    }
}
