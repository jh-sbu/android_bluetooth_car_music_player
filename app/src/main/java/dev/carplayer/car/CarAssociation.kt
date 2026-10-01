package dev.carplayer.car

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import dev.carplayer.appGraph
import java.util.concurrent.Executor

data class PairedDevice(val address: String, val name: String, val looksLikeCar: Boolean)

/**
 * Registers the car with CompanionDeviceManager. The association is what gets this app woken
 * up (CarPresenceService) when the car connects, and exempts it from background
 * foreground-service start restrictions.
 */
class CarAssociation(private val context: Context) {

    private val cdm = context.getSystemService(CompanionDeviceManager::class.java)
    private val settings = context.appGraph.carSettings
    private val log = context.appGraph.eventLog

    /**
     * Devices already paired in system Bluetooth settings, car-looking ones first. Empty without
     * the BLUETOOTH_CONNECT permission.
     */
    fun pairedDevices(): List<PairedDevice> {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { PairedDevice(it.address, it.name ?: it.address, it.looksLikeCar()) }
            .sortedWith(compareByDescending<PairedDevice> { it.looksLikeCar }.thenBy { it.name.lowercase() })
    }

    /**
     * Starts association with the paired device at [address]. The system shows a confirmation
     * dialog through [onPending]'s IntentSender, which the caller must launch.
     */
    fun associate(
        address: String,
        executor: Executor,
        onPending: (IntentSender) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        cdm.associate(request, executor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                onAssociated(associationInfo)
            }

            override fun onFailure(error: CharSequence?) {
                log.log("Car association failed: $error")
                onFailure(error?.toString() ?: "Association failed")
            }
        })
    }

    fun onAssociated(info: AssociationInfo) {
        val address = info.deviceMacAddress?.toString()?.uppercase() ?: run {
            log.log("Association ${info.id} has no Bluetooth address; ignoring")
            return
        }
        settings.setCar(address, info.displayName?.toString(), info.id)
        log.log("Associated with car ${info.displayName ?: address} (association ${info.id})")
        observe()
    }

    /** (Re)registers presence observation. Idempotent; called at app start as well. */
    fun observe() {
        val config = settings.get()
        val id = config.associationId ?: return
        if (cdm.myAssociations.none { it.id == id }) {
            // Removed from system settings ("Companion apps" / "Device & app notifications").
            log.log("Car association $id no longer exists; forgetting car")
            settings.clearCar()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(config.address!!)
            }
        } catch (e: RuntimeException) {
            log.log("Couldn't observe car presence: $e")
        }
    }

    fun forget() {
        val config = settings.get()
        config.associationId?.let { id ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
                } else {
                    @Suppress("DEPRECATION")
                    cdm.stopObservingDevicePresence(config.address!!)
                }
            }
            runCatching { cdm.disassociate(id) }
        }
        settings.clearCar()
        log.log("Forgot car ${config.name ?: config.address}")
    }

    @SuppressLint("MissingPermission") // Only called from pairedDevices(), after its check.
    private fun BluetoothDevice.looksLikeCar(): Boolean {
        val cls = bluetoothClass?.deviceClass
        // A 2013 Accord's head unit advertises itself as "HandsFreeLink".
        return cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
            cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
            name?.contains("HandsFreeLink", ignoreCase = true) == true
    }
}
