package ru.forum.adbfastboottool

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Главный UI-контейнер NekoFlash.
 *
 * Activity связывает вкладки приложения с единственным [DeviceViewModel], но не владеет
 * USB-транспортом и не выполняет mutation напрямую. Долгоживущие подключения и операции
 * остаются во ViewModel/transport layer. UI выбирает действие и передаёт его в реальный
 * ADB/Fastboot transport без тестовых и host-side authorization прослоек.
 */
class MainActivity : AppCompatActivity() {

    private enum class MiAuthExchangeState { IDLE, LOADING, SUCCESS, ERROR }

    private lateinit var usbManager: UsbManager
    private lateinit var rvConsoleOutput: RecyclerView
    private lateinit var consoleLogAdapter: ConsoleLogAdapter
    private lateinit var etCommand: EditText
    private lateinit var consoleDockController: ConsoleDockController
    private lateinit var tvStatus: TextView
    private var tvOtgStatus: TextView? = null
    private lateinit var cardOperationCenter: MaterialCardView
    private lateinit var operationCenterDetails: View
    private lateinit var tvOperationCenterStatus: TextView
    private lateinit var tvOperationCenterLastEvent: TextView
    private lateinit var operationCenterProgressBar: ProgressBar
    private lateinit var tvOperationCenterProgress: TextView
    private lateinit var tvOperationCenterCurrentLabel: TextView
    private lateinit var tvOperationCenterHeadline: TextView
    private lateinit var tvOperationCenterPercent: TextView
    private lateinit var tvOperationCenterStepSummary: TextView
    private lateinit var tvOperationStepQueue: TextView
    private lateinit var cardOperationStrip: MaterialCardView
    private lateinit var tvOperationStripTitle: TextView
    private lateinit var tvOperationStripMeta: TextView
    private lateinit var tvOperationStripPercent: TextView
    private lateinit var operationStripProgressBar: ProgressBar
    private lateinit var btnOperationStripDismiss: MaterialButton
    private lateinit var viewModel: DeviceViewModel
    private var viewModelReady: Boolean = false

    private val operationStripHandler = Handler(Looper.getMainLooper())
    private var operationStripDismissedSignature: String? = null
    private var operationStripScheduledSignature: String? = null
    private var operationStripHideRunnable: Runnable? = null
    private var operationStepsVisibleForCurrentRun: Boolean = false
    private var operationRunObservedActive: Boolean = false
    private var operationRunTracksProgress: Boolean = false
    private var operationStoredProgressHidden: Boolean = false
    private var operationHiddenProgressFingerprint: String? = null
    private var operationCancelRequested: Boolean = false

    private val actionUsbPermission: String by lazy { "$packageName.USB_PERMISSION" }
    private val folderName = "NekoFlash"
    private lateinit var workspacePath: File
    private lateinit var importFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var miLoginLauncher: ActivityResultLauncher<Intent>
    private var miAuth: MiAccountClient.AuthResult? = null
    private var miAuthExchangeJob: Job? = null
    private var miAuthExchangeState: MiAuthExchangeState = MiAuthExchangeState.IDLE

    // Управление вкладками вынесено в TabController (декомпозиция MainActivity).
    // by lazy — чтобы не обращаться к this в инициализаторе поля (leaking this).
    private val tabController by lazy { TabController(this) }
    // Совместимость: остальной код читает currentTab/selectedWindow как раньше.
    private val currentTab: String get() = tabController.commandContext
    private val selectedWindow: String get() = tabController.selectedWindow

    private var restoringWindowState = false
    private var overlayProtectionLogged = false
    private var redirectingToWelcome = false

    private data class PendingUsbConnect(
        val candidate: UsbDeviceInspector.Candidate,
        val automatic: Boolean
    )

    private val usbPermissionHandler = Handler(Looper.getMainLooper())
    private val usbPermissionTimeouts = mutableMapOf<Int, Runnable>()
    private val pendingUsbCandidates = mutableMapOf<Int, PendingUsbConnect>()

    private val modeSwitchHandler = Handler(Looper.getMainLooper())
    private val deviceOverviewHandler = Handler(Looper.getMainLooper())
    private val shortDeviceOverviewRefresh = Runnable {
        if (!isFinishing && !isDestroyed) updateDeviceOverview()
    }
    private val finalDeviceOverviewRefresh = Runnable {
        if (!isFinishing && !isDestroyed) updateDeviceOverview()
    }
    private var modeSwitchPreviousSignature: String? = null
    private var modeSwitchPreviousVendorId: Int? = null
    private var modeSwitchAttemptsRemaining = 0
    private var startupUsbDiscoveryDone = false
    private val startupUsbDiscoveryRunnable = Runnable { discoverAlreadyConnectedDevice() }
    private val modeSwitchRunnable = object : Runnable {
        override fun run() {
            if (modeSwitchAttemptsRemaining <= 0) return

            val candidate = UsbDeviceInspector.selectModeSwitchCandidate(
                usbManager.deviceList.values,
                modeSwitchPreviousSignature,
                modeSwitchPreviousVendorId
            )
            if (candidate != null) {
                modeSwitchAttemptsRemaining = 0
                // selectModeSwitchCandidate уже гарантировал другой логический профиль.
                viewModel.log(
                    "USB re-enumeration: found new mode ${candidate.mode.label} " +
                        "(interface=${candidate.interfaceIndex})"
                )
                requestUsbAccess(candidate, automatic = true)
                return
            }

            modeSwitchAttemptsRemaining -= 1
            if (modeSwitchAttemptsRemaining > 0) {
                modeSwitchHandler.postDelayed(this, MODE_SWITCH_SCAN_INTERVAL_MS)
            }
        }
    }

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private sealed class TerminalAction {
        data object LocalStatus : TerminalAction()
        data object OpenReportsFolder : TerminalAction()
        data class RawFastboot(val command: String) : TerminalAction()
        data class FastbootFlash(val partition: String, val file: File, val slot: String? = null) : TerminalAction()
        data class FastbootPartitionCommand(val wirePrefix: String, val partition: String, val slot: String? = null) : TerminalAction()
        data class FastbootDownloadAndRun(val file: File, val commandAfterDownload: String) : TerminalAction()
        data class FastbootLogicalInfo(val partition: String) : TerminalAction()
        data class FastbootFetch(val partition: String, val outputFile: File, val slot: String? = null) : TerminalAction()
        data class AdbService(val service: String) : TerminalAction()
        data class AdbShell(val command: String) : TerminalAction()
        data class AdbShellToFile(val command: String, val outputFile: File, val append: Boolean) : TerminalAction()
        data class AdbPush(val localFile: File, val remotePath: String) : TerminalAction()
        data class AdbPull(val remotePath: String, val localFile: File) : TerminalAction()
        data class AdbInstall(val packageFile: File, val options: List<String>) : TerminalAction()
        data class AdbInstallMultiple(val apkFiles: List<File>, val options: List<String>) : TerminalAction()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> handleUsbPermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleUsbDetached(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        super.onCreate(savedInstanceState)

        // Never trust exported Intent extras as an onboarding bypass. This
        // check also covers ACTION_USB_DEVICE_ATTACHED, activity recreation,
        // and a storage permission revoked after onboarding.
        if (!OnboardingGate.canEnterMain(this)) {
            redirectToWelcome(intent)
            return
        }
        setContentView(R.layout.activity_main)

        rvConsoleOutput = findViewById(R.id.rvConsoleOutput)
        consoleLogAdapter = ConsoleLogAdapter()
        rvConsoleOutput.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = consoleLogAdapter
            itemAnimator = null
        }
        etCommand = findViewById(R.id.etCommand)
        consoleDockController = ConsoleDockController(this, rvConsoleOutput).also { it.initialize() }
        findViewById<View>(R.id.btnConsoleLogs).setOnClickListener { showLogsMenu() }
        tvStatus = findViewById(R.id.tvStatus)
        tvOtgStatus = findViewById(R.id.tvOtgStatus)
        updateOtgStatus()
        cardOperationCenter = findViewById(R.id.cardOperationCenter)
        operationCenterDetails = findViewById(R.id.operationCenterDetails)
        tvOperationCenterStatus = findViewById(R.id.tvOperationCenterStatus)
        tvOperationCenterLastEvent = findViewById(R.id.tvOperationCenterLastEvent)
        operationCenterProgressBar = findViewById(R.id.operationCenterProgressBar)
        tvOperationCenterProgress = findViewById(R.id.tvOperationCenterProgress)
        tvOperationCenterCurrentLabel = findViewById(R.id.tvOperationCenterCurrentLabel)
        tvOperationCenterHeadline = findViewById(R.id.tvOperationCenterHeadline)
        tvOperationCenterPercent = findViewById(R.id.tvOperationCenterPercent)
        tvOperationCenterStepSummary = findViewById(R.id.tvOperationCenterStepSummary)
        tvOperationStepQueue = findViewById(R.id.tvOperationStepQueue)
        cardOperationStrip = findViewById(R.id.cardOperationStrip)
        tvOperationStripTitle = findViewById(R.id.tvOperationStripTitle)
        tvOperationStripMeta = findViewById(R.id.tvOperationStripMeta)
        tvOperationStripPercent = findViewById(R.id.tvOperationStripPercent)
        operationStripProgressBar = findViewById(R.id.operationStripProgressBar)
        btnOperationStripDismiss = findViewById(R.id.btnOperationStripDismiss)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        viewModel = ViewModelProvider(this)[DeviceViewModel::class.java]
        viewModelReady = true
        operationStoredProgressHidden = savedInstanceState?.getBoolean(
            STATE_HIDE_STORED_OPERATION_PROGRESS,
            false
        ) ?: false
        operationHiddenProgressFingerprint = savedInstanceState?.getString(
            STATE_HIDDEN_OPERATION_PROGRESS_FINGERPRINT
        )
        operationCancelRequested = savedInstanceState?.getBoolean(
            STATE_OPERATION_CANCEL_REQUESTED,
            false
        ) ?: false
        operationStripDismissedSignature = savedInstanceState?.getString(
            STATE_OPERATION_STRIP_DISMISSED_SIGNATURE
        )
        enableOverlayProtection()

        viewModel.logLines.observe(this) { lines ->
            renderLog(lines)
            updateOperationCenter(lines)
        }

        viewModel.connectionState.observe(this) {
            refreshConnectionStatusLabel()
            updateDeviceOverview()
            if (selectedWindow == "unlock") buildUnlockPage()
        }

        viewModel.connectionInfo.observe(this) { updateDeviceOverview() }
        viewModel.fastbootDiagnostics.observe(this) {
            // Диагностика приходит после connectionState — переобновим точный режим.
            refreshConnectionStatusLabel()
            updateDeviceOverview()
            if (selectedWindow == "unlock") buildUnlockPage()
        }
        viewModel.fastbootPartitionInventory.observe(this) {
            updateDeviceOverview()
        }
        viewModel.adbPeerMode.observe(this) {
            // ADB transport один, но peer mode различается: system/recovery/sideload.
            refreshConnectionStatusLabel()
            updateDeviceOverview()
        }
        viewModel.transportSessionId.observe(this) { updateDeviceOverview() }

        viewModel.operationActive.observe(this) { active ->
            val rawProgress = viewModel.operationProgress.value
            if (active) {
                // Do not leak a completed queue's step list or result into a
                // later unrelated operation. Heavy operations publish a fresh
                // unfinished OperationProgress before operationActive=true;
                // lightweight diagnostics/commands intentionally do not.
                operationStepsVisibleForCurrentRun = false
                operationRunObservedActive = true
                val rawFingerprint = operationProgressFingerprint(rawProgress)
                val knownHiddenProgress = operationStoredProgressHidden &&
                    rawFingerprint != null && rawFingerprint == operationHiddenProgressFingerprint
                operationRunTracksProgress = rawProgress?.finished == false && !knownHiddenProgress
                operationStoredProgressHidden = !operationRunTracksProgress
                operationHiddenProgressFingerprint = if (operationStoredProgressHidden) {
                    rawFingerprint
                } else {
                    null
                }
                // Preserve a cancellation request across Activity recreation.
                // A genuinely new run reaches this branch only after the prior
                // inactive transition has reset operationCancelRequested.
                operationStripDismissedSignature = null
                cancelScheduledOperationStripHide()
                // Operations never force Console over the active task screen.
                // If it was left open, return it to the persistent compact dock;
                // the user can still reopen it explicitly while work is running.
                consoleDockController.collapse()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Авто-снижение яркости на время записи: экономит энергию
                // и снижает нагрев/троттлинг при долгой прошивке.
                applyReducedBrightness()
            } else {
                if (operationRunObservedActive && !operationRunTracksProgress) {
                    // A lightweight operation has no result object of its own.
                    // Keep the previous heavy-operation result hidden instead
                    // of resurrecting stale 100%/FAILED state after completion.
                    operationStoredProgressHidden = true
                    operationCancelRequested = false
                }
                operationRunObservedActive = false
                if (rawProgress?.finished == true || operationStoredProgressHidden) {
                    operationRunTracksProgress = false
                    operationCancelRequested = false
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                restoreBrightness()
            }
            updateDeviceOverview()
            renderOperationProgressUi(rawProgress)
        }

        viewModel.operationSteps.observe(this) { steps -> renderOperationSteps(steps) }
        viewModel.operationProgress.observe(this) { progress ->
            val operationIsActive = viewModel.operationActive.value == true
            if (operationIsActive && operationRunTracksProgress) {
                operationStoredProgressHidden = false
                operationHiddenProgressFingerprint = null
            } else if (operationIsActive && operationStoredProgressHidden && !operationRunTracksProgress) {
                // A lightweight run can mutate an older progress object while
                // cancellation is being requested. Remember that exact stale
                // snapshot so the next lightweight run cannot revive it.
                operationHiddenProgressFingerprint = operationProgressFingerprint(progress)
            }
            if (!operationIsActive && progress?.finished == true && !operationStoredProgressHidden) {
                operationRunTracksProgress = false
                operationHiddenProgressFingerprint = null
                operationCancelRequested = false
            }
            renderOperationProgressUi(progress)
        }

        registerUsbReceiver()
        registerImportLauncher()
        registerMiLoginLauncher()
        setupButtons()
        buildSettingsPage()
        restoreWindowState(savedInstanceState)
        updateDeviceOverview()
        checkPermissions()
        logBatteryOptimizationState()
        viewModel.log(getString(R.string.log_init_v20))
        val scanAfterWelcome = intent.getBooleanExtra(
            WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME,
            false
        )
        val attachHandled = handleAutoUsbIntent(intent)
        if (!attachHandled) {
            if (scanAfterWelcome) {
                intent.removeExtra(WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME)
                viewModel.log("USB attach continued after the required welcome gate; running a safe startup scan.")
            }
            scheduleStartupUsbDiscovery()
        }
    }

    private fun redirectToWelcome(sourceIntent: Intent?) {
        if (redirectingToWelcome || isFinishing || isDestroyed) return
        redirectingToWelcome = true
        val launchedFromUsbAttach = sourceIntent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        startActivity(Intent(this, WelcomeActivity::class.java).apply {
            putExtra(WelcomeActivity.EXTRA_PENDING_USB_ATTACH, launchedFromUsbAttach)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!OnboardingGate.canEnterMain(this)) {
            redirectToWelcome(intent)
            return
        }
        val scanAfterWelcome = intent.getBooleanExtra(
            WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME,
            false
        )
        val attachHandled = handleAutoUsbIntent(intent)
        if (!attachHandled && scanAfterWelcome) {
            intent.removeExtra(WelcomeActivity.EXTRA_STARTUP_SCAN_AFTER_WELCOME)
            viewModel.log("USB attach continued after the required welcome gate; running a safe startup scan.")
            scheduleStartupUsbDiscovery()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_WINDOW, selectedWindow)
        outState.putBoolean(STATE_HIDE_STORED_OPERATION_PROGRESS, operationStoredProgressHidden)
        outState.putString(STATE_HIDDEN_OPERATION_PROGRESS_FINGERPRINT, operationHiddenProgressFingerprint)
        outState.putBoolean(STATE_OPERATION_CANCEL_REQUESTED, operationCancelRequested)
        outState.putString(STATE_OPERATION_STRIP_DISMISSED_SIGNATURE, operationStripDismissedSignature)
        super.onSaveInstanceState(outState)
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { updateOtgStatus(); scanForDevices() }
        // Импорт файла из угла блока прошивки (в контексте Fastboot).
        findViewById<View>(R.id.btnBlockImportFastboot).setOnClickListener { startImportFilePicker() }
        // Режим перезагрузки в блоке прошивки — то же меню, что было на главной.
        findViewById<View>(R.id.btnFlashRebootMode).setOnClickListener { showRebootMenu() }
        findViewById<Button>(R.id.btnHomeRefreshData).setOnClickListener { refreshDeviceDataFromUi() }
        findViewById<Button>(R.id.btnOperationCenterConsole).setOnClickListener {
            openConsole(requestCommandFocus = false)
        }
        findViewById<View>(R.id.btnReportsMenu).setOnClickListener { showReportsMenu() }
        findViewById<Button>(R.id.btnOperationCenterCancel).setOnClickListener { requestOperationCancelFromUi() }
        cardOperationStrip.setOnClickListener { openOperationCenter() }
        btnOperationStripDismiss.setOnClickListener {
            operationProgressSignature(viewModel.operationProgress.value)?.let { signature ->
                operationStripDismissedSignature = signature
            }
            cancelScheduledOperationStripHide()
            cardOperationStrip.visibility = View.GONE
        }

        findViewById<View>(R.id.btnFlashRecovery).setOnClickListener {
            startDirectFlash("recovery")
        }
        findViewById<View>(R.id.btnFlashBoot).setOnClickListener {
            startDirectFlash("boot")
        }
        findViewById<View>(R.id.btnFlashInitBoot).setOnClickListener {
            startDirectFlash("init_boot")
        }
        findViewById<View>(R.id.btnFlashVendorBoot).setOnClickListener {
            startDirectFlash("vendor_boot")
        }
        findViewById<View>(R.id.btnFlashDtbo).setOnClickListener {
            startDirectFlash("dtbo")
        }
        findViewById<View>(R.id.btnFlashVbmeta).setOnClickListener {
            startDirectFlash("vbmeta")
        }
        findViewById<View>(R.id.btnFlashManual).setOnClickListener {
            showManualQuickFlashTargetDialog()
        }

        // Единое меню Reboot (BottomSheet) — собирает все варианты перезагрузки.
        findViewById<Button>(R.id.btnAdbSideload).setOnClickListener {
            showFileSelector { file -> viewModel.runSideload(file) }
        }
        findViewById<View>(R.id.btnSideloadImport).setOnClickListener { startImportFilePicker() }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            requestOperationCancelFromUi()
        }

        findViewById<Button>(R.id.btnHistoryUp).setOnClickListener { navigateHistory(-1) }
        findViewById<Button>(R.id.btnHistoryDown).setOnClickListener { navigateHistory(1) }

        findViewById<Button>(R.id.btnSend).setOnClickListener { handleCommandInput() }
        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                handleCommandInput()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.tabHome).setOnClickListener { switchTab("home") }

        // Кнопка «Назад»: если мы не на главном экране — возвращаемся на него,
        // а не закрываем приложение. На главном — стандартный выход.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = tabController.selectedWindow
                viewModel.log("← Back pressed (current tab: $current)")
                when {
                    // Console owns IME and sheet-state navigation before pages.
                    consoleDockController.handleBack() -> Unit
                    // Не на главной — возвращаемся на главный экран.
                    current != "home" -> switchTab("home")
                    // На главной — сворачиваем в фон (не убивая процесс).
                    else -> moveTaskToBack(true)
                }
            }
        })
        findViewById<Button>(R.id.tabFastboot).setOnClickListener { switchTab("fastboot") }
        findViewById<Button>(R.id.tabAdb).setOnClickListener { switchTab("adb") }
        findViewById<Button>(R.id.tabSettings).setOnClickListener { switchTab("settings") }
        findViewById<Button>(R.id.tabUnlock).setOnClickListener {
            switchTab("unlock")
            buildUnlockPage()
        }
    }

    // ─── USB ─────────────────────────────────────────────────────────────────

    private fun registerUsbReceiver() {
        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun handleUsbPermissionResult(intent: Intent) {
        synchronized(this@MainActivity) {
            val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            device?.let { cancelUsbPermissionTimeout(it.deviceId) }

            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                device?.let { takePendingUsbConnect(it) }
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: USB access denied by user")
                return
            }
            if (device == null) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: USB device was not provided by the system")
                return
            }

            viewModel.log("USB access granted. Analyzing interfaces...")
            val pending = takePendingUsbConnect(device)
            analyzeAndConnectDevice(device, pending)
        }
    }

    private fun handleUsbDetached(intent: Intent) {
        val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device == null) {
            viewModel.log("USB device disconnected: unknown")
            updateOtgStatus()
            return
        }

        pendingUsbCandidates.remove(device.deviceId)
        val isCurrent = viewModel.isCurrentUsbDevice(device)
        val wasFastboot = isCurrent && viewModel.currentUsbMode() == UsbDeviceInspector.Mode.FASTBOOT
        if (isCurrent) {
            val previousSignature = viewModel.currentUsbLogicalSignature()
                ?: UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = true)?.logicalSignature
            val previousVendorId = viewModel.currentUsbVendorId() ?: device.vendorId
            if (wasFastboot) {
                viewModel.log(
                    DiagnosticLogPolicy.Level.INFO,
                    "ℹ️ Fastboot detach confirmed: current USB generation is closed fail-closed. " +
                        "Reconnect the device before the next command."
                )
            } else {
                viewModel.log("USB device disconnected: ${device.productName ?: device.deviceName}")
            }
            viewModel.disconnectCurrent()
            startModeSwitchWatch(previousSignature, previousVendorId)
        } else {
            viewModel.logFileOnly(
                "USB detach ignored for unrelated device: ${device.deviceName} " +
                    "VID=${device.vendorId} PID=${device.productId}"
            )
        }
        updateOtgStatus()
    }

    private fun handleAutoUsbIntent(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        if (intent.getBooleanExtra(EXTRA_USB_INTENT_CONSUMED, false)) {
            // Activity могла быть пересоздана с тем же Intent. Сам Intent повторно
            // не обрабатываем, но возвращаем false, чтобы onCreate выполнил
            // одноразовое перечисление уже подключённых USB-устройств.
            return false
        }

        val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        intent.putExtra(EXTRA_USB_INTENT_CONSUMED, true)

        if (device == null) {
            viewModel.log("⚠️ USB attach: system did not provide a device")
            return true
        }

        cancelStartupUsbDiscovery()
        stopModeSwitchWatch()
        val candidate = UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = true)
        if (candidate == null) {
            viewModel.log("⚠️ USB device connected, but no ADB/Fastboot bulk interface was found")
            viewModel.logFileOnly(UsbDeviceInspector.summarizeDevice(device))
            return true
        }
        requestUsbAccess(candidate, automatic = true)
        updateOtgStatus()
        return true
    }

    private fun requestUsbAccess(
        candidate: UsbDeviceInspector.Candidate,
        automatic: Boolean
    ) {
        val device = candidate.device
        pendingUsbCandidates[device.deviceId] = PendingUsbConnect(candidate, automatic)
        viewModel.log(
            "Requesting device access: ${device.productName ?: "Unknown"} " +
                "(VID=${device.vendorId}, PID=${device.productId}, mode=${candidate.mode.label}, " +
                "interface=${candidate.interfaceIndex}, match=${candidate.matchKind.label})"
        )

        if (usbManager.hasPermission(device)) {
            viewModel.log("USB access already granted")
            pendingUsbCandidates.remove(device.deviceId)
            connectCandidate(candidate, automatic)
            return
        }

        val permissionIntent = Intent(actionUsbPermission).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, device.deviceId, permissionIntent, flags)
        scheduleUsbPermissionTimeout(device)
        usbManager.requestPermission(device, pi)
    }

    private fun analyzeAndConnectDevice(
        device: UsbDevice,
        pending: PendingUsbConnect? = null
    ) {
        val candidate = pending?.candidate
            ?.let { UsbDeviceInspector.rebindCandidate(device, it) }
            ?: UsbDeviceInspector.selectPrimaryCandidate(device, allowGenericFastboot = true)

        if (candidate == null) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: Device was not recognized as ADB/Fastboot")
            viewModel.logFileOnly(UsbDeviceInspector.summarizeDevice(device))
            return
        }
        connectCandidate(candidate, pending?.automatic ?: true)
    }

    private fun connectCandidate(
        candidate: UsbDeviceInspector.Candidate,
        automatic: Boolean
    ) {
        when (candidate.mode) {
            UsbDeviceInspector.Mode.ADB -> viewModel.log("Mode: ADB")
            UsbDeviceInspector.Mode.FASTBOOT -> viewModel.log("Mode: FASTBOOT")
        }
        if (candidate.matchKind != UsbDeviceInspector.MatchKind.CANONICAL) {
            viewModel.log(
                "ℹ️ Using ${candidate.matchKind.label} USB interface: " +
                    "class=${candidate.interfaceClass}, subclass=${candidate.interfaceSubclass}, " +
                    "protocol=${candidate.interfaceProtocol}, interface=${candidate.interfaceIndex}"
            )
        }
        viewModel.connectDevice(usbManager, candidate, automatic = automatic)
    }

    private fun scheduleUsbPermissionTimeout(device: UsbDevice) {
        cancelUsbPermissionTimeout(device.deviceId)
        val timeout = Runnable {
            usbPermissionTimeouts.remove(device.deviceId)
            pendingUsbCandidates.remove(device.deviceId)
            if (!usbManager.hasPermission(device)) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: no response to the USB permission request within 30 seconds. Reconnect the OTG cable and tap Search again.")
            }
        }
        usbPermissionTimeouts[device.deviceId] = timeout
        usbPermissionHandler.postDelayed(timeout, USB_PERMISSION_TIMEOUT_MS)
    }

    private fun cancelUsbPermissionTimeout(deviceId: Int) {
        val timeout = usbPermissionTimeouts.remove(deviceId) ?: return
        usbPermissionHandler.removeCallbacks(timeout)
    }

    private fun takePendingUsbConnect(device: UsbDevice): PendingUsbConnect? {
        pendingUsbCandidates.remove(device.deviceId)?.let { return it }
        val matchingEntry = pendingUsbCandidates.entries.firstOrNull {
            it.value.candidate.device.deviceName == device.deviceName
        } ?: return null
        pendingUsbCandidates.remove(matchingEntry.key)
        return matchingEntry.value
    }

    private fun scheduleStartupUsbDiscovery() {
        if (startupUsbDiscoveryDone) return
        startupUsbDiscoveryDone = true
        modeSwitchHandler.postDelayed(startupUsbDiscoveryRunnable, STARTUP_USB_SCAN_DELAY_MS)
    }

    private fun cancelStartupUsbDiscovery() {
        modeSwitchHandler.removeCallbacks(startupUsbDiscoveryRunnable)
    }

    private fun discoverAlreadyConnectedDevice() {
        val state = viewModel.connectionState.value ?: DeviceViewModel.ConnectionState.NONE
        if (state !in setOf(DeviceViewModel.ConnectionState.NONE, DeviceViewModel.ConnectionState.ERROR)) return

        val candidates = UsbDeviceInspector.findAutoConnectCandidates(usbManager.deviceList.values)
        val candidate = candidates.singleOrNull() ?: return
        viewModel.log(
            "USB startup-scan: found ${candidate.mode.label} device " +
                "(interface=${candidate.interfaceIndex})"
        )
        requestUsbAccess(candidate, automatic = true)
    }

    private fun startModeSwitchWatch(previousLogicalSignature: String?, previousVendorId: Int?) {
        cancelStartupUsbDiscovery()
        stopModeSwitchWatch()
        modeSwitchPreviousSignature = previousLogicalSignature
        modeSwitchPreviousVendorId = previousVendorId
        modeSwitchAttemptsRemaining = MODE_SWITCH_SCAN_ATTEMPTS
        modeSwitchHandler.postDelayed(modeSwitchRunnable, MODE_SWITCH_SCAN_INTERVAL_MS)
    }

    private fun stopModeSwitchWatch() {
        modeSwitchAttemptsRemaining = 0
        modeSwitchPreviousSignature = null
        modeSwitchPreviousVendorId = null
        modeSwitchHandler.removeCallbacks(modeSwitchRunnable)
    }

    private fun scanForDevices() {
        cancelStartupUsbDiscovery()
        stopModeSwitchWatch()
        val candidates = UsbDeviceInspector.findAllCandidates(
            usbManager.deviceList.values,
            includeGenericFastboot = true
        )
        when {
            candidates.isEmpty() -> {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: no compatible ADB/Fastboot USB devices found")
                logUsbInventoryForTroubleshooting()
            }
            candidates.size == 1 -> {
                val candidate = candidates.first()
                viewModel.log("Found device: ${candidate.displayTitle()} | ${candidate.displaySubtitle()}")
                connectManualCandidate(candidate)
            }
            else -> showUsbDeviceChooser(candidates)
        }
    }

    private fun showUsbDeviceChooser(candidates: List<UsbDeviceInspector.Candidate>) {
        val items = candidates.mapIndexed { index, candidate ->
            candidate.displayTitle(index + 1) + "\n" + candidate.displaySubtitle()
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_usb_choose_title))
            .setItems(items) { _, which ->
                val selected = candidates[which]
                viewModel.log("Selected: ${selected.displayTitle()} | ${selected.displaySubtitle()}")
                connectManualCandidate(selected)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun connectManualCandidate(candidate: UsbDeviceInspector.Candidate) {
        stopModeSwitchWatch()
        if (candidate.matchKind != UsbDeviceInspector.MatchKind.GENERIC_FASTBOOT) {
            requestUsbAccess(candidate, automatic = false)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.usb_generic_fastboot_title))
            .setMessage(
                getString(
                    R.string.usb_generic_fastboot_message,
                    candidate.displaySubtitle()
                )
            )
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                viewModel.log("⚠️ Generic Fastboot candidate was selected manually by the user")
                requestUsbAccess(candidate, automatic = false)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun logUsbInventoryForTroubleshooting() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            viewModel.log("USB inventory: Android does not see any USB devices. Check OTG and the data cable.")
            return
        }
        devices.forEach { device ->
            viewModel.log("USB found, but not ADB/Fastboot: ${device.productName ?: device.deviceName} VID=${device.vendorId} PID=${device.productId} interfaces=${device.interfaceCount}")
            viewModel.logFileOnly(UsbDeviceInspector.summarizeDevice(device))
        }
    }

    // ─── КОМАНДЫ ─────────────────────────────────────────────────────────────

    private fun handleCommandInput() {
        val raw = etCommand.text.toString().trim()
        if (raw.isEmpty()) return
        etCommand.text.clear()
        addToHistory(raw)

        val rawLower = raw.lowercase(Locale.US)
        if (isOpenReportsCommand(rawLower)) {
            viewModel.log("> $raw")
            openReportsFolder()
            return
        }

        val (type, cmd) = when {
            raw.startsWith("fastboot ", ignoreCase = true) -> "fastboot" to raw.substringAfter(" ").trim()
            raw.startsWith("adb ", ignoreCase = true) -> "adb" to raw.substringAfter(" ").trim()
            else -> currentTab to raw
        }

        if (viewModel.isInteractiveAdbShellActive()) {
            handleInteractiveAdbShellInput(raw, type, cmd)
            return
        }

        when (type) {
            "fastboot" -> handleFastbootTerminalCommand(cmd)
            "adb" -> handleAdbTerminalCommand(cmd)
            else -> {
                viewModel.log("> $raw")
                viewModel.log("⚠️ No ADB/Fastboot context selected. Enter the adb or fastboot prefix.")
            }
        }
    }

    private fun handleInteractiveAdbShellInput(raw: String, type: String, cmd: String) {
        val cleanRaw = raw.trim()
        val cleanCmd = cmd.trim()
        val lowerRaw = cleanRaw.lowercase(Locale.US)
        val lowerCmd = cleanCmd.lowercase(Locale.US)

        val stopRequested = lowerRaw == ":close" ||
            lowerRaw == ":exit" ||
            lowerRaw == "adb shell-stop" ||
            lowerRaw == "adb shell-exit" ||
            (type == "adb" && (lowerCmd == "shell-stop" || lowerCmd == "shell-exit"))

        if (stopRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.stopInteractiveAdbShell()
            return
        }

        val interruptRequested = lowerRaw == ":ctrl-c" ||
            lowerRaw == ":sigint" ||
            lowerRaw == ":interrupt" ||
            lowerRaw == "adb shell-ctrl-c" ||
            lowerRaw == "adb shell-interrupt" ||
            (type == "adb" && (lowerCmd == "shell-ctrl-c" || lowerCmd == "shell-interrupt"))

        if (interruptRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.interruptInteractiveAdbShell()
            return
        }

        val eofRequested = lowerRaw == ":ctrl-d" ||
            lowerRaw == ":eof" ||
            lowerRaw == "adb shell-ctrl-d" ||
            lowerRaw == "adb shell-eof" ||
            (type == "adb" && (lowerCmd == "shell-ctrl-d" || lowerCmd == "shell-eof"))

        if (eofRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.sendInteractiveAdbShellEof()
            return
        }

        val shellLine = when {
            type == "adb" && lowerCmd == "shell" -> ""
            type == "adb" && lowerCmd.startsWith("shell ") -> cleanCmd.substringAfterWord("shell").trimStart()
            type == "adb" -> cleanCmd
            else -> cleanRaw
        }

        if (shellLine.isBlank()) {
            viewModel.log("ℹ️ Interactive adb shell is already open. Enter a command or adb shell-stop to exit.")
            return
        }

        viewModel.sendInteractiveAdbShellInput(shellLine)
    }

    private fun handleFastbootTerminalCommand(cmd: String) {
        viewModel.log("> fastboot $cmd")
        when (val action = parseFastbootCommand(cmd)) {
            null -> return
            TerminalAction.LocalStatus -> viewModel.logConnectionStatus()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.RawFastboot -> {
                val hostOp = action.command.substringBefore(' ').substringBefore(':')
                if (!hostOp.matches(Regex("^-{0,2}[A-Za-z0-9][A-Za-z0-9._-]*$"))) {
                    viewModel.log("❌ Invalid Fastboot command: $hostOp")
                } else if (viewModel.fastbootProtocol?.isConnected != true) {
                    viewModel.log(
                        DiagnosticLogPolicy.Level.ERROR,
                        "ERROR: Fastboot device is not connected. Command was not sent."
                    )
                } else {
                    // Raw/OEM passthrough остаётся разрешённым; terminal FAIL/OKAY
                    // показывается в Console без блокирующего progress-dialog.
                    viewModel.runFastbootCommand(action.command, heavy = false)
                }
            }
            is TerminalAction.FastbootFlash -> viewModel.runFlash(action.partition, action.file, action.slot)
            is TerminalAction.FastbootPartitionCommand -> viewModel.runFastbootPartitionCommand(action.wirePrefix, action.partition, action.slot)
            is TerminalAction.FastbootDownloadAndRun -> viewModel.runFastbootDownloadAndRun(action.file, action.commandAfterDownload)
            is TerminalAction.FastbootLogicalInfo -> viewModel.inspectFastbootLogicalPartition(action.partition)
            is TerminalAction.FastbootFetch -> viewModel.runFastbootFetch(action.partition, action.outputFile, action.slot)
            is TerminalAction.AdbService,
            is TerminalAction.AdbShell,
            is TerminalAction.AdbShellToFile,
            is TerminalAction.AdbPush,
            is TerminalAction.AdbPull,
            is TerminalAction.AdbInstall,
            is TerminalAction.AdbInstallMultiple -> Unit
        }
    }

    private fun handleAdbTerminalCommand(cmd: String) {
        viewModel.log("> adb $cmd")
        when (val action = parseAdbCommand(cmd)) {
            null -> return
            TerminalAction.LocalStatus -> viewModel.logConnectionStatus()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.AdbService -> {
                if (viewModel.adbProtocol?.isConnected != true) {
                    viewModel.log(
                        DiagnosticLogPolicy.Level.ERROR,
                        "ERROR: ADB device is not connected. Command was not sent."
                    )
                } else {
                    viewModel.runAdbService(action.service)
                }
            }
            is TerminalAction.AdbShell -> {
                if (viewModel.adbProtocol?.isConnected != true) {
                    viewModel.log(
                        DiagnosticLogPolicy.Level.ERROR,
                        "ERROR: ADB device is not connected. Command was not sent."
                    )
                } else {
                    viewModel.runAdbShell(action.command)
                }
            }
            is TerminalAction.AdbShellToFile -> {
                if (viewModel.adbProtocol?.isConnected != true) {
                    viewModel.log(
                        DiagnosticLogPolicy.Level.ERROR,
                        "ERROR: ADB device is not connected. Command was not sent."
                    )
                } else {
                    viewModel.runAdbShellToFile(action.command, action.outputFile, action.append)
                }
            }
            is TerminalAction.AdbPush -> viewModel.runAdbPush(action.localFile, action.remotePath)
            is TerminalAction.AdbPull -> viewModel.runAdbPull(action.remotePath, action.localFile)
            is TerminalAction.AdbInstall -> viewModel.runAdbInstall(action.packageFile, action.options)
            is TerminalAction.AdbInstallMultiple -> viewModel.runAdbInstallMultiple(action.apkFiles, action.options)
            is TerminalAction.RawFastboot,
            is TerminalAction.FastbootFlash,
            is TerminalAction.FastbootPartitionCommand,
            is TerminalAction.FastbootDownloadAndRun,
            is TerminalAction.FastbootLogicalInfo,
            is TerminalAction.FastbootFetch -> Unit
        }
    }

    private fun invalidTerminalFormat(format: String): TerminalAction? {
        viewModel.log("❌ Format: $format")
        return null
    }

    private data class FastbootTerminalOptions(
        val tokens: List<String>,
        val slot: String? = null,
        val setActive: String? = null
    )

    private fun normalizeFastbootSlot(raw: String): String? {
        val slot = raw.trim().removePrefix("_").lowercase(Locale.US)
        if (slot.isBlank()) {
            viewModel.log("❌ Empty --slot value")
            return null
        }
        if (slot == "all" || slot == "other" || (slot.length == 1 && slot[0] in 'a'..'z')) {
            return slot
        }
        viewModel.log("❌ Invalid slot: $raw. Use a, b, all or other.")
        return null
    }

    private fun parseFastbootTerminalOptions(tokens: List<String>): FastbootTerminalOptions? {
        val commandTokens = mutableListOf<String>()
        var slot: String? = null
        var setActive: String? = null
        var i = 0

        fun nextOptionValue(option: String): String? {
            val value = tokens.getOrNull(i + 1)
            if (value == null) {
                viewModel.log("❌ $option requires a value")
                return null
            }
            i += 1
            return value
        }

        while (i < tokens.size) {
            val token = tokens[i]
            val lower = token.lowercase(Locale.US)
            when {
                lower == "--slot" -> {
                    val value = nextOptionValue("--slot") ?: return null
                    slot = normalizeFastbootSlot(value) ?: return null
                }
                lower.startsWith("--slot=") -> {
                    slot = normalizeFastbootSlot(token.substringAfter('=')) ?: return null
                }
                lower == "--set-active" -> {
                    val value = nextOptionValue("--set-active") ?: return null
                    setActive = normalizeFastbootSlot(value)?.takeUnless { it == "all" || it == "other" } ?: return null
                }
                lower.startsWith("--set-active=") -> {
                    setActive = normalizeFastbootSlot(token.substringAfter('='))?.takeUnless { it == "all" || it == "other" } ?: return null
                }
                lower == "-a" -> {
                    val value = nextOptionValue("-a") ?: return null
                    setActive = normalizeFastbootSlot(value)?.takeUnless { it == "all" || it == "other" } ?: return null
                }
                lower == "--disable-verity" || lower == "--disable-verification" -> {
                    viewModel.log(
                        "⚠️ $token — host-side vbmeta patching from desktop fastboot. " +
                            "NekoFlash does not patch vbmeta on the fly; flash an already prepared image."
                    )
                    return null
                }
                lower == "--skip-reboot" || lower == "--skip-secondary" || lower == "--force" || lower == "--verbose" || lower == "-v" -> {
                    viewModel.log("ℹ️ Option $token is not used by the terminal handler for a single USB command.")
                }
                token == "-S" || lower == "--sparse-limit" -> {
                    nextOptionValue(token) ?: return null
                    viewModel.log("ℹ️ Sparse splitting (-S) is not required: NekoFlash sends the selected image directly.")
                }
                token.startsWith("-S") && token.length > 2 -> {
                    viewModel.log("ℹ️ Sparse splitting (-S) is not required: NekoFlash sends the selected image directly.")
                }
                lower == "-s" -> {
                    nextOptionValue("-s") ?: return null
                    viewModel.log("ℹ️ -s SERIAL is not used: NekoFlash works with the selected OTG device.")
                }
                else -> commandTokens += token
            }
            i += 1
        }

        return FastbootTerminalOptions(commandTokens, slot, setActive)
    }

    private fun parseFastbootCommand(cmd: String): TerminalAction? {
        val clean = cmd.trim()
        if (clean.isBlank()) return null
        val rawTokens = tokenizeCommandLine(clean)
        if (rawTokens.isEmpty()) return null

        val parsedOptions = parseFastbootTerminalOptions(rawTokens) ?: return null
        if (parsedOptions.setActive != null && parsedOptions.tokens.isEmpty()) {
            return TerminalAction.RawFastboot("set_active:${parsedOptions.setActive}")
        }

        val tokens = parsedOptions.tokens
        if (tokens.isEmpty()) return null
        val op = tokens[0].lowercase(Locale.US)

        if (warnIfBatchOrShellSyntax(op, clean)) return null

        return when (op) {
            "status", "devices" -> TerminalAction.LocalStatus
            "reports", "open-reports", "report-folder", "reports-folder" -> TerminalAction.OpenReportsFolder

            "-w", "--wipe" -> TerminalAction.RawFastboot("erase:userdata")

            "flash" -> {
                if (tokens.size < 3) return invalidTerminalFormat("fastboot [--slot=<a|b|all|other>] flash <partition> <file.img>")
                val partition = tokens[1]
                val file = resolveTerminalFile(tokens[2]) ?: return null
                TerminalAction.FastbootFlash(partition, file, parsedOptions.slot)
            }

            "boot" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot boot <file.img>")
                val file = resolveTerminalFile(tokens[1]) ?: return null
                TerminalAction.FastbootDownloadAndRun(file, "boot")
            }

            "getvar" -> {
                val variable = tokens.drop(1).joinToString(" ").ifBlank { "all" }
                TerminalAction.RawFastboot("getvar:$variable")
            }

            "is-logical", "logical-info" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot $op <partition>")
                TerminalAction.FastbootLogicalInfo(tokens[1])
            }

            "create-logical-partition" -> {
                if (tokens.size < 3) return invalidTerminalFormat("fastboot create-logical-partition <partition> <size>")
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "create-logical-partition:$partition:$size"
                TerminalAction.RawFastboot(wire)
            }

            "delete-logical-partition" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot delete-logical-partition <partition>")
                val partition = tokens[1]
                val wire = "delete-logical-partition:$partition"
                TerminalAction.RawFastboot(wire)
            }

            "resize-logical-partition" -> {
                if (tokens.size < 3) return invalidTerminalFormat("fastboot resize-logical-partition <partition> <size>")
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "resize-logical-partition:$partition:$size"
                TerminalAction.RawFastboot(wire)
            }

            "update-super" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot update-super <super.img> [wipe] [superPartition]")
                val file = resolveTerminalFile(tokens[1]) ?: return null
                val wipe = tokens.drop(2).any { it.equals("wipe", ignoreCase = true) || it.equals("--wipe", ignoreCase = true) }
                val explicitSuper = tokens.drop(2).firstOrNull { !it.equals("wipe", ignoreCase = true) && !it.equals("--wipe", ignoreCase = true) }
                val superName = explicitSuper ?: viewModel.currentFastbootDiagnostics()?.superPartitionName ?: "super"
                val wire = "update-super:$superName" + if (wipe) ":wipe" else ""
                TerminalAction.FastbootDownloadAndRun(file, wire)
            }

            "gsi" -> {
                val sub = tokens.getOrNull(1)?.lowercase(Locale.US)
                when (sub) {
                    "status" -> TerminalAction.RawFastboot("gsi:status")
                    "wipe", "disable" -> TerminalAction.RawFastboot("gsi:$sub")
                    else -> invalidTerminalFormat("fastboot gsi <wipe|disable|status>")
                }
            }

            "wipe-super" -> TerminalAction.RawFastboot(clean)

            "snapshot-update" -> {
                val action = tokens.getOrNull(1)?.lowercase(Locale.US)
                when (action) {
                    null -> TerminalAction.RawFastboot("snapshot-update")
                    "cancel" -> TerminalAction.RawFastboot("snapshot-update:cancel")
                    "merge" -> TerminalAction.RawFastboot("snapshot-update:merge")
                    else -> invalidTerminalFormat("fastboot snapshot-update [cancel|merge]")
                }
            }

            "fetch" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot [--slot=<a|b|other>] fetch <partition> [out.img]")
                if (parsedOptions.slot == "all") {
                    viewModel.log("⚠️ fastboot fetch --slot=all is not used: one output file must not mix both slots. Specify --slot=a or --slot=b.")
                    return null
                }
                val partition = tokens[1]
                val defaultName = parsedOptions.slot?.let { "$partition-$it-fetch.img" } ?: "$partition-fetch.img"
                val output = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                TerminalAction.FastbootFetch(partition, output, parsedOptions.slot)
            }

            "erase" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot [--slot=<a|b|all|other>] erase <partition>")
                TerminalAction.FastbootPartitionCommand("erase", tokens[1], parsedOptions.slot)
            }

            "format" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot [--slot=<a|b|all|other>] format <partition>")
                TerminalAction.FastbootPartitionCommand("format", tokens[1], parsedOptions.slot)
            }

            "set_active", "set-active" -> {
                val slot = tokens.getOrNull(1)?.let { normalizeFastbootSlot(it) }
                    ?: parsedOptions.setActive
                    ?: return invalidTerminalFormat("fastboot set_active <a|b>")
                if (slot == "all" || slot == "other") {
                    viewModel.log("❌ set_active requires a concrete slot: a, b, ...")
                    return null
                }
                TerminalAction.RawFastboot("set_active:$slot")
            }

            "reboot" -> {
                val target = tokens.getOrNull(1)?.lowercase(Locale.US)
                val command = when (target) {
                    null, "system" -> "reboot"
                    "bootloader" -> "reboot-bootloader"
                    "recovery" -> "reboot-recovery"
                    "fastboot" -> "reboot-fastboot"
                    else -> clean
                }
                TerminalAction.RawFastboot(command)
            }

            "flashing" -> {
                if (tokens.size < 2) return invalidTerminalFormat("fastboot flashing <unlock|lock|unlock_critical|lock_critical|get_unlock_ability>")
                TerminalAction.RawFastboot(clean)
            }

            "oem" -> TerminalAction.RawFastboot(clean)

            "update", "flashall" -> {
                viewModel.log("⚠️ fastboot $op requires desktop-fastboot batch logic and is not emulated here. Use separate flash commands or ADB Sideload.")
                null
            }

            else -> TerminalAction.RawFastboot(clean)
        }
    }

    private fun parseAdbCommand(cmd: String): TerminalAction? {
        val clean = cmd.trim()
        if (clean.isBlank()) return null
        val tokens = tokenizeCommandLine(clean)
        if (tokens.isEmpty()) return null
        val op = tokens[0].lowercase(Locale.US)

        if (warnIfBatchOrShellSyntax(op, clean, isAdbTab = true)) return null

        // "adb logcat > file.txt": an unquoted > / >> saves the command output on this phone.
        when (val redirect = ShellOutputRedirect.parse(clean)) {
            is ShellOutputRedirect.Result.Invalid -> return invalidTerminalFormat(redirect.message)
            is ShellOutputRedirect.Result.Found -> return parseAdbRedirect(redirect.redirect)
            ShellOutputRedirect.Result.None -> Unit
        }

        return when (op) {
            "status", "devices", "get-state" -> TerminalAction.LocalStatus
            "reports", "open-reports", "report-folder", "reports-folder" -> TerminalAction.OpenReportsFolder

            "shell" -> {
                val shellCommand = clean.substringAfterWord("shell").trim()
                TerminalAction.AdbShell(shellCommand)
            }

            "exec" -> {
                val execCommand = clean.substringAfterWord("exec").trim()
                if (execCommand.isBlank()) {
                    invalidTerminalFormat("adb exec <command>")
                } else {
                    TerminalAction.AdbService("exec:$execCommand")
                }
            }

            "reboot" -> {
                val target = tokens.getOrNull(1)
                TerminalAction.AdbService(AdbServiceCompletionPolicy.normalizeRebootService(target))
            }

            "root", "unroot", "remount", "disable-verity", "enable-verity", "usb" ->
                TerminalAction.AdbService("$op:")

            "tcpip" -> {
                val port = tokens.getOrNull(1) ?: "5555"
                TerminalAction.AdbService("tcpip:$port")
            }

            "raw", "service" -> {
                val service = clean.substringAfterWord(op).trim()
                if (service.isBlank()) {
                    invalidTerminalFormat("adb $op <service>, for example adb raw shell:getprop")
                } else {
                    TerminalAction.AdbService(service)
                }
            }

            "logcat" -> TerminalAction.AdbShell(clean)
            "getprop", "setprop", "pm", "am", "cmd", "settings", "wm", "input", "svc", "dumpsys", "cat", "ls", "cd", "pwd", "id", "su", "sh" ->
                TerminalAction.AdbShell(clean)

            "push" -> {
                if (tokens.size < 3) {
                    invalidTerminalFormat("adb push <local-file> <remote-path>")
                } else {
                    val localPath = resolveTerminalInputPath(tokens[1]) ?: return null
                    val remoteArg = tokens[2]
                    val remotePath = if (localPath.isFile && remoteArg.endsWith("/")) remoteArg + localPath.name else remoteArg
                    TerminalAction.AdbPush(localPath, remotePath)
                }
            }

            "pull" -> {
                if (tokens.size < 2) {
                    invalidTerminalFormat("adb pull <remote-path> [local-file]")
                } else {
                    val remotePath = tokens[1]
                    val defaultName = remotePath.substringAfterLast('/').ifBlank { "adb-pull.bin" }
                    val localFile = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                    TerminalAction.AdbPull(remotePath, localFile)
                }
            }

            "install" -> {
                if (tokens.size < 2) {
                    invalidTerminalFormat("adb install [-r] [-d] [-g] <local.apk|local.apks|local.xapk>")
                } else {
                    val packageToken = tokens.last()
                    val packageFile = resolveTerminalFile(packageToken) ?: return null
                    val lowerName = packageFile.name.lowercase(Locale.US)
                    if (!(lowerName.endsWith(".apk") || lowerName.endsWith(".apks") || lowerName.endsWith(".xapk"))) {
                        viewModel.log("⚠️ File does not look like APK/APKS/XAPK: ${packageFile.name}")
                    }
                    val options = tokens.drop(1).dropLast(1)
                    TerminalAction.AdbInstall(packageFile, options)
                }
            }

            "install-multiple" -> parseAdbInstallMultiple(tokens)

            "sync" -> {
                viewModel.log("ℹ️ adb sync for directories is not implemented yet. Use adb push/adb pull for individual files.")
                null
            }

            "sideload" -> {
                viewModel.log("ℹ️ For sideload, use the ADB Sideload button: it sends the ZIP via sideload-host with progress.")
                null
            }

            else -> TerminalAction.AdbShell(clean)
        }
    }


    /**
     * Turns `adb <shell command> > file` into [TerminalAction.AdbShellToFile]. The command itself
     * is classified by [parseAdbCommand] (so every existing shell shortcut keeps working) and the
     * file is resolved by [resolveTerminalOutputFile], i.e. the same workspace folder, folder
     * creation and absolute/relative path rules as adb pull.
     */
    private fun parseAdbRedirect(redirect: ShellOutputRedirect.Redirect): TerminalAction? {
        if (redirect.command.isBlank()) {
            return invalidTerminalFormat("adb <command> > file.txt, for example adb logcat -d > logcat.txt")
        }
        val inner = parseAdbCommand(redirect.command) ?: return null
        if (inner !is TerminalAction.AdbShell) {
            viewModel.log("❌ Saving output with > works for shell commands only (adb logcat, adb shell <command>). Use adb pull to copy files.")
            return null
        }
        if (inner.command.isBlank()) {
            viewModel.log("❌ An interactive shell cannot be saved to a file. Use: adb shell <command> > file.txt")
            return null
        }
        val outputFile = resolveTerminalOutputFile(redirect.target, "adb-output.txt") ?: return null

        val words = inner.command.trim().split(Regex("\\s+"))
        if (words.firstOrNull().equals("logcat", ignoreCase = true) &&
            words.none { it == "-d" || it == "-t" || it == "-g" || it == "-c" || it == "-S" }
        ) {
            viewModel.log("💡 logcat keeps streaming until you tap Cancel, and what was captured is saved when you stop. Add -d to save a one-time dump instead.")
        }
        if (redirect.command.trimStart().startsWith("shell", ignoreCase = true)) {
            viewModel.log("💡 Output is saved on this phone. To redirect on the target device instead, use: adb shell sh -c \"command > /sdcard/file\"")
        }
        return TerminalAction.AdbShellToFile(inner.command, outputFile, redirect.append)
    }

    private fun parseAdbInstallMultiple(tokens: List<String>): TerminalAction? {
        if (tokens.size < 3) return invalidTerminalFormat("adb install-multiple [-r] [-d] [-g] <base.apk> <split1.apk> [split2.apk...]")

        val options = mutableListOf<String>()
        val files = mutableListOf<File>()
        tokens.drop(1).forEach { token ->
            if (token.lowercase(Locale.US).endsWith(".apk")) {
                val file = resolveTerminalFile(token) ?: return null
                files.add(file)
            } else {
                options.add(token)
            }
        }

        if (files.size < 2) {
            viewModel.log("❌ install-multiple requires at least 2 APKs: base.apk and one or more split/config APKs")
            viewModel.log("💡 Example: adb install-multiple -r base.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk")
            return null
        }

        val hasBaseLikeFile = files.any { it.name.equals("base.apk", ignoreCase = true) || it.name.startsWith("base-", ignoreCase = true) }
        if (!hasBaseLikeFile) {
            viewModel.log("⚠️ base.apk was not found among the files. If the base APK is missing, split APK installation usually fails.")
        }

        return TerminalAction.AdbInstallMultiple(files, options)
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Распознаёт строки, скопированные целиком из .bat/.sh flash-скрипта, а не
     * реальные fastboot/adb-команды. Такие строки нельзя слать на устройство
     * как есть — это управляющий синтаксис ПК-оболочки (echo, метки, циклы,
     * условия, комментарии), у которого просто нет аналога в wire-протоколе.
     * Вместо непонятного отказа устройства показываем понятную подсказку.
     */
    private fun warnIfBatchOrShellSyntax(op: String, clean: String, isAdbTab: Boolean = false): Boolean {
        val isBatchOrShell = op == "@echo" || op == "echo" ||
            op.startsWith(":") || // метка батника, for example :label
            op == "rem" || clean.startsWith("::") || clean.startsWith("#") ||
            op == "pause" || op == "cls" || op == "goto" ||
            op == "if" || op == "for" || op == "exit" || op == "set" ||
            op == "printf" || op == "read" ||
            // "cd" — легитимный шорткат в ADB-вкладке (adb shell cd), но в
            // fastboot-вкладке такой команды нет вообще — там это точно батник.
            (op == "cd" && !isAdbTab)
        if (!isBatchOrShell) return false
        viewModel.log(getString(R.string.terminal_batch_syntax_hint, clean))
        return true
    }

    private fun parseFastbootSizeArgument(raw: String): Long? {
        val token = raw.trim().lowercase(Locale.US)
        if (token.isBlank()) {
            viewModel.log("❌ Size is not specified")
            return null
        }
        val multiplier = when {
            token.endsWith("k") || token.endsWith("kb") -> 1024L
            token.endsWith("m") || token.endsWith("mb") -> 1024L * 1024L
            token.endsWith("g") || token.endsWith("gb") -> 1024L * 1024L * 1024L
            else -> 1L
        }
        val numberPart = token.removeSuffix("kb").removeSuffix("mb").removeSuffix("gb").removeSuffix("k").removeSuffix("m").removeSuffix("g")
        val value = try {
            if (numberPart.startsWith("0x")) numberPart.removePrefix("0x").toLong(16) else numberPart.toLong()
        } catch (_: NumberFormatException) {
            viewModel.log("❌ Invalid size: $raw. Use bytes, 512M, 2G or 0x...")
            return null
        }
        val bytes = try {
            Math.multiplyExact(value, multiplier)
        } catch (_: ArithmeticException) {
            viewModel.log("❌ Size is too large: $raw")
            return null
        }
        if (bytes <= 0L) {
            viewModel.log("❌ Size must be greater than zero")
            return null
        }
        return bytes
    }

    private fun resolveTerminalInputPath(pathText: String): File? {
        val rawPath = pathText.trim().trim('"', '\'')
        if (rawPath.isBlank()) {
            viewModel.log("❌ Local path is not specified")
            return null
        }

        val file = when {
            // Любой абсолютный путь (/sdcard/..., /storage/..., и т.п.) — берём как есть.
            rawPath.startsWith("/") -> File(rawPath)
            else -> {
                if (!ensureWorkspaceReady()) return null
                File(workspacePath, rawPath)
            }
        }

        return if (file.exists() && file.canRead()) {
            file
        } else {
            viewModel.log("❌ Local path was not found or is unavailable: ${file.absolutePath}")
            viewModel.log("💡 For a relative path, put the file/folder in /sdcard/Download/$folderName or import a file with the Import button.")
            null
        }
    }

    private fun resolveTerminalFile(pathText: String): File? {
        val file = resolveTerminalInputPath(pathText) ?: return null
        return if (file.isFile) {
            file
        } else {
            viewModel.log("❌ Expected a file, but a directory was specified: ${file.absolutePath}")
            null
        }
    }

    private fun resolveTerminalOutputFile(pathText: String, defaultName: String): File? {
        if (!ensureWorkspaceReady()) return null
        val rawPath = pathText.trim().trim('"', '\'')

        val candidate = if (rawPath.isBlank()) {
            File(workspacePath, defaultName)
        } else {
            val base = if (rawPath.startsWith("/")) File(rawPath) else File(workspacePath, rawPath)
            when {
                rawPath.endsWith("/") -> File(base, defaultName)
                base.exists() && base.isDirectory -> File(base, defaultName)
                else -> base
            }
        }

        val parent = candidate.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            viewModel.log("❌ Could not create folder for the file: ${parent.absolutePath}")
            return null
        }
        return candidate
    }

    private fun tokenizeCommandLine(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        input.forEach { ch ->
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }
                ch == '\\' -> escaping = true
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun String.substringAfterWord(word: String): String {
        if (!startsWith(word, ignoreCase = true)) return this
        return drop(word.length)
    }

    private fun addToHistory(command: String) {
        if (commandHistory.lastOrNull() != command) {
            commandHistory.add(command)
            if (commandHistory.size > 50) commandHistory.removeAt(0)
        }
        historyIndex = commandHistory.size
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + direction).coerceIn(0, commandHistory.size)
        etCommand.setText(if (historyIndex == commandHistory.size) "" else commandHistory[historyIndex])
        etCommand.setSelection(etCommand.text.length)
    }

    // ─── РАЗРЕШЕНИЯ И ФАЙЛЫ ──────────────────────────────────────────────────

    private fun registerImportLauncher() {
        importFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.log("File import cancelled")
                return@registerForActivityResult
            }
            val uri = result.data?.data
            if (uri == null) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: system file picker did not return a URI")
                return@registerForActivityResult
            }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { error ->
                // Some document providers grant only transient access. The copy
                // below is still valid for this activity result.
                viewModel.logFileOnly(
                    "Persistable import permission unavailable: ${error.javaClass.simpleName}",
                )
            }
            importFirmwareFile(uri)
        }
    }

    private fun registerMiLoginLauncher() {
        miLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            if (activityResult.resultCode != Activity.RESULT_OK) {
                val reason = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_LOGIN_ERROR)?.trim().orEmpty()
                if (reason.isBlank()) {
                    viewModel.log("Mi Account sign-in cancelled by user")
                } else {
                    viewModel.log("❌ Mi Account sign-in did not complete: $reason")
                }
                return@registerForActivityResult
            }
            val passToken = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_PASS_TOKEN)
            val deviceId = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_DEVICE_ID)
            val userId = activityResult.data?.getStringExtra(MiLoginActivity.EXTRA_USER_ID)
            if (passToken.isNullOrEmpty() || deviceId.isNullOrEmpty() || userId.isNullOrEmpty()) {
                viewModel.log("❌ Mi Account sign-in: authorization data was not received")
                return@registerForActivityResult
            }
            viewModel.log("🔑 Signed in. Getting unlockApi session...")
            miAuthExchangeJob?.cancel()
            miAuthExchangeState = MiAuthExchangeState.LOADING
            miAuthExchangeJob = lifecycleScope.launch {
                val exchangeResult = withContext(Dispatchers.IO) {
                    runCatching { MiAccountClient.exchangeToken(passToken, userId, deviceId) }
                }
                exchangeResult.onSuccess { auth ->
                    if (isFinishing || isDestroyed) return@onSuccess
                    miAuth = auth
                    miAuthExchangeState = MiAuthExchangeState.SUCCESS
                    viewModel.log("✅ Mi Account authorized. Region: ${auth.region}, dataCenterZone: ${auth.dataCenterZone} (${auth.zoneSource})")
                    viewModel.log("🔐 unlockApi cookies: ${auth.serviceCookieNames.joinToString(", ")}")
                    buildUnlockPage()
                }.onFailure { error ->
                    if (isFinishing || isDestroyed) return@onFailure
                    miAuthExchangeState = MiAuthExchangeState.ERROR
                    viewModel.log("❌ Token retrieval error: ${error.message ?: error.javaClass.simpleName}")
                    viewModel.log("💡 If you use a VPN, disable it and try again.")
                }
            }
        }
    }

    private fun startMiLogin() {
        viewModel.log("🔑 Opened the official Xiaomi sign-in page for unlockApi")
        miLoginLauncher.launch(Intent(this, MiLoginActivity::class.java))
    }

    private fun startImportFilePicker() {
        if (!ensureWorkspaceReady()) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed", "application/vnd.android.package-archive", "text/plain", "*/*")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            importFileLauncher.launch(intent)
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not open the system file picker: ${e.message}")
        }
    }

    private fun ensureWorkspaceReady(): Boolean {
        if (::workspacePath.isInitialized && workspacePath.exists()) return true
        viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: workspace folder is not ready yet. Grant access to all files and try again.")
        checkPermissions()
        return false
    }

    private fun importFirmwareFile(uri: Uri) {
        if (!ensureWorkspaceReady()) return

        val displayName = sanitizeImportedFileName(queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}")
        val target = uniqueTargetFile(displayName)
        val expectedSize = queryFileSize(uri)
        viewModel.log("File import: $displayName → /sdcard/Download/$folderName/${target.name}")
        expectedSize?.let { viewModel.log("Expected source size: $it bytes") }

        lifecycleScope.launch(Dispatchers.IO) {
            target.parentFile?.listFiles()
                ?.filter { it.name.startsWith(".${target.name}.part-") }
                ?.forEach { stale -> runCatching { stale.delete() } }
            val temp = File(target.parentFile, ".${target.name}.part-${System.currentTimeMillis()}")
            try {
                val copied = contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().buffered(1024 * 1024).use { output ->
                        input.buffered(1024 * 1024).copyTo(output, 1024 * 1024)
                    }
                } ?: throw IllegalStateException("could not open input stream")
                if (expectedSize != null && expectedSize >= 0L && copied != expectedSize) {
                    throw IllegalStateException("source size changed: expected $expectedSize, copied $copied")
                }
                if (copied <= 0L || temp.length() != copied) {
                    throw IllegalStateException("imported file is empty or incomplete")
                }
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("could not prepare the destination path")
                }
                if (!temp.renameTo(target)) {
                    throw IllegalStateException("could not finish the import atomically")
                }
                viewModel.log("✅ File imported: /sdcard/Download/$folderName/${target.name} (${formatFileSize(target.length())})")
            } catch (e: Exception) {
                temp.delete()
                target.delete()
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not import file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }


    private fun queryFileSize(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it >= 0L }
            } else {
                null
            }
        }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sanitizeImportedFileName(name: String): String {
        val safe = name.trim()
            .replace(Regex("[\\/:*?\"<>|\r\n]+"), "_")
            .replace(Regex("\\s+"), "_")
            .take(160)
        return safe.ifBlank { "imported-${System.currentTimeMillis()}" }
    }

    private fun uniqueTargetFile(fileName: String): File {
        var candidate = File(workspacePath, fileName)
        if (!candidate.exists()) return candidate

        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(workspacePath, "$base-$index$ext")
            index++
        }
        return candidate
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes.toDouble() / 1024.0 / 1024.0
        return "%.2f MB".format(Locale.US, mb)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                viewModel.log("⚠️ All-files access is required to read /sdcard/Download/$folderName.")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    })
                } catch (e: Exception) {
                    // Многоуровневый фолбэк для прошивок без точечного экрана.
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:$packageName".toUri()
                            })
                        } catch (e3: Exception) {
                            Toast.makeText(
                                this,
                                getString(R.string.perm_open_settings_manually),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else if (!::workspacePath.isInitialized) {
                initWorkspace()
            }
        } else {
            if (!PermissionGate.hasStorage(this)) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    100
                )
            } else if (!::workspacePath.isInitialized) {
                initWorkspace()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initWorkspace()
                } else {
                    viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: No storage read permission")
                }
            }
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    viewModel.log("Notification permission granted")
                } else {
                    viewModel.log("⚠️ Notifications are disabled. ForegroundService will still start, but Android may hide the notification.")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!OnboardingGate.canEnterMain(this)) {
            redirectToWelcome(intent)
            return
        }
        enableOverlayProtection()
        updateOtgStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager() && !::workspacePath.isInitialized) {
                initWorkspace()
            }
        }
    }

    private fun initWorkspace() {
        // Рабочая папка теперь в системной папке «Загрузки»: /sdcard/Download/NekoFlash
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        workspacePath = File(downloadsDir, folderName)
        if (!workspacePath.exists() && !workspacePath.mkdirs()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: Could not create folder ${workspaceDisplayPath()}")
            return
        }
        viewModel.log("Workspace folder: ${workspaceDisplayPath()}")
        viewModel.configureLogDirectory(workspacePath)
        updateDeviceOverview()
    }

    /** Человекочитаемый путь рабочей папки для логов и диалогов. */
    @Suppress("SdCardPath")
    private fun workspaceDisplayPath(): String = "/sdcard/Download/$folderName"

    private fun showFileSelector(onFileSelected: (File) -> Unit) {
        if (!::workspacePath.isInitialized || !workspacePath.exists()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: Folder is not initialized. Grant permissions.")
            return
        }
        val files = workspacePath
            .listFiles()
            ?.filter { it.isFile && it.canRead() && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.toTypedArray()

        if (files.isNullOrEmpty()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: No readable files in the $folderName folder. Tap Import to add a file through the system picker.")
            return
        }
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_file_choose_title))
                .setItems(files.map { "📄 ${it.name}" }.toTypedArray()) { _, which ->
                    onFileSelected(files[which])
                }
                .setNegativeButton(getString(R.string.cancel_upper), null)
                .show()
        }
    }


    /**
     * Единое меню перезагрузки (BottomSheet). Собирает все варианты reboot
     * в одну панель вместо разбросанных кнопок. Вызывает существующую логику —
     * скрытые кнопки btnReboot* остаются обработчиками той же команды.
     */
    private fun showRebootMenu() {
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: No Fastboot connection for reboot")
            return
        }
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val items = listOf(
            "🔄  " + getString(R.string.layout_reboot_system) to "reboot",
            "⚙\uFE0F  " + getString(R.string.layout_reboot_bootloader) to "reboot-bootloader",
            "🛠\uFE0F  " + getString(R.string.layout_reboot_recovery) to "reboot-recovery",
            "⚡  " + getString(R.string.layout_reboot_fastbootd) to "reboot:fastboot"
        )
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(0, dp(8), 0, dp(16))
        }
        // Заголовок
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.layout_reboot_menu)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        items.forEach { (label, cmd) ->
            container.addView(android.widget.TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(24), dp(16), dp(24), dp(16))
                isClickable = true
                setOnClickListener {
                    viewModel.runFastbootCommand(cmd)
                    dialog.dismiss()
                }
            })
        }
        dialog.setContentView(container)
        dialog.show()
    }

    /**
     * Единое меню отчётов и логов (BottomSheet). Собирает 5 разбросанных
     * функций логов и отчётов в одну панель без скрытой дублирующей страницы.
     */
    private fun showReportsMenu() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val items = listOf(
            getString(R.string.reports_copy_short_summary) to { copyDiagnosticSummary() },
            getString(R.string.reports_open_folder) to { openReportsFolder() },
            getString(R.string.reports_log_actions) to { showLogsMenu() }
        )
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(0, dp(8), 0, dp(16))
        }
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.reports_sheet_title)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        items.forEach { (label, action) ->
            container.addView(android.widget.TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(24), dp(16), dp(24), dp(16))
                isClickable = true
                setOnClickListener {
                    action()
                    dialog.dismiss()
                }
            })
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showLogsMenu() {
        if (!ensureWorkspaceReady()) return
        val compact = viewModel.currentLogFile()
        val trace = viewModel.currentTraceLogFiles().lastOrNull()
        val summaryFile = viewModel.currentSessionSummaryFile()
        val snapshot = viewModel.currentDiagnosticSessionSummary()
        val logsDir = File(workspacePath, "logs")
        val entries = LogMenuPolicy.entries(logsDir.listFiles())
        val activeNames = activeLogFileNames(summaryFile)
        val oldCount = entries.count { LogMenuPolicy.canDelete(it, activeNames) }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.logs_sheet_title)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        root.addView(TextView(this).apply {
            text = buildString {
                appendLine(getString(R.string.logs_overview_build, viewModel.currentBuildId()))
                appendLine(
                    getString(
                        R.string.logs_overview_operations,
                        snapshot.operationsSucceeded,
                        snapshot.operationsStarted,
                        snapshot.warningCount,
                        snapshot.errorCount
                    )
                )
                append(getString(R.string.logs_overview_files, entries.size, oldCount))
            }
            setTextColor("#AAB6C5".toColorInt())
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor("#182330".toColorInt())
        })

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.logs_current_summary) to { showCurrentSessionSummaryDialog() }
        if (compact?.isFile == true) {
            actions += getString(R.string.logs_current_compact) to {
                showLogFileActions(LogMenuPolicy.Entry(compact, LogMenuPolicy.Kind.COMPACT))
            }
        }
        if (trace?.isFile == true) {
            actions += getString(R.string.logs_current_trace) to {
                showLogFileActions(LogMenuPolicy.Entry(trace, LogMenuPolicy.Kind.TRACE))
            }
        }
        if (summaryFile?.isFile == true) {
            actions += getString(R.string.logs_current_json) to {
                showLogFileActions(LogMenuPolicy.Entry(summaryFile, LogMenuPolicy.Kind.SESSION_SUMMARY))
            }
        }
        actions += "${getString(R.string.logs_history)} (${entries.size})" to { showLogHistoryDialog() }
        actions += getString(R.string.logs_open_folder) to { openLogsFolder() }
        actions += getString(R.string.logs_clear_console) to { viewModel.clearLog() }
        if (oldCount > 0) {
            actions += "${getString(R.string.logs_delete_old)} ($oldCount)" to { confirmDeleteOldLogs() }
        }

        actions.forEach { (label, action) ->
            root.addView(TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 14.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(12), dp(15), dp(12), dp(15))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showCurrentSessionSummaryDialog() {
        val snapshot = viewModel.currentDiagnosticSessionSummary()
        val none = getString(R.string.logs_none)
        val text = buildString {
            appendLine(getString(R.string.logs_summary_build, snapshot.buildId))
            appendLine(getString(R.string.logs_summary_session, snapshot.sessionId))
            appendLine(
                getString(
                    R.string.logs_summary_transport_session,
                    snapshot.activeTransportSessionId ?: none
                )
            )
            appendLine()
            appendLine(
                getString(
                    R.string.logs_summary_messages,
                    snapshot.infoCount,
                    snapshot.successCount,
                    snapshot.warningCount,
                    snapshot.errorCount
                )
            )
            appendLine(
                getString(
                    R.string.logs_summary_operations,
                    snapshot.operationsStarted,
                    snapshot.operationsSucceeded,
                    snapshot.operationsFailed,
                    snapshot.operationsCancelled,
                    snapshot.operationsVerificationPending
                )
            )
            appendLine(getString(R.string.logs_summary_last_operation, snapshot.lastOperation ?: none))
            appendLine(getString(R.string.logs_summary_last_outcome, snapshot.lastOperationOutcome ?: none))
            appendLine(getString(R.string.logs_summary_connection, snapshot.lastConnectionMode ?: none))
            snapshot.lastWarning?.let { appendLine(getString(R.string.logs_summary_last_warning, it)) }
            snapshot.lastError?.let { appendLine(getString(R.string.logs_summary_last_error, it)) }
        }.trim()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_current_summary))
            .setMessage(text)
            .setPositiveButton(getString(R.string.copy_upper)) { _, _ ->
                copyTextToClipboard(
                    getString(R.string.logs_clipboard_session_label),
                    text,
                    getString(R.string.logs_session_summary_copied)
                )
            }
            .setNeutralButton(getString(R.string.logs_current_json)) { _, _ ->
                viewModel.currentSessionSummaryFile()?.let {
                    showLogFileActions(LogMenuPolicy.Entry(it, LogMenuPolicy.Kind.SESSION_SUMMARY))
                }
            }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun showLogHistoryDialog() {
        if (!ensureWorkspaceReady()) return
        val logsDir = File(workspacePath, "logs")
        val entries = LogMenuPolicy.entries(logsDir.listFiles())
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.logs_no_files), Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        list.addView(TextView(this).apply {
            text = getString(R.string.logs_history_count, getString(R.string.logs_history), entries.size)
            setTextColor("#E9782B".toColorInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(10), dp(8), dp(12))
        })
        entries.forEach { entry ->
            list.addView(TextView(this).apply {
                text = buildString {
                    append(logKindLabel(entry.kind)).append("  ")
                    append(entry.file.name).append('\n')
                    append(formatLogFileSize(entry.file.length())).append("  •  ")
                    append(java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT
                    ).format(java.util.Date(entry.file.lastModified())))
                }
                setTextColor("#F3F6FA".toColorInt())
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(12), dp(13), dp(12), dp(13))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    showLogFileActions(entry)
                }
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun showLogFileActions(entry: LogMenuPolicy.Entry) {
        if (!entry.file.isFile) {
            Toast.makeText(this, getString(R.string.logs_no_files), Toast.LENGTH_SHORT).show()
            return
        }
        val activeNames = activeLogFileNames(viewModel.currentSessionSummaryFile())
        val canDelete = LogMenuPolicy.canDelete(entry, activeNames)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#121A24".toColorInt())
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        root.addView(TextView(this).apply {
            text = getString(
                R.string.logs_entry_summary,
                logKindLabel(entry.kind),
                entry.file.name,
                formatLogFileSize(entry.file.length())
            )
            setTextColor("#E9782B".toColorInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(4), dp(8), dp(4), dp(12))
        })
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.logs_preview) to { showLogPreview(entry.file) }
        actions += getString(R.string.logs_share_sanitized) to { shareLogFile(entry.file) }
        actions += getString(R.string.logs_copy_path) to {
            copyTextToClipboard(getString(R.string.logs_clipboard_log_label), entry.file.absolutePath, getString(R.string.logs_path_copied))
        }
        if (canDelete) {
            actions += getString(R.string.logs_delete_file) to { confirmDeleteLogFile(entry) }
        } else {
            actions += getString(R.string.logs_active_protected) to {
                Toast.makeText(this, getString(R.string.logs_active_protected), Toast.LENGTH_SHORT).show()
            }
        }
        actions.forEach { (label, action) ->
            root.addView(TextView(this).apply {
                text = label
                setTextColor("#F3F6FA".toColorInt())
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(10), dp(15), dp(10), dp(15))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showLogPreview(file: File) {
        lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { readLogTail(file) }.getOrElse {
                    getString(R.string.logs_read_error, it.message ?: it.javaClass.simpleName)
                }
            }
            val textView = TextView(this@MainActivity).apply {
                text = getString(
                    R.string.logs_preview_content,
                    body,
                    getString(R.string.logs_preview_tail_note)
                )
                setTextColor("#D7DEE8".toColorInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(18), dp(14), dp(18), dp(14))
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(textView) }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(file.name)
                .setView(scroll)
                .setPositiveButton(getString(R.string.logs_share_sanitized)) { _, _ -> shareLogFile(file) }
                .setNegativeButton(getString(R.string.close_upper), null)
                .show()
        }
    }

    private fun readLogTail(file: File, maxBytes: Int = 128 * 1024): String {
        val length = file.length().coerceAtLeast(0L)
        val start = (length - maxBytes).coerceAtLeast(0L)
        val size = (length - start).coerceAtMost(maxBytes.toLong()).toInt()
        val bytes = ByteArray(size)
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            if (size > 0) input.readFully(bytes)
        }
        return (if (start > 0L) "…\n" else "") + bytes.toString(Charsets.UTF_8)
    }

    @Suppress("SdCardPath")
    private fun openLogsFolder() {
        if (!ensureWorkspaceReady()) return
        val logsDir = File(workspacePath, "logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not create folder logs: ${logsDir.absolutePath}")
            return
        }
        val documentId = "primary:Download/$folderName/logs"
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        try {
            viewModel.log(getString(R.string.logs_folder_opened, "/sdcard/Download/$folderName/logs"))
            startActivity(intent)
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not open DocumentsUI for logs: ${e.message ?: e.javaClass.simpleName}")
            copyTextToClipboard(getString(R.string.logs_clipboard_folder_label), logsDir.absolutePath, getString(R.string.logs_path_copied))
        }
    }

    private fun activeLogFileNames(summaryFile: File?): Set<String> = buildSet {
        viewModel.currentLogFiles().forEach { add(it.name) }
        viewModel.currentTraceLogFiles().forEach { add(it.name) }
        summaryFile?.let { add(it.name) }
    }

    private fun confirmDeleteOldLogs() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_delete_old_title))
            .setMessage(getString(R.string.logs_delete_old_message))
            .setPositiveButton(getString(R.string.delete_upper)) { _, _ -> deleteOldLogs() }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun deleteOldLogs() {
        if (!ensureWorkspaceReady()) return
        val summaryFile = viewModel.currentSessionSummaryFile()
        val activeNames = activeLogFileNames(summaryFile)
        val entries = LogMenuPolicy.entries(File(workspacePath, "logs").listFiles())
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                entries.count { entry ->
                    LogMenuPolicy.canDelete(entry, activeNames) && runCatching { entry.file.delete() }.getOrDefault(false)
                }
            }
            viewModel.log(getString(R.string.logs_deleted_count, deleted))
            Toast.makeText(this@MainActivity, getString(R.string.logs_deleted_count, deleted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteLogFile(entry: LogMenuPolicy.Entry) {
        val activeNames = activeLogFileNames(viewModel.currentSessionSummaryFile())
        if (!LogMenuPolicy.canDelete(entry, activeNames)) {
            Toast.makeText(this, getString(R.string.logs_active_protected), Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_delete_file))
            .setMessage(entry.file.name)
            .setPositiveButton(getString(R.string.delete_upper)) { _, _ ->
                val deleted = runCatching { entry.file.delete() }.getOrDefault(false)
                viewModel.log(if (deleted) "Old log deleted: ${entry.file.name}" else "⚠️ Could not delete log: ${entry.file.name}")
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun logKindLabel(kind: LogMenuPolicy.Kind): String = getString(
        when (kind) {
            LogMenuPolicy.Kind.COMPACT -> R.string.logs_kind_compact
            LogMenuPolicy.Kind.TRACE -> R.string.logs_kind_trace
            LogMenuPolicy.Kind.SESSION_SUMMARY -> R.string.logs_kind_summary
        }
    )

    private fun formatLogFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * Action-first Mi Unlock page: account state, Fastboot precondition and unlock action.
     */
    private fun runMiUnlockFromUi(auth: MiAccountClient.AuthResult) {
        viewModel.runMiUnlock(
            auth = auth,
            onClearInfo = { _, _ -> },
            onAuthExpired = {
                miAuth = null
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                buildUnlockPage()
                Toast.makeText(this, getString(R.string.mi_unlock_session_expired), Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun isBootloaderUnlocked(): Boolean =
        viewModel.fastbootDiagnostics.value?.unlocked?.trim()?.equals("yes", ignoreCase = true) == true

    private fun isFastbootConnected(): Boolean =
        viewModel.connectionState.value == DeviceViewModel.ConnectionState.FASTBOOT

    private fun unlockStatusSummary(): String {
        val diagnostics = viewModel.fastbootDiagnostics.value
        val unknown = getString(R.string.device_bool_unknown)
        return getString(
            R.string.mi_unlock_status_summary,
            diagnostics?.product?.takeIf { it.isNotBlank() } ?: unknown,
            diagnostics?.currentSlot?.takeIf { it.isNotBlank() } ?: unknown,
            diagnostics?.unlocked?.takeIf { it.isNotBlank() }?.let(::formatDeviceBoolean) ?: unknown,
            diagnostics?.secure?.takeIf { it.isNotBlank() }?.let(::formatDeviceBoolean) ?: unknown,
        )
    }

    private fun buildUnlockPage() {
        val container = findViewById<android.widget.LinearLayout>(R.id.unlockContainer)
        container.removeAllViews()

        fun title(text: String, color: String = "#E9782B") = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(color.toColorInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(16), dp(6), dp(8))
            letterSpacing = 0.08f
        }
        fun card(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor("#121A24".toColorInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), "#324052".toColorInt())
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        fun body(text: String, color: String = "#AEB8C5") = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(color.toColorInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }

        val unlocked = isBootloaderUnlocked()
        val fastbootReady = isFastbootConnected()
        val operationActive = viewModel.operationActive.value == true

        container.addView(title(getString(R.string.mi_unlock_page_title), "#E9782B"))

        container.addView(card().apply {
            addView(body(getString(R.string.mi_unlock_page_subtitle), "#F3F6FA"))
            addView(body(getString(R.string.mi_unlock_data_warning), "#F2B766"))
        })

        container.addView(title(getString(R.string.mi_unlock_account_section)))
        val auth = miAuth
        if (auth == null) {
            container.addView(card().apply {
                addView(body(getString(R.string.mi_unlock_sign_in_hint), "#F3F6FA"))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = getString(R.string.mi_unlock_sign_in_button)
                    isAllCaps = false
                    setTextColor("#080D13".toColorInt())
                    setBackgroundColor("#E98B49".toColorInt())
                    setOnClickListener { startMiLogin() }
                })
            })
        } else {
            container.addView(card().apply {
                addView(body(getString(R.string.mi_unlock_authorized, auth.userId), "#69C779"))
                addView(body(getString(R.string.mi_unlock_region_zone, auth.region, auth.dataCenterZone, auth.zoneSource), "#AEB8C5"))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = getString(R.string.mi_unlock_change_zone_button)
                    isAllCaps = false
                    setTextColor("#F3F6FA".toColorInt())
                    setBackgroundColor("#192431".toColorInt())
                    setOnClickListener {
                        val zoneItems = MiAccountClient.dataCenterZones().toTypedArray()
                        val checked = zoneItems.indexOf(auth.dataCenterZone).coerceAtLeast(0)
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.mi_unlock_zone_title))
                            .setSingleChoiceItems(zoneItems, checked) { dialog, which ->
                                val updated = MiAccountClient.withDataCenterZone(auth, zoneItems[which]).copy(zoneSource = "manual")
                                miAuth = updated
                                viewModel.log("🌍 dataCenterZone changed manually: ${updated.dataCenterZone}")
                                dialog.dismiss()
                                buildUnlockPage()
                            }
                            .setNegativeButton(getString(R.string.cancel_upper), null)
                            .show()
                    }
                })
                addView(android.widget.Button(this@MainActivity).apply {
                    text = getString(R.string.mi_unlock_sign_out_switch)
                    isAllCaps = false
                    setTextColor("#F3F6FA".toColorInt())
                    setBackgroundColor("#192431".toColorInt())
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.mi_unlock_sign_out_title))
                            .setMessage(getString(R.string.mi_unlock_sign_out_message, auth.userId, auth.region))
                            .setNegativeButton(getString(R.string.cancel_upper), null)
                            .setPositiveButton(getString(R.string.mi_unlock_sign_out)) { _, _ ->
                                miAuth = null
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                buildUnlockPage()
                            }
                            .show()
                    }
                })
            })

            container.addView(title(getString(R.string.mi_unlock_bootloader_section), if (unlocked) "#69C779" else "#E9782B"))
            container.addView(card().apply {
                if (unlocked) {
                    addView(body(getString(R.string.mi_unlock_bootloader_unlocked), "#69C779"))
                    addView(body(unlockStatusSummary(), "#AEB8C5"))
                    addView(body(getString(R.string.mi_unlock_repeat_not_needed), "#F3F6FA"))
                } else {
                    addView(body(getString(R.string.mi_unlock_fastboot_requirement), "#F3F6FA"))
                    addView(body(unlockStatusSummary(), if (fastbootReady) "#AEB8C5" else "#F2B766"))
                    addView(body(getString(R.string.mi_unlock_data_warning_short), "#F2B766"))
                    addView(android.widget.Button(this@MainActivity).apply {
                        val canRunUnlock = fastbootReady && !operationActive
                        text = when {
                            operationActive -> getString(R.string.mi_unlock_operation_running)
                            !fastbootReady -> getString(R.string.mi_unlock_connect_fastboot)
                            else -> getString(R.string.mi_unlock_action)
                        }
                        isAllCaps = false
                        isEnabled = canRunUnlock
                        alpha = if (canRunUnlock) 1.0f else 0.55f
                        setTextColor("#080D13".toColorInt())
                        setBackgroundColor(if (canRunUnlock) "#E9782B".toColorInt() else "#5D6570".toColorInt())
                        setOnClickListener {
                            if (canRunUnlock) runMiUnlockFromUi(auth)
                        }
                    })
                }
            })
        }
    }

    private fun buildSettingsPage() {
        val container = findViewById<android.widget.LinearLayout>(R.id.settingsContainer)
        container.removeAllViews()

        fun sectionTitle(text: String) = android.widget.TextView(this).apply {
            this.text = text
            setTextColor("#E9782B".toColorInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(18), dp(6), dp(8))
            letterSpacing = 0.1f
        }
        fun card(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor("#121A24".toColorInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), "#324052".toColorInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun row(text: String, sub: String? = null, onClick: () -> Unit) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(android.widget.TextView(this@MainActivity).apply {
                this.text = text
                setTextColor("#F3F6FA".toColorInt())
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            if (sub != null) addView(android.widget.TextView(this@MainActivity).apply {
                this.text = sub
                setTextColor("#AEB8C5".toColorInt())
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            setOnClickListener { onClick() }
        }

        // ── Система ──
        container.addView(sectionTitle(getString(R.string.settings_section_system)))
        val sysCard = card()
        sysCard.addView(row(getString(R.string.settings_open_language)) { showLanguageDialog() })
        sysCard.addView(row(getString(R.string.settings_open_permissions)) { showPermissionsDialog() })
        sysCard.addView(row(getString(R.string.settings_open_battery)) { showBatteryOptimizationDialog() })
        container.addView(sysCard)

        // ── Сервис ──
        container.addView(sectionTitle(getString(R.string.settings_section_service)))
        val svcCard = card()
        svcCard.addView(row(getString(R.string.settings_clear_workspace),
            getString(R.string.settings_clear_workspace_sub)) { confirmClearWorkspace() })
        svcCard.addView(row(getString(R.string.settings_about),
            getString(R.string.settings_about_sub, appVersionName())) { showAboutDialog() })
        container.addView(svcCard)
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    } catch (e: Exception) { "—" }

    private fun confirmClearWorkspace() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_clear_workspace))
            .setMessage(getString(R.string.settings_clear_workspace_confirm, workspacePath.absolutePath))
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.settings_clear_workspace_do)) { _, _ ->
                var count = 0
                try {
                    workspacePath.listFiles()?.forEach { if (it.isFile && it.delete()) count++ }
                } catch (error: Exception) {
                    android.util.Log.w("NekoFlash", "Unable to enumerate or delete workspace files", error)
                    viewModel.log("⚠️ Workspace folder was partially cleared: ${error.javaClass.simpleName}")
                }
                viewModel.log(resources.getQuantityString(R.plurals.settings_clear_workspace_done, count, count))
            }
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_about))
            .setMessage(getString(R.string.settings_about_body, appVersionName()))
            .setPositiveButton(getString(R.string.close_upper), null)
            .show()
    }

    private data class QuickFlashSlotTargetInfo(
        val hasSlots: Boolean?,
        val currentSlot: String?,
        val slotCount: Int
    )

    private fun startDirectFlash(partition: String) {
        chooseQuickFlashSlotTarget(partition) { slot ->
            showFileSelector { file ->
                viewModel.runFlash(partition, file, slot)
            }
        }
    }

    private fun chooseQuickFlashSlotTarget(partition: String, onSlotChosen: (String?) -> Unit) {
        val normalized = partition.trim().lowercase(Locale.US)
        if (normalized.isBlank() || quickFlashPartitionAlreadySuffixed(normalized)) {
            onSlotChosen(null)
            return
        }

        val proto = viewModel.fastbootProtocol
        if (proto?.isConnected != true) {
            // Keep the existing offline flow: allow file selection, then runFlash will
            // report the real Fastboot connection error.
            onSlotChosen(null)
            return
        }

        val snapshotInfo = quickFlashSlotInfoFromSnapshot(normalized)
        when (snapshotInfo.hasSlots) {
            true -> showQuickFlashSlotDialog(normalized, snapshotInfo, onSlotChosen)
            false -> onSlotChosen(null)
            null -> probeQuickFlashSlotTarget(normalized, snapshotInfo, onSlotChosen)
        }
    }

    private fun quickFlashPartitionAlreadySuffixed(partition: String): Boolean {
        val name = partition.substringBefore(':').lowercase(Locale.US)
        return name.endsWith("_a") || name.endsWith("_b")
    }

    private fun normalizeQuickFlashSlot(raw: String?): String? =
        raw?.trim()
            ?.removePrefix("_")
            ?.lowercase(Locale.US)
            ?.takeIf { it.length == 1 && it[0] in 'a'..'z' }

    private fun quickFlashBaseName(partition: String): String =
        FastbootPartitionInventory.baseName(partition.substringBefore(':').trim().lowercase(Locale.US))

    private fun quickFlashSlotInfoFromSnapshot(partition: String): QuickFlashSlotTargetInfo {
        val base = quickFlashBaseName(partition)
        val inventory = viewModel.currentFastbootPartitionInventory()
        val diagnostics = viewModel.currentFastbootDiagnostics()
        val currentSlot = normalizeQuickFlashSlot(inventory?.currentSlot)
            ?: normalizeQuickFlashSlot(diagnostics?.currentSlot)
        val slotCount = diagnostics?.slotCount?.trim()?.toIntOrNull()
            ?: inventory?.variables?.get("slot-count")?.trim()?.toIntOrNull()
            ?: 0

        if (inventory?.topology == FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY) {
            return QuickFlashSlotTargetInfo(hasSlots = false, currentSlot = currentSlot, slotCount = slotCount)
        }

        val familyHasSlot = inventory?.slotFamilies
            ?.entries
            ?.firstOrNull { it.key.equals(base, ignoreCase = true) }
            ?.value
        val entryHasSlot = inventory?.entries
            ?.firstOrNull {
                it.name.equals(base, ignoreCase = true) ||
                    it.baseName.equals(base, ignoreCase = true)
            }
            ?.hasSlot
        val hasSlots = familyHasSlot ?: entryHasSlot
        return QuickFlashSlotTargetInfo(
            hasSlots = hasSlots,
            currentSlot = currentSlot,
            slotCount = slotCount
        )
    }

    private fun probeQuickFlashSlotTarget(
        partition: String,
        snapshotInfo: QuickFlashSlotTargetInfo,
        onSlotChosen: (String?) -> Unit
    ) {
        val base = quickFlashBaseName(partition)
        lifecycleScope.launch(Dispatchers.IO) {
            val proto = viewModel.fastbootProtocol
            val probedHasSlots = proto
                ?.takeIf { it.isConnected }
                ?.let {
                    runCatching {
                        it.getVar("has-slot:$base")?.trim()?.equals("yes", ignoreCase = true)
                    }.getOrNull()
                }
            val probedCurrentSlot = proto
                ?.takeIf { it.isConnected }
                ?.let { runCatching { normalizeQuickFlashSlot(it.getVar("current-slot")) }.getOrNull() }
            val probedSlotCount = proto
                ?.takeIf { it.isConnected }
                ?.let { runCatching { it.getVar("slot-count")?.trim()?.toIntOrNull() }.getOrNull() }
                ?: 0

            withContext(Dispatchers.Main) {
                val info = QuickFlashSlotTargetInfo(
                    hasSlots = probedHasSlots,
                    currentSlot = probedCurrentSlot ?: snapshotInfo.currentSlot,
                    slotCount = if (probedSlotCount > 0) probedSlotCount else snapshotInfo.slotCount
                )
                if (info.hasSlots == true) {
                    showQuickFlashSlotDialog(partition, info, onSlotChosen)
                } else {
                    onSlotChosen(null)
                }
            }
        }
    }

    private fun showQuickFlashSlotDialog(
        partition: String,
        info: QuickFlashSlotTargetInfo,
        onSlotChosen: (String?) -> Unit
    ) {
        val current = info.currentSlot
        val currentTarget = current?.let { "${quickFlashBaseName(partition)}_$it" } ?: "${quickFlashBaseName(partition)}_<current>"
        val lastSlot = if (info.slotCount > 0) {
            ('a'.code + (info.slotCount - 1).coerceAtLeast(0)).toChar()
        } else {
            'b'
        }
        val allLabel = if (info.slotCount > 1) {
            getString(R.string.quick_flash_all_slots_range, lastSlot.toString())
        } else {
            getString(R.string.quick_flash_all_slots)
        }
        val labels = arrayOf(
            getString(
                R.string.quick_flash_active_slot_target,
                current ?: getString(R.string.quick_flash_current_slot),
                currentTarget,
            ),
            allLabel,
        )
        // Do not mix AlertDialog.setMessage() with setItems() here: on some
        // Android/Material theme combinations the message view can consume the
        // dialog content area and leave the slot choices invisible. Keep the
        // explanatory text and the two target choices in one explicit custom view.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.quick_flash_slot_dialog_message)
            setTextColor("#AEB8C5".toColorInt())
            textSize = 16f
            setPadding(0, dp(4), 0, dp(16))
        })
        val activeSlotButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = labels[0]
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val allSlotsButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = labels[1]
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        }
        content.addView(activeSlotButton, buttonParams)
        content.addView(allSlotsButton, LinearLayout.LayoutParams(buttonParams))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quick_flash_slot_dialog_title, partition))
            .setView(content)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .create()

        activeSlotButton.setOnClickListener {
            dialog.dismiss()
            onSlotChosen(current)
        }
        allSlotsButton.setOnClickListener {
            dialog.dismiss()
            onSlotChosen("all")
        }
        dialog.show()
    }

    private fun showManualQuickFlashTargetDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.quick_flash_manual_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quick_flash_manual_title))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                val partition = input.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
                if (!PARTITION_NAME_PATTERN.matches(partition)) {
                    viewModel.log("❌ Invalid Fastboot partition name: $partition")
                    return@setPositiveButton
                }
                startDirectFlash(partition)
            }
            .show()
    }

    private fun refreshDeviceDataFromUi() {
        viewModel.refreshFastbootDiagnostics()
        deviceOverviewHandler.removeCallbacks(shortDeviceOverviewRefresh)
        deviceOverviewHandler.removeCallbacks(finalDeviceOverviewRefresh)
        deviceOverviewHandler.postDelayed(shortDeviceOverviewRefresh, 800L)
        deviceOverviewHandler.postDelayed(finalDeviceOverviewRefresh, 2500L)
    }



    private fun isOpenReportsCommand(rawLower: String): Boolean {
        return rawLower == "reports" ||
            rawLower == "open reports" ||
            rawLower == "open-reports" ||
            rawLower == "reports open" ||
            rawLower == "report folder" ||
            rawLower == "reports folder" ||
            rawLower == "adb reports" ||
            rawLower == "fastboot reports"
    }

    private fun copyDiagnosticSummary() {
        val inventory = viewModel.currentFastbootPartitionInventory()
        val fastboot = viewModel.currentFastbootDiagnostics()
        val adb = viewModel.currentAdbDiagnostics()
        val state = viewModel.connectionState.value ?: DeviceViewModel.ConnectionState.NONE
        val text = buildString {
            appendLine("NekoFlash: ${viewModel.currentBuildId()}")
            appendLine("Session ID: ${viewModel.currentTransportSessionId() ?: "none"}")
            appendLine("Mode: $state")
            appendLine("Connection: ${viewModel.currentConnectionInfo() ?: "none"}")
            appendLine("Fastboot session: ${fastboot?.sessionState ?: "none"}")
            appendLine("Fastboot broken reason: ${fastboot?.brokenReasonCode ?: "none"}")
            appendLine("ADB peer: ${adb?.peerMode ?: "none"}")
            appendLine("ADB dispatcher: running=${adb?.dispatcherRunning ?: false}, queue=${adb?.queuedPackets ?: 0}, packets=${adb?.packetsRead ?: 0}, failures=${adb?.readerFailures ?: 0}")
            appendLine("Topology: ${inventory?.topology ?: "unknown"}")
            appendLine("Inventory: ${inventory?.entries?.size ?: 0} partitions, warnings=${inventory?.warnings?.size ?: 0}")
        }.trim()
        copyTextToClipboard(
            getString(R.string.diagnostic_summary_clipboard_label),
            text,
            getString(R.string.reports_short_summary_copied)
        )
    }


    private fun openReportsFolder() {
        if (!ensureWorkspaceReady()) return
        val reportsDir = File(workspacePath, "reports")
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not create folder reports: ${reportsDir.absolutePath}")
            return
        }

        val documentId = "primary:Download/$folderName/reports"
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

        try {
            viewModel.log(
                getString(R.string.reports_folder_opening, "${workspaceDisplayPath()}/reports")
            )
            startActivity(intent)
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not open DocumentsUI for reports: ${e.message ?: e.javaClass.simpleName}")
            copyTextToClipboard(
                getString(R.string.reports_clipboard_label),
                reportsDir.absolutePath,
                getString(R.string.reports_path_copied)
            )
        }
    }

    private fun shareGenericFile(file: File, mimeType: String, subject: String): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.share_file_text, file.name))
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_file_chooser)))
            true
        } catch (e: Exception) {
            viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not send file: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun shareLogFile(file: File) {
        lifecycleScope.launch {
            val sanitized = try {
                withContext(Dispatchers.IO) {
                    SanitizedLogShare.create(
                        source = file,
                        outputDir = File(cacheDir, "shared-logs"),
                        scope = ReportSanitizer.Scope(
                            workspace = workspacePath,
                            logFile = file,
                            packageName = packageName
                        )
                    )
                }
            } catch (e: Exception) {
                viewModel.log(DiagnosticLogPolicy.Level.ERROR, "ERROR: could not prepare a sanitized log copy: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }

            viewModel.log(getString(R.string.logs_sanitized_share_created))
            if (
                shareGenericFile(
                    sanitized,
                    "text/plain",
                    getString(R.string.logs_sanitized_share_subject)
                )
            ) {
                // ACTION_SEND does not report when the receiving app has finished reading.
                // Keep the cache file briefly, then remove it; stale files are also
                // cleaned on every subsequent share.
                Handler(Looper.getMainLooper()).postDelayed(
                    { sanitized.delete() },
                    15L * 60L * 1000L
                )
            } else {
                sanitized.delete()
            }
        }
    }

    private fun copyTextToClipboard(label: String, text: String, logMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        viewModel.log(logMessage)
    }


    private fun enableOverlayProtection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setHideOverlayWindows(true)
                if (!overlayProtectionLogged) {
                    viewModel.log(getString(R.string.overlay_protection_enabled))
                    overlayProtectionLogged = true
                }
            } catch (e: Exception) {
                if (!overlayProtectionLogged) {
                    viewModel.log(getString(R.string.overlay_protection_error, e.message ?: e.javaClass.simpleName))
                    overlayProtectionLogged = true
                }
            }
        } else if (!overlayProtectionLogged) {
            viewModel.log(getString(R.string.overlay_protection_unsupported))
            overlayProtectionLogged = true
        }
    }

    private fun showPermissionsDialog() {
        val notifications = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            getString(R.string.permission_status_not_required)
        } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) getString(R.string.permission_status_granted) else getString(R.string.permission_status_not_granted)
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) getString(R.string.permission_status_granted) else getString(R.string.permission_status_not_granted)
        }

        val powerManager = getSystemService(PowerManager::class.java)
        val battery = if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_optional)
        }

        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getString(R.string.permission_status_enabled)
        } else {
            getString(R.string.permission_status_not_supported)
        }

        val message = getString(
            R.string.permissions_dialog_message,
            storage,
            notifications,
            battery,
            overlay
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_permissions_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok_understood_upper), null)
            .setNeutralButton(getString(R.string.open_app_settings_upper)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:$packageName".toUri()
                    })
                } catch (e: Exception) {
                    viewModel.log(getString(R.string.app_settings_open_error, e.message ?: e.javaClass.simpleName))
                }
            }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun logBatteryOptimizationState() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.log("⚠️ For long flashing, it is recommended to disable battery optimization: Battery button.")
        }
    }

    private fun showBatteryOptimizationDialog() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.log(getString(R.string.battery_optimization_already_disabled))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_battery_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.open_upper)) { _, _ -> requestDisableBatteryOptimization() }
            .setNegativeButton(getString(R.string.later_upper), null)
            .show()
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimization() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                viewModel.log(getString(R.string.battery_optimization_open_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }


    private fun applySavedLanguage() {
        val tag = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE_TAG, "") ?: ""
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_russian),
            getString(R.string.language_english)
        )
        val tags = arrayOf("", "ru", "en")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTag = prefs.getString(PREF_LANGUAGE_TAG, "") ?: ""
        val checked = tags.indexOf(currentTag).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language_dialog_title))
            .setMessage(getString(R.string.language_dialog_message))
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val selectedTag = tags[which]
                prefs.edit { putString(PREF_LANGUAGE_TAG, selectedTag) }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedTag))
                dialog.dismiss()
                viewModel.log(getString(R.string.language_changed))
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    // ─── UI ──────────────────────────────────────────────────────────────────


    private fun formatDeviceBoolean(value: String): String = when (value.trim().lowercase(Locale.US)) {
        "yes", "true", "1" -> getString(R.string.device_bool_yes)
        "no", "false", "0" -> getString(R.string.device_bool_no)
        "", "unknown" -> getString(R.string.device_bool_unknown)
        else -> value
    }

    @Suppress("SdCardPath")
    private fun updateDeviceOverview() {
        val diagnostics = viewModel.currentFastbootDiagnostics()
        val inventory = viewModel.currentFastbootPartitionInventory()
        val connectionInfo = viewModel.currentConnectionInfo()
        val modeText = connectionStatusPresentation().first.removePrefix("● ")

        val product = diagnostics?.product
            ?: inventory?.product
            ?: extractConnectionField(connectionInfo, "Device")
            ?: "—"
        val slot = when (inventory?.topology) {
            FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY -> getString(R.string.device_topology_legacy)
            FastbootPartitionInventory.SlotTopology.A_B ->
                diagnostics?.currentSlot ?: getString(R.string.device_slot_current_unknown)
            FastbootPartitionInventory.SlotTopology.UNKNOWN, null -> diagnostics?.currentSlot ?: "—"
        }
        val unlocked = diagnostics?.unlocked?.let(::formatDeviceBoolean) ?: "—"
        val maxDownload = diagnostics?.maxDownloadSizeRaw?.let { raw ->
            val bytes = diagnostics.maxDownloadSizeBytes
            if (bytes != null && bytes > 0L) "$raw / ${formatFileSize(bytes)}" else raw
        } ?: "—"

        val serialno = diagnostics?.serialno?.let { getString(R.string.device_extra_serial, it) } ?: ""
        val slotExtra = buildString {
            if (inventory?.topology != FastbootPartitionInventory.SlotTopology.LEGACY_A_ONLY) {
                diagnostics?.slotCount?.let { append(getString(R.string.device_extra_slot_count, it)) }
                diagnostics?.slotSuffix?.let { append(getString(R.string.device_extra_slot_suffix, it)) }
            }
        }
        val slotDisplay = if (slotExtra.isNotBlank()) "$slot$slotExtra" else slot
        val vbl = diagnostics?.versionBootloader?.let {
            getString(R.string.device_extra_bootloader_version, it)
        } ?: ""

        findViewById<TextView>(R.id.tvDeviceModeValue).text =
            getString(R.string.device_mode_value, modeText)
        findViewById<TextView>(R.id.tvDeviceProductValue).text =
            getString(R.string.device_product_value, product, serialno, vbl)
        findViewById<TextView>(R.id.tvDeviceSlotValue).text =
            getString(R.string.device_slot_value, slotDisplay)
        findViewById<TextView>(R.id.tvDeviceUnlockedValue).text =
            getString(R.string.device_bootloader_value, unlocked)
        val maxFetch = diagnostics?.maxFetchSizeRaw?.let { raw ->
            val bytes = diagnostics.maxFetchSizeBytes
            if (bytes != null && bytes > 0L) "$raw / ${formatFileSize(bytes)}" else raw
        }
        val superPart = diagnostics?.superPartitionName?.let {
            getString(R.string.device_extra_super, it)
        } ?: ""
        val inventoryPart = inventory?.let { snapshot ->
            val normal = snapshot.entries.count { it.risk == FastbootPartitionInventory.RiskTier.NORMAL }
            val advanced = snapshot.entries.count { it.risk == FastbootPartitionInventory.RiskTier.ADVANCED }
            val critical = snapshot.entries.count { it.risk == FastbootPartitionInventory.RiskTier.CRITICAL }
            val logical = snapshot.entries.count { it.storage == FastbootPartitionInventory.StorageKind.LOGICAL }
            val physical = snapshot.entries.count { it.storage == FastbootPartitionInventory.StorageKind.PHYSICAL }
            val incomplete = snapshot.entries.count { it.missingFields.isNotEmpty() }
            getString(
                R.string.device_inventory_summary,
                snapshot.entries.size,
                normal,
                advanced,
                critical,
                physical,
                logical,
                incomplete
            )
        } ?: ""
        findViewById<TextView>(R.id.tvDeviceMaxDownloadValue).text = getString(
            R.string.device_max_download_value,
            maxDownload,
            maxFetch?.let { getString(R.string.device_extra_fetch, it) } ?: "",
            superPart,
            inventoryPart
        )
        val session = viewModel.currentTransportSessionId() ?: "—"
        val transportInfo = connectionInfo
            ?.split(" | ")
            ?.drop(1)
            ?.joinToString(" • ")
            ?.ifBlank { null }
            ?: "—"
        findViewById<TextView>(R.id.tvDeviceWorkspaceValue).text = getString(
            R.string.device_transport_value,
            transportInfo,
            session
        )
    }



    private fun extractConnectionField(info: String?, field: String): String? {
        if (info.isNullOrBlank()) return null
        val marker = "$field:"
        val start = info.indexOf(marker)
        if (start < 0) return null
        val after = info.substring(start + marker.length).trim()
        return after.substringBefore("|").trim().ifBlank { null }
    }

    private fun restoreWindowState(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val target = sanitizeWindow(
            savedInstanceState?.getString(STATE_SELECTED_WINDOW)
                ?: prefs.getString(PREF_LAST_WINDOW, "home")
        )
        restoringWindowState = true
        try {
            switchTab(target)
        } finally {
            restoringWindowState = false
        }
        if (target == "unlock") buildUnlockPage()
    }

    private fun sanitizeWindow(value: String?): String = when (value) {
        "home", "fastboot", "adb", "unlock", "settings" -> value
        else -> "home"
    }

    private fun openConsole(requestCommandFocus: Boolean) {
        consoleDockController.open(requestCommandFocus = requestCommandFocus)
    }

    private fun openOperationCenter() {
        switchTab("home")
        val home = findViewById<ScrollView>(R.id.pageHome)
        home.post {
            home.smoothScrollTo(0, cardOperationCenter.top.coerceAtLeast(0))
        }
    }

    private fun requestOperationCancelFromUi() {
        if (viewModel.operationActive.value != true || operationCancelRequested) return
        operationCancelRequested = true
        updateOperationCenter(viewModel.logSnapshot())
        renderOperationProgressUi(viewModel.operationProgress.value)
        viewModel.cancelActiveOperation()
    }

    private fun operationProgressForUi(
        rawProgress: DeviceViewModel.OperationProgress?
    ): DeviceViewModel.OperationProgress? =
        if (operationStoredProgressHidden) null else rawProgress

    private fun switchTab(tab: String) {
        when (tab) {
            "reports" -> {
                showReportsMenu()
                return
            }
        }

        val target = sanitizeWindow(tab)
        tabController.switchTab(target)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(PREF_LAST_WINDOW, tabController.selectedWindow)
        }

        // The unified Console is a persistent Bottom Sheet, never a separate page.
        findViewById<View>(R.id.consolePanel).visibility = View.VISIBLE
    }


    /**
     * Обновляет OTG-индикатор. Прямого API «OTG вкл/выкл» в Android нет, поэтому
     * статус выводится косвенно: поддержка USB Host (железо) + наличие устройств
     * в deviceList. Если OTG отключён в системе, deviceList пуст даже при кабеле —
     * пользователь видит подсказку включить OTG.
     */
    private fun updateOtgStatus() {
        val tv = tvOtgStatus ?: return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)) {
            tv.text = getString(R.string.otg_status_unsupported)
            tv.setTextColor("#E06C75".toColorInt())
            return
        }
        val hasDevices = try { usbManager.deviceList.isNotEmpty() } catch (_: Exception) { false }
        if (hasDevices) {
            tv.text = getString(R.string.otg_status_active)
            tv.setTextColor("#69C779".toColorInt())
        } else {
            tv.text = getString(R.string.otg_status_no_device)
            tv.setTextColor("#F2B766".toColorInt())
        }
    }

    private fun connectionStatusPresentation(): Pair<String, String> {
        val transport = when (viewModel.connectionState.value ?: DeviceViewModel.ConnectionState.NONE) {
            DeviceViewModel.ConnectionState.NONE -> ConnectionModeUiPolicy.Transport.NONE
            DeviceViewModel.ConnectionState.CONNECTING -> ConnectionModeUiPolicy.Transport.CONNECTING
            DeviceViewModel.ConnectionState.FASTBOOT -> ConnectionModeUiPolicy.Transport.FASTBOOT
            DeviceViewModel.ConnectionState.ADB -> ConnectionModeUiPolicy.Transport.ADB
            DeviceViewModel.ConnectionState.ERROR -> ConnectionModeUiPolicy.Transport.ERROR
        }
        val adbMode = when (viewModel.adbPeerMode.value) {
            AdbProtocol.PeerMode.DEVICE -> ConnectionModeUiPolicy.AdbMode.SYSTEM
            AdbProtocol.PeerMode.RECOVERY -> ConnectionModeUiPolicy.AdbMode.RECOVERY
            AdbProtocol.PeerMode.SIDELOAD -> ConnectionModeUiPolicy.AdbMode.SIDELOAD
            AdbProtocol.PeerMode.UNKNOWN -> ConnectionModeUiPolicy.AdbMode.UNKNOWN
            null -> null
        }
        val fastbootd = viewModel.fastbootDiagnostics.value?.isUserspace?.let { raw ->
            when {
                raw.equals("yes", ignoreCase = true) -> true
                raw.equals("no", ignoreCase = true) -> false
                else -> null
            }
        }

        return when (ConnectionModeUiPolicy.resolve(transport, adbMode, fastbootd)) {
            ConnectionModeUiPolicy.DisplayMode.NO_DEVICE -> getString(R.string.status_no_device) to "#758397"
            ConnectionModeUiPolicy.DisplayMode.CONNECTING -> getString(R.string.status_connecting) to "#F2B766"
            ConnectionModeUiPolicy.DisplayMode.FASTBOOT_BOOTLOADER -> getString(R.string.status_fastboot) to "#E9782B"
            ConnectionModeUiPolicy.DisplayMode.FASTBOOTD -> getString(R.string.status_fastbootd) to "#E98B49"
            ConnectionModeUiPolicy.DisplayMode.FASTBOOT_UNKNOWN -> getString(R.string.status_fastboot_unknown) to "#AEB8C5"
            ConnectionModeUiPolicy.DisplayMode.ADB_SYSTEM -> getString(R.string.status_adb_system) to "#69C779"
            ConnectionModeUiPolicy.DisplayMode.ADB_RECOVERY -> getString(R.string.status_adb_recovery) to "#6FB7D8"
            ConnectionModeUiPolicy.DisplayMode.ADB_SIDELOAD -> getString(R.string.status_adb_sideload) to "#F2B766"
            ConnectionModeUiPolicy.DisplayMode.ADB_UNKNOWN -> getString(R.string.status_adb_unknown) to "#AEB8C5"
            ConnectionModeUiPolicy.DisplayMode.ERROR -> getString(R.string.status_error) to "#E06C75"
        }
    }

    private fun refreshConnectionStatusLabel() {
        if (!::tvStatus.isInitialized) return
        val (text, color) = connectionStatusPresentation()
        tvStatus.text = text
        tvStatus.setTextColor(color.toColorInt())
    }

    private fun renderOperationProgressUi(rawProgress: DeviceViewModel.OperationProgress?) {
        // Operation progress is rendered in-place. It never opens Console or
        // overlays the active workflow page.
        if (::operationCenterProgressBar.isInitialized && ::tvOperationCenterProgress.isInitialized) {
            val active = viewModel.operationActive.value == true
            val progress = operationProgressForUi(rawProgress)
            val operationRunning = active || progress?.finished == false
            val effectiveProgress = progress?.takeUnless { operationRunning && it.finished }
            val showDetails = operationRunning || progress != null
            val percent = if (operationCancelRequested && operationRunning) {
                null
            } else {
                effectiveProgress?.percent?.takeIf { it >= 0 }?.coerceIn(0, 100)
            }

            operationCenterDetails.visibility = if (showDetails) View.VISIBLE else View.GONE
            operationCenterProgressBar.visibility = if (showDetails) View.VISIBLE else View.GONE
            operationCenterProgressBar.isIndeterminate = operationRunning && percent == null
            if (percent != null) {
                operationCenterProgressBar.progress = percent
            } else if (!operationRunning && progress?.finished == true && progress.success) {
                operationCenterProgressBar.isIndeterminate = false
                operationCenterProgressBar.progress = 100
            } else if (!operationRunning) {
                operationCenterProgressBar.isIndeterminate = false
                operationCenterProgressBar.progress = 0
            }

            tvOperationCenterPercent.text = percent?.let {
                getString(R.string.layout_operation_progress_percent, it)
            }.orEmpty()
            tvOperationCenterPercent.visibility = if (percent != null) View.VISIBLE else View.GONE

            val detail = if (operationCancelRequested && operationRunning) {
                getString(R.string.layout_operation_center_cancelling_detail)
            } else {
                effectiveProgress?.detail?.trim().orEmpty()
            }
            tvOperationCenterProgress.text = detail
            tvOperationCenterProgress.visibility = if (detail.isNotBlank()) View.VISIBLE else View.GONE
        }
        updateOperationCenter(viewModel.logSnapshot())
        if (rawProgress != null && selectedWindow == "unlock") buildUnlockPage()
    }

    private fun renderOperationSteps(steps: List<DeviceViewModel.OperationStep>) {
        if (!::tvOperationStepQueue.isInitialized) return

        val active = viewModel.operationActive.value == true
        if (active && steps.any {
                it.status == DeviceViewModel.OperationStepStatus.RUNNING ||
                    it.status == DeviceViewModel.OperationStepStatus.FAILED
            }
        ) {
            operationStepsVisibleForCurrentRun = true
        }

        val displaySteps = if (operationStepsVisibleForCurrentRun) steps else emptyList()
        if (displaySteps.isEmpty()) {
            tvOperationStepQueue.text = ""
            tvOperationStepQueue.visibility = View.GONE
            updateOperationCenter(viewModel.logSnapshot())
            return
        }

        val runningIndex = displaySteps.indexOfFirst { it.status == DeviceViewModel.OperationStepStatus.RUNNING }
        val failedIndex = displaySteps.indexOfFirst { it.status == DeviceViewModel.OperationStepStatus.FAILED }
        val anchorIndex = when {
            runningIndex >= 0 -> runningIndex
            failedIndex >= 0 -> failedIndex
            else -> displaySteps.indexOfLast { it.status != DeviceViewModel.OperationStepStatus.PENDING }
                .takeIf { it >= 0 } ?: 0
        }
        val from = (anchorIndex - 2).coerceAtLeast(0)
        val to = (from + 5).coerceAtMost(displaySteps.size)
        val adjustedFrom = (to - 5).coerceAtLeast(0)
        val visibleSteps = displaySteps.subList(adjustedFrom, to)
        val hiddenCount = displaySteps.size - visibleSteps.size

        val body = buildString {
            visibleSteps.forEach { step ->
                val icon = when (step.status) {
                    DeviceViewModel.OperationStepStatus.PENDING -> "·"
                    DeviceViewModel.OperationStepStatus.RUNNING -> "▶"
                    DeviceViewModel.OperationStepStatus.OK -> "✓"
                    DeviceViewModel.OperationStepStatus.FAILED -> "✕"
                    DeviceViewModel.OperationStepStatus.SKIPPED -> "↷"
                    DeviceViewModel.OperationStepStatus.INFO -> "i"
                }
                append(icon)
                append(' ')
                append(step.index)
                append('/')
                append(step.total)
                append(' ')
                append(step.title.take(88))
                step.subtitle?.takeIf { it.isNotBlank() }?.let {
                    append(" — ")
                    append(it.take(56))
                }
                append('\n')
            }
            if (hiddenCount > 0) {
                append(resources.getQuantityString(R.plurals.layout_operation_steps_more, hiddenCount, hiddenCount))
            }
        }.trimEnd()

        tvOperationStepQueue.text = body
        tvOperationStepQueue.visibility = View.VISIBLE
        val hasFailed = displaySteps.any { it.status == DeviceViewModel.OperationStepStatus.FAILED }
        val hasRunning = displaySteps.any { it.status == DeviceViewModel.OperationStepStatus.RUNNING }
        val allOk = displaySteps.isNotEmpty() && displaySteps.all {
            it.status == DeviceViewModel.OperationStepStatus.OK ||
                it.status == DeviceViewModel.OperationStepStatus.SKIPPED
        }
        val colorRes = when {
            hasFailed -> R.color.log_error
            hasRunning -> R.color.log_warning
            allOk -> R.color.log_success
            else -> R.color.text_secondary
        }
        tvOperationStepQueue.setTextColor(getColor(colorRes))
        updateOperationCenter(viewModel.logSnapshot())
    }

    private fun updateOperationCenter(lines: List<String>) {
        if (!::tvOperationCenterStatus.isInitialized || !::tvOperationCenterLastEvent.isInitialized) return

        val active = viewModel.operationActive.value == true
        val progress = operationProgressForUi(viewModel.operationProgress.value)
        val operationRunning = active || progress?.finished == false
        val effectiveProgress = progress?.takeUnless { operationRunning && it.finished }
        val steps = if (operationStepsVisibleForCurrentRun) {
            viewModel.operationSteps.value.orEmpty()
        } else {
            emptyList()
        }
        if (!operationStepsVisibleForCurrentRun) {
            tvOperationStepQueue.text = ""
            tvOperationStepQueue.visibility = View.GONE
        }
        val focusStep = steps.firstOrNull { it.status == DeviceViewModel.OperationStepStatus.RUNNING }
            ?: steps.firstOrNull { it.status == DeviceViewModel.OperationStepStatus.FAILED }
            ?: steps.lastOrNull { it.status != DeviceViewModel.OperationStepStatus.PENDING }

        val recent = lines.asReversed().firstOrNull { line ->
            val trimmed = line.trim()
            trimmed.isNotBlank() &&
                !trimmed.startsWith("💡") &&
                !trimmed.contains("System terminal ready", ignoreCase = true) &&
                !trimmed.contains("Full terminal", ignoreCase = true)
        }
        val recentText = recent?.let { if (it.length > 260) it.take(257) + "…" else it }
        val recentIsError = recentText != null && (
            recentText.contains("❌") || recentText.contains("ERROR", ignoreCase = true) ||
                recentText.contains("FAILED", ignoreCase = true) || recentText.contains("LOCKED", ignoreCase = true)
            )
        val recentIsWarning = recentText != null && (
            recentText.contains("⚠") || recentText.contains("WARN", ignoreCase = true)
            )

        val (statusRes, colorRes) = when {
            operationRunning && operationCancelRequested ->
                R.string.layout_operation_center_cancelling to R.color.log_warning
            operationRunning -> R.string.layout_operation_center_running to R.color.log_warning
            progress?.finished == true && progress.outcome == DeviceViewModel.OperationOutcomeKind.SUCCESS ->
                R.string.layout_operation_center_completed to R.color.log_success
            progress?.finished == true && progress.outcome == DeviceViewModel.OperationOutcomeKind.FAILED ->
                R.string.layout_operation_center_failed to R.color.log_error
            progress?.finished == true && progress.outcome == DeviceViewModel.OperationOutcomeKind.CANCELLED ->
                R.string.layout_operation_center_cancelled to R.color.text_secondary
            progress?.finished == true && progress.outcome == DeviceViewModel.OperationOutcomeKind.VERIFY_PENDING ->
                R.string.layout_operation_center_verify_pending to R.color.log_warning
            else -> R.string.layout_operation_center_idle to R.color.text_secondary
        }

        val statusText = getString(statusRes)
        tvOperationCenterStatus.text = statusText
        tvOperationCenterStatus.setTextColor(getColor(colorRes))
        cardOperationCenter.strokeColor = getColor(
            if (!operationRunning && progress == null) R.color.stroke else colorRes
        )
        val progressTint = ColorStateList.valueOf(getColor(colorRes))
        operationCenterProgressBar.progressTintList = progressTint
        operationCenterProgressBar.indeterminateTintList = progressTint

        val showDetails = operationRunning || progress != null
        operationCenterDetails.visibility = if (showDetails) View.VISIBLE else View.GONE
        tvOperationCenterCurrentLabel.text = getString(
            if (!operationRunning && progress?.finished == true) {
                R.string.layout_operation_center_result_label
            } else {
                R.string.layout_operation_center_current_label
            }
        )

        val headline = if (operationRunning && operationCancelRequested) {
            getString(R.string.layout_operation_center_cancelling_task)
        } else if (operationRunning) {
            focusStep?.title?.trim()?.takeIf { it.isNotBlank() }
                ?: effectiveProgress?.title?.trim()?.takeIf { it.isNotBlank() }
        } else {
            progress?.title?.trim()?.takeIf { it.isNotBlank() }
        }
            ?: if (operationRunning) getString(R.string.layout_operation_center_working) else progress?.title.orEmpty()
        tvOperationCenterHeadline.text = headline

        val stepSummary = focusStep?.let {
            getString(R.string.layout_operation_step_summary, it.index, it.total)
        }
        tvOperationCenterStepSummary.text = stepSummary.orEmpty()
        tvOperationCenterStepSummary.visibility = if (stepSummary != null) View.VISIBLE else View.GONE

        val showLastEvent = recentText != null && when {
            operationRunning -> recentIsError || recentIsWarning
            progress?.finished == true -> progress.outcome == DeviceViewModel.OperationOutcomeKind.FAILED ||
                progress.outcome == DeviceViewModel.OperationOutcomeKind.VERIFY_PENDING
            else -> false
        }
        tvOperationCenterLastEvent.text = recentText?.let {
            getString(R.string.layout_operation_center_last_event, it)
        } ?: getString(R.string.layout_operation_center_last_event_empty)
        tvOperationCenterLastEvent.visibility = if (showLastEvent) View.VISIBLE else View.GONE

        val canRequestCancel = active && !operationCancelRequested
        val cancelButton = findViewById<Button>(R.id.btnOperationCenterCancel)
        cancelButton.isEnabled = canRequestCancel
        cancelButton.text = getString(
            if (operationCancelRequested) R.string.layout_operation_cancelling_action
            else R.string.layout_operation_cancel
        )
        cancelButton.visibility = if (active) View.VISIBLE else View.GONE

        val globalCancelButton = findViewById<Button>(R.id.btnCancel)
        globalCancelButton.isEnabled = canRequestCancel
        globalCancelButton.alpha = if (canRequestCancel) 1f else 0.42f
        globalCancelButton.contentDescription = getString(
            if (operationCancelRequested) R.string.layout_operation_cancelling_action
            else R.string.layout_operation_cancel
        )

        renderOperationStrip(
            running = operationRunning,
            progress = progress,
            focusStep = focusStep,
            statusText = statusText,
            colorRes = colorRes,
            stepSummary = stepSummary,
        )
    }

    private fun renderOperationStrip(
        running: Boolean,
        progress: DeviceViewModel.OperationProgress?,
        focusStep: DeviceViewModel.OperationStep?,
        statusText: String,
        colorRes: Int,
        stepSummary: String?,
    ) {
        if (!::cardOperationStrip.isInitialized) return

        val finished = !running && progress?.finished == true
        if (!running && !finished) {
            cancelScheduledOperationStripHide()
            cardOperationStrip.visibility = View.GONE
            return
        }

        val signature = operationProgressSignature(progress)
        if (finished && signature != null && operationStripDismissedSignature == signature) {
            cardOperationStrip.visibility = View.GONE
            return
        }

        if (running) {
            operationStripDismissedSignature = null
            cancelScheduledOperationStripHide()
        } else if (finished && signature != null) {
            val autoHide = progress.outcome == DeviceViewModel.OperationOutcomeKind.SUCCESS ||
                progress.outcome == DeviceViewModel.OperationOutcomeKind.CANCELLED
            if (autoHide && operationStripScheduledSignature != signature) {
                cancelScheduledOperationStripHide()
                operationStripScheduledSignature = signature
                val hideRunnable = Runnable {
                    if (viewModel.operationActive.value != true &&
                        operationProgressSignature(viewModel.operationProgress.value) == signature
                    ) {
                        operationStripDismissedSignature = signature
                        cardOperationStrip.visibility = View.GONE
                    }
                    operationStripScheduledSignature = null
                    operationStripHideRunnable = null
                }
                operationStripHideRunnable = hideRunnable
                operationStripHandler.postDelayed(hideRunnable, OPERATION_STRIP_SUCCESS_HOLD_MS)
            } else if (!autoHide) {
                cancelScheduledOperationStripHide()
            }
        }

        val effectiveProgress = progress?.takeUnless { running && it.finished }
        val headline = if (running && operationCancelRequested) {
            getString(R.string.layout_operation_center_cancelling_task)
        } else if (running) {
            focusStep?.title?.trim()?.takeIf { it.isNotBlank() }
                ?: effectiveProgress?.title?.trim()?.takeIf { it.isNotBlank() }
        } else {
            progress?.title?.trim()?.takeIf { it.isNotBlank() }
        }
            ?: getString(R.string.layout_operation_center_working)
        val percent = if (operationCancelRequested && running) {
            null
        } else {
            effectiveProgress?.percent?.takeIf { it >= 0 }?.coerceIn(0, 100)
                ?: if (finished && progress.success) 100 else null
        }
        val metaText = if (running && stepSummary != null && !operationCancelRequested) {
            getString(R.string.layout_operation_strip_step_status, stepSummary, statusText)
        } else {
            statusText
        }

        tvOperationStripTitle.text = headline
        tvOperationStripMeta.text = metaText
        tvOperationStripPercent.text = percent?.let {
            getString(R.string.layout_operation_progress_percent, it)
        }.orEmpty()
        tvOperationStripPercent.visibility = if (percent != null) View.VISIBLE else View.GONE
        btnOperationStripDismiss.visibility = if (finished) View.VISIBLE else View.GONE

        operationStripProgressBar.isIndeterminate = running && percent == null
        if (percent != null) {
            operationStripProgressBar.progress = percent
        } else if (!running) {
            operationStripProgressBar.isIndeterminate = false
            operationStripProgressBar.progress = 0
        }

        val stateColor = getColor(colorRes)
        val stateTint = ColorStateList.valueOf(stateColor)
        cardOperationStrip.strokeColor = stateColor
        tvOperationStripMeta.setTextColor(stateColor)
        operationStripProgressBar.progressTintList = stateTint
        operationStripProgressBar.indeterminateTintList = stateTint
        cardOperationStrip.contentDescription = listOfNotNull(
            statusText,
            headline,
            stepSummary,
            percent?.let { getString(R.string.layout_operation_progress_percent, it) }
        ).distinct().joinToString(". ")
        cardOperationStrip.visibility = View.VISIBLE
    }

    private fun operationProgressFingerprint(progress: DeviceViewModel.OperationProgress?): String? {
        progress ?: return null
        return buildString {
            append(progress.title)
            append('|')
            append(progress.percent)
            append('|')
            append(progress.finished)
            append('|')
            append(progress.success)
            append('|')
            append(progress.outcome?.name ?: "none")
            append('|')
            append(progress.detail.hashCode())
        }
    }

    private fun operationProgressSignature(progress: DeviceViewModel.OperationProgress?): String? {
        if (progress?.finished != true) return null
        return operationProgressFingerprint(progress)
    }

    private fun cancelScheduledOperationStripHide() {
        operationStripHideRunnable?.let(operationStripHandler::removeCallbacks)
        operationStripHideRunnable = null
        operationStripScheduledSignature = null
    }

    // Console snapshots are coalesced for a short window so bursty USB output
    // produces one RecyclerView update instead of one layout pass per line.
    private var compactLogRenderState = CompactLogRenderPolicy.State()
    private val consoleRenderHandler = Handler(Looper.getMainLooper())
    private var pendingConsoleSnapshot: List<String>? = null
    private var consoleRenderScheduled: Boolean = false
    private val flushConsoleRenderRunnable = Runnable { flushConsoleRender() }

    private fun renderLog(lines: List<String>) {
        pendingConsoleSnapshot = CompactLogRenderPolicy.boundedSnapshot(lines)
        lines.lastOrNull()?.let(consoleDockController::updatePeek)
            ?: consoleDockController.updatePeek("")

        if (!consoleRenderScheduled) {
            consoleRenderScheduled = true
            consoleRenderHandler.postDelayed(
                flushConsoleRenderRunnable,
                CompactLogRenderPolicy.RENDER_DEBOUNCE_MS,
            )
        }
    }

    private fun flushConsoleRender() {
        consoleRenderScheduled = false
        val lines = pendingConsoleSnapshot ?: return
        pendingConsoleSnapshot = null

        val decision = CompactLogRenderPolicy.decide(lines, compactLogRenderState)
        compactLogRenderState = decision.nextState

        val layoutManager = rvConsoleOutput.layoutManager as? LinearLayoutManager
        val keepPinnedToBottom = !consoleDockController.isExpanded ||
            !rvConsoleOutput.canScrollVertically(1)
        val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: -1
        val firstVisibleOffset = if (firstVisiblePosition >= 0) {
            layoutManager?.findViewByPosition(firstVisiblePosition)?.top ?: 0
        } else {
            0
        }

        if (decision.reset) {
            consoleLogAdapter.replaceAll(lines)
        } else {
            consoleLogAdapter.removeFirst(decision.removeCount)
            consoleLogAdapter.append(lines.drop(decision.startIndex))
        }

        when {
            keepPinnedToBottom -> scrollConsoleToBottom()
            decision.removeCount > 0 && firstVisiblePosition >= 0 -> {
                layoutManager?.scrollToPositionWithOffset(
                    (firstVisiblePosition - decision.removeCount).coerceAtLeast(0),
                    firstVisibleOffset,
                )
            }
        }
    }

    private fun scrollConsoleToBottom() {
        val lastPosition = consoleLogAdapter.itemCount - 1
        if (lastPosition < 0) return
        rvConsoleOutput.post { rvConsoleOutput.scrollToPosition(lastPosition) }
    }

    // ─── Авто-снижение яркости во время записи ───────────────────────────────
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var brightnessReduced: Boolean = false

    private fun applyReducedBrightness() {
        if (brightnessReduced) return
        runCatching {
            val attributes = window.attributes
            savedBrightness = attributes.screenBrightness
            attributes.screenBrightness = 0.15f // Экран остаётся читаемым без лишнего нагрева.
            window.attributes = attributes
            brightnessReduced = true
        }.onFailure { error ->
            android.util.Log.w("NekoFlash", "Unable to reduce screen brightness", error)
        }
    }

    private fun restoreBrightness() {
        if (!brightnessReduced) return
        runCatching {
            val attributes = window.attributes
            attributes.screenBrightness = savedBrightness
            window.attributes = attributes
        }.onFailure { error ->
            android.util.Log.w("NekoFlash", "Unable to restore screen brightness", error)
        }
        brightnessReduced = false
        savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    override fun onStop() {
        if (viewModelReady) viewModel.flushDiagnostics("ACTIVITY_BACKGROUND_FLUSH", terminal = false)
        endWelcomeSessionIfTaskClosing()
        super.onStop()
    }

    private fun endWelcomeSessionIfTaskClosing() {
        // Do not reset on rotation/recreation or normal backgrounding. A cold
        // process start resets the in-memory gate automatically; this branch
        // covers an explicitly finished/removed task while the process survives.
        if (isFinishing && !isChangingConfigurations) {
            OnboardingGate.endSession()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && viewModelReady) {
            viewModel.flushDiagnostics("ACTIVITY_DESTROY", terminal = true)
        }
        // Android may destroy a removed/background task without leaving
        // isFinishing=true. Any non-configuration destruction ends the entry
        // session; a surviving process must therefore show Welcome next time.
        if (!isChangingConfigurations) {
            OnboardingGate.endSession()
        }

        miAuthExchangeJob?.cancel()
        miAuthExchangeJob = null
        usbPermissionTimeouts.values.forEach(usbPermissionHandler::removeCallbacks)
        usbPermissionTimeouts.clear()
        modeSwitchHandler.removeCallbacksAndMessages(null)
        deviceOverviewHandler.removeCallbacksAndMessages(null)
        cancelScheduledOperationStripHide()
        operationStripHandler.removeCallbacksAndMessages(null)
        consoleRenderHandler.removeCallbacks(flushConsoleRenderRunnable)
        consoleRenderScheduled = false
        pendingConsoleSnapshot = null
        runCatching { unregisterReceiver(usbReceiver) }
            .onFailure { error ->
                if (error !is IllegalArgumentException) {
                    android.util.Log.w("NekoFlash", "USB receiver cleanup failed", error)
                }
            }
        super.onDestroy()
    }

    private val PARTITION_NAME_PATTERN = Regex("^[a-z0-9._-]{1,64}$")

    companion object {
        private const val USB_PERMISSION_TIMEOUT_MS = 30_000L
        private const val EXTRA_USB_INTENT_CONSUMED = "nekoflash_usb_intent_consumed"
        private const val STARTUP_USB_SCAN_DELAY_MS = 350L
        private const val OPERATION_STRIP_SUCCESS_HOLD_MS = 9_000L
        private const val MODE_SWITCH_SCAN_INTERVAL_MS = 750L
        private const val MODE_SWITCH_SCAN_ATTEMPTS = 16
        private const val PREFS_NAME = "settings"
        private const val PREF_LANGUAGE_TAG = "language_tag"
        private const val PREF_LAST_WINDOW = "last_window"
        private const val STATE_SELECTED_WINDOW = "state_selected_window"
        private const val STATE_HIDE_STORED_OPERATION_PROGRESS = "state_hide_stored_operation_progress"
        private const val STATE_HIDDEN_OPERATION_PROGRESS_FINGERPRINT =
            "state_hidden_operation_progress_fingerprint"
        private const val STATE_OPERATION_CANCEL_REQUESTED = "state_operation_cancel_requested"
        private const val STATE_OPERATION_STRIP_DISMISSED_SIGNATURE =
            "state_operation_strip_dismissed_signature"
    }
}
