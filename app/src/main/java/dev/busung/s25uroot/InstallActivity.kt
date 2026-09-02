package dev.busung.s25uroot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.busung.s25uroot.ui.theme.RootMyGalaxyTheme

class InstallActivity : ComponentActivity() {
    private val installViewModel by viewModels<InstallViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val startInstall = savedInstanceState == null && AppPreferences.consumeInstallRequest(
            this,
            intent.getStringExtra(EXTRA_INSTALL_REQUEST_ID),
        )
        intent.removeExtra(EXTRA_INSTALL_REQUEST_ID)
        setContent {
            RootMyGalaxyTheme(
                accentColor = AppPreferences.accentColor(this),
                themeMode = AppPreferences.themeMode(this),
            ) {
                val installState by installViewModel.state.collectAsStateWithLifecycle()
                val history by installViewModel.history.collectAsStateWithLifecycle()
                var autoRootEnabled by remember {
                    mutableStateOf(AppPreferences.autoRootEnabled(this@InstallActivity))
                }
                var softRebootEnabled by remember {
                    mutableStateOf(AppPreferences.softRebootAfterRoot(this@InstallActivity))
                }
                var softRebootTriggered by rememberSaveable { mutableStateOf(false) }
                var installSessionStartedAt by rememberSaveable {
                    mutableStateOf(if (startInstall) System.currentTimeMillis() else 0L)
                }
                val batteryOptimizationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    val exempt = getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(packageName)
                    AppPreferences.setAutoRootEnabled(this@InstallActivity, exempt)
                    autoRootEnabled = exempt
                    if (!exempt) {
                        Toast.makeText(
                            this@InstallActivity,
                            getString(R.string.autoroot_battery_optimization_required),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                val requestBatteryOptimizationExemption: () -> Unit = {
                    val powerManager = getSystemService(PowerManager::class.java)
                    if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                        AppPreferences.setAutoRootEnabled(this@InstallActivity, true)
                        autoRootEnabled = true
                    } else {
                        val packageUri = Uri.parse("package:$packageName")
                        runCatching {
                            batteryOptimizationLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                            )
                        }.onFailure {
                            batteryOptimizationLauncher.launch(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        requestBatteryOptimizationExemption()
                    } else {
                        AppPreferences.setAutoRootEnabled(this@InstallActivity, false)
                        autoRootEnabled = false
                        Toast.makeText(
                            this@InstallActivity,
                            getString(R.string.autoroot_notification_permission),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                val setAutoRoot: (Boolean) -> Unit = { enabled ->
                    if (!enabled) {
                        AppPreferences.setAutoRootEnabled(this@InstallActivity, false)
                        autoRootEnabled = false
                    } else if (!AutoRootSupport.hasVerifiedInstall(this@InstallActivity)) {
                        Toast.makeText(
                            this@InstallActivity,
                            getString(R.string.autoroot_prior_install_required),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else if (
                        ContextCompat.checkSelfPermission(
                            this@InstallActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        requestBatteryOptimizationExemption()
                    } else {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                val setSoftReboot: (Boolean) -> Unit = { enabled ->
                    AppPreferences.setSoftRebootAfterRoot(this@InstallActivity, enabled)
                    softRebootEnabled = enabled
                    if (!enabled) softRebootTriggered = false
                }

                BackHandler(enabled = installState.busy) {}
                LaunchedEffect(startInstall, profileId) {
                    if (startInstall) installViewModel.install(profileId)
                }
                LaunchedEffect(installState.phase, history, softRebootEnabled, installSessionStartedAt) {
                    val latestRun = history.firstOrNull()
                    val persistedSuccess = installSessionStartedAt > 0L &&
                        latestRun?.result == InstallRunResult.Succeeded &&
                        latestRun.completedAtMillis != null &&
                        latestRun.startedAtMillis >= installSessionStartedAt
                    if (
                        installState.phase == InstallPhase.Installed &&
                        persistedSuccess &&
                        softRebootEnabled &&
                        !softRebootTriggered
                    ) {
                        softRebootTriggered = true
                        val result = KernelSuSoftReboot.request(this@InstallActivity)
                        if (!result.started) {
                            softRebootTriggered = false
                            Toast.makeText(
                                this@InstallActivity,
                                getString(R.string.soft_reboot_failed, result.detail.take(160)),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                InstallScreen(
                    installState = installState,
                    autoRootEnabled = autoRootEnabled,
                    onAutoRootEnabledChanged = setAutoRoot,
                    softRebootEnabled = softRebootEnabled,
                    onSoftRebootEnabledChanged = setSoftReboot,
                    onRetry = {
                        installSessionStartedAt = System.currentTimeMillis()
                        softRebootTriggered = false
                        installViewModel.install(profileId)
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_INSTALL_REQUEST_ID = "install_request_id"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

internal data class InstallerStep(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val icon: ImageVector,
)

internal val installerSteps = listOf(
    InstallerStep(R.string.step_support_title, R.string.step_support_detail, Icons.Rounded.Security),
    InstallerStep(R.string.step_download_title, R.string.step_download_detail, Icons.Rounded.CloudDownload),
    InstallerStep(R.string.step_exploit_title, R.string.step_exploit_detail, Icons.Rounded.Memory),
    InstallerStep(R.string.step_ksu_title, R.string.step_ksu_detail, Icons.Rounded.Check),
)

private fun clickHaptic(view: View) {
    view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}

@Composable
private fun InstallScreen(
    installState: InstallUiState,
    autoRootEnabled: Boolean,
    onAutoRootEnabledChanged: (Boolean) -> Unit,
    softRebootEnabled: Boolean,
    onSoftRebootEnabledChanged: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val view = LocalView.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.install_title),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = if (installState.busy) {
                        stringResource(R.string.install_keep_open)
                    } else {
                        installState.message
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InstallerStatusCard(installState)
            InstallerSteps(installState.phase)
            InstallerLog(
                output = installState.log,
                modifier = Modifier.weight(1f),
            )

            if (installState.phase == InstallPhase.Installed) {
                AutoRootOptInCard(
                    enabled = autoRootEnabled,
                    onEnabledChanged = onAutoRootEnabledChanged,
                )
                SoftRebootOptInCard(
                    enabled = softRebootEnabled,
                    onEnabledChanged = onSoftRebootEnabledChanged,
                )
            }

            if (!installState.busy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (installState.phase == InstallPhase.Failed) {
                        FilledTonalButton(
                            onClick = {
                                clickHaptic(view)
                                onClose()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_close))
                        }
                        Button(
                            onClick = {
                                clickHaptic(view)
                                onRetry()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    } else if (installState.phase == InstallPhase.Installed) {
                        Button(
                            onClick = {
                                clickHaptic(view)
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoRootOptInCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.autoroot_opt_in_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.autoroot_opt_in_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
        }
    }
}

@Composable
private fun SoftRebootOptInCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.soft_reboot_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.soft_reboot_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
        }
    }
}

@Composable
private fun InstallerStatusCard(installState: InstallUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = when (installState.phase) {
                InstallPhase.Failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (installState.phase == InstallPhase.Failed) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    installState.busy -> Icon(
                        Icons.Rounded.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                    installState.phase == InstallPhase.Installed -> Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                    else -> Icon(
                        Icons.Rounded.Error,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = installState.message,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = installPhaseDetail(installState.phase),
                        color = LocalContentColor.current.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallerSteps(phase: InstallPhase) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            installerSteps.forEachIndexed { index, step ->
                val stepState = stepState(phase, index)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = CircleShape,
                        color = if (stepState >= 1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (stepState >= 1) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (stepState == 2) Icons.Rounded.Check else step.icon,
                                contentDescription = null,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(step.title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(step.detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallerLog(
    output: String,
    modifier: Modifier,
) {
    val scrollState = rememberScrollState()
    val visibleOutput = remember(output) { installerLogForDisplay(output) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.install_live_progress), style = MaterialTheme.typography.titleMedium)
            Text(
                text = visibleOutput.ifBlank { stringResource(R.string.install_preparing) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal const val INSTALLER_LOG_RENDER_CHARS = 32_768

internal fun installerLogForDisplay(
    output: String,
    maxChars: Int = INSTALLER_LOG_RENDER_CHARS,
): String {
    require(maxChars > 0)
    if (output.length <= maxChars) return output
    return "…\n" + output.takeLast(maxChars)
}

@Composable
private fun installPhaseDetail(phase: InstallPhase): String = stringResource(
    when (phase) {
        InstallPhase.Checking -> R.string.phase_checking
        InstallPhase.Ready -> R.string.phase_ready
        InstallPhase.Downloading -> R.string.phase_downloading
        InstallPhase.Exploiting -> R.string.phase_exploiting
        InstallPhase.LoadingKernelSu -> R.string.phase_loading_ksu
        InstallPhase.Installed -> R.string.phase_installed
        InstallPhase.Failed -> R.string.phase_failed
    },
)

private fun stepState(phase: InstallPhase, stepIndex: Int): Int {
    if (phase == InstallPhase.Installed) return 2
    val activeIndex = when (phase) {
        InstallPhase.Checking, InstallPhase.Ready, InstallPhase.Failed -> 0
        InstallPhase.Downloading -> 1
        InstallPhase.Exploiting -> 2
        InstallPhase.LoadingKernelSu -> 3
        InstallPhase.Installed -> 4
    }
    return when {
        stepIndex < activeIndex -> 2
        stepIndex == activeIndex -> 1
        else -> 0
    }
}
