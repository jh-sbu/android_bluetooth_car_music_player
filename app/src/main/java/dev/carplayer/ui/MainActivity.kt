package dev.carplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.carplayer.car.CarAssociation
import dev.carplayer.car.PairedDevice

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private val association by lazy { CarAssociation(this) }

    private var hasMusicPermission by mutableStateOf(false)
    private var pairedDevices by mutableStateOf<List<PairedDevice>?>(null)

    private val requestMusicPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissions() }

    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showCarPicker() else toast("Bluetooth permission is needed to list paired devices")
        }

    private val confirmAssociation =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) toast("Car setup cancelled")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        association.observe()

        setContent {
            CarPlayerTheme {
                MainScreen(
                    viewModel = viewModel,
                    hasMusicPermission = hasMusicPermission,
                    onRequestMusicPermission = {
                        requestMusicPermissions.launch(
                            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
                        )
                    },
                    onChooseCar = ::chooseCar,
                    onForgetCar = association::forget,
                    pairedDevices = pairedDevices,
                    onCarPicked = ::associate,
                    onDismissPicker = { pairedDevices = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val granted = granted(Manifest.permission.READ_MEDIA_AUDIO)
        if (granted && !hasMusicPermission) viewModel.loadLibrary(refresh = true)
        hasMusicPermission = granted
    }

    private fun chooseCar() {
        if (granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            showCarPicker()
        } else {
            requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun showCarPicker() {
        pairedDevices = association.pairedDevices()
    }

    private fun associate(device: PairedDevice) {
        pairedDevices = null
        association.associate(
            address = device.address,
            executor = ContextCompat.getMainExecutor(this),
            onPending = { confirmAssociation.launch(IntentSenderRequest.Builder(it).build()) },
            onFailure = ::toast,
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
