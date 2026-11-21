package com.autodeck.headunit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autodeck.headunit.service.HeadUnitForegroundService
import com.autodeck.headunit.ui.HeadUnitViewModel
import com.autodeck.headunit.ui.screens.HeadUnitScreen
import com.autodeck.headunit.ui.screens.SetupWizardScreen
import com.autodeck.headunit.ui.theme.AutoDeckTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HeadUnitViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionsUpdated()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HeadUnitForegroundService.start(this)
        applyImmersiveFullscreen()
        requestRuntimePermissionsIfNeeded()

        setContent {
            AutoDeckTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                // Libera a tela para apagar quando o pós-chave / carregamento USB-C desconectar
                LaunchedEffect(uiState.headUnit.powerConnected) {
                    if (uiState.headUnit.powerConnected) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                if (!uiState.config.setupCompleted) {
                    SetupWizardScreen(
                        uiState = uiState,
                        onRefreshBluetooth = { viewModel.onPermissionsUpdated() },
                        onRequestPermissions = { requestRuntimePermissionsIfNeeded() },
                        onRefreshUsb = { viewModel.refreshUsbDevices() },
                        onCalibrateNoSignal = { viewModel.calibrateCameraNoSignal() },
                        onToggleLauncherMode = { enabled -> viewModel.setLauncherModeEnabled(enabled) },
                        onFinishSetup = { updated -> viewModel.completeSetup(updated) }
                    )
                } else {
                    HeadUnitScreen(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppForeground(true)
    }

    override fun onStop() {
        viewModel.setAppForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.refreshUsbDevices()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }

    private fun applyImmersiveFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            missing += Manifest.permission.CAMERA
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
