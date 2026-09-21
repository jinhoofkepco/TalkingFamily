package kr.family.homeway

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kr.family.homeway.ui.HomewayApp
import kr.family.homeway.ui.UiActions
import kr.family.homeway.overlay.FloatingStarService
import kr.family.homeway.data.TelegramReceiveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val model: HomewayViewModel by viewModels()
    private var afterPermission: (() -> Unit)? = null
    private var requestingAutomatic=false
    private var overlayPermissionGranted by mutableStateOf(false)
    private var overlayPromptVisible by mutableStateOf(false)
    private var openChatRequestId by mutableIntStateOf(0)
    private var launchWantsBubble = false
    private var waitingForOverlayPermission = false
    private var enableOverlayOnResume = false
    private var collapsing = false
    private var collapseJob: Job? = null
    private var notificationPermissionInFlight = false
    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val callback=afterPermission
        afterPermission=null
        val fine=has(Manifest.permission.ACCESS_FINE_LOCATION)
        val notifications=Build.VERSION.SDK_INT<33 || has(Manifest.permission.POST_NOTIFICATIONS)
        if(fine && (!requestingAutomatic || notifications)) callback?.invoke()
        else model.showError(if(!fine) "정확한 위치 권한이 필요해요. 설정에서 허용한 뒤 다시 눌러 주세요." else "자동 공유 상태를 표시하려면 알림 권한이 필요해요.")
    }
    private val notificationLauncher=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionInFlight = false
        if (granted && model.state.value.configured && TelegramReceiveService.wantsReceiving(this)) startTelegramReceiving()
        else if (!granted) model.showError("화면이 꺼져 있을 때 가족 소식을 받으려면 알림 권한을 허용해 주세요. 앱을 열어 두면 대화할 수 있어요.")
        handleReadyState()
    }
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForOverlayPermission = false
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        enableOverlayOnResume = overlayPermissionGranted
        if (!overlayPermissionGranted) {
            model.showError("별 아이콘 권한을 허용하지 않았어요. 지금처럼 앱에서 대화할 수 있어요.")
        }
        handleReadyState()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (savedInstanceState == null) {
            routeEntry(intent)
        } else {
            launchWantsBubble = savedInstanceState.getBoolean("launchWantsBubble")
            waitingForOverlayPermission = savedInstanceState.getBoolean("waitingForOverlayPermission")
            enableOverlayOnResume = savedInstanceState.getBoolean("enableOverlayOnResume")
            overlayPromptVisible = savedInstanceState.getBoolean("overlayPromptVisible")
            openChatRequestId = savedInstanceState.getInt("openChatRequestId")
            notificationPermissionInFlight = savedInstanceState.getBoolean("notificationPermissionInFlight")
        }
        setContent {
            val state=model.state.collectAsStateWithLifecycle().value
            val overlay = FloatingStarService.runtime.collectAsStateWithLifecycle().value
            val receiver = TelegramReceiveService.runtime.collectAsStateWithLifecycle().value
            HomewayApp(state.copy(
                telegramReceiving = receiver.running,
                error = state.error ?: receiver.error,
                overlayEnabled = overlay.running,
                overlayPermissionGranted = overlayPermissionGranted,
                openChatRequestId = openChatRequestId,
                overlayPromptVisible = overlayPromptVisible,
            ), UiActions(
                configure={ role,token,peer ->
                    getSharedPreferences("homeway_receiver", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
                    model.configure(role,token,peer)
                },
                startDemo={ role -> TelegramReceiveService.stop(this); model.startDemo(role) },
                sendChat=model::sendChat,
                shareCurrentLocation={ if(state.demoMode) model.shareCurrentLocation() else requestLocation(false,model::shareCurrentLocation) },
                awardSticker=model::awardSticker,
                requestRedemption=model::requestRedemption,
                saveReward=model::saveReward,
                deleteReward=model::deleteReward,
                approveRedemption=model::approveRedemption,
                setSharing={ enabled -> if(enabled && !state.demoMode) requestLocation(true) { model.setSharing(true) } else model.setSharing(enabled) },
                refresh=model::refresh,
                selectHistoryDay=model::selectHistoryDay,
                loadMoreHistory=model::loadMoreHistory,
                clearNotice=model::clearNotice,
                resetConfiguration={
                    if (!model.state.value.demoMode && model.state.value.role == "child" && model.state.value.sharingEnabled) model.resetConfiguration()
                    else {
                        disableOverlay()
                        TelegramReceiveService.stop(this)
                        model.resetConfiguration()
                    }
                },
                switchDemoRole=model::switchDemoRole,
                returnToStar=::startAndCollapse,
                enableOverlay=::enableOverlay,
                disableOverlay=::disableOverlay,
                dismissOverlayPrompt={ overlayPromptVisible = false },
                setTelegramReceiving={ enabled ->
                    if (enabled) {
                        getSharedPreferences("homeway_receiver", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
                        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                            getSharedPreferences("homeway_permissions", MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
                            notificationPermissionInFlight = true
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else startTelegramReceiving()
                    } else TelegramReceiveService.stop(this)
                }
            ))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { handleReadyState() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(true) {
                    model.refresh()
                    delay(5000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeEntry(intent)
        handleReadyState()
    }

    private fun routeEntry(entry: Intent?) {
        launchWantsBubble = entry?.action == Intent.ACTION_MAIN && entry.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (entry?.action == FloatingStarService.ACTION_OPEN_CHAT) {
            launchWantsBubble = false
            overlayPromptVisible = false
            openChatRequestId++
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (!collapsing) FloatingStarService.setMessengerVisible(true)
        handleReadyState()
    }

    override fun onStop() {
        super.onStop()
        // Showing an already-running shortcut is safe here; never start an FGS from onStop.
        if (!isChangingConfigurations && !waitingForOverlayPermission) {
            FloatingStarService.setMessengerVisible(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launchWantsBubble", launchWantsBubble)
        outState.putBoolean("waitingForOverlayPermission", waitingForOverlayPermission)
        outState.putBoolean("enableOverlayOnResume", enableOverlayOnResume)
        outState.putBoolean("overlayPromptVisible", overlayPromptVisible)
        outState.putInt("openChatRequestId", openChatRequestId)
        outState.putBoolean("notificationPermissionInFlight", notificationPermissionInFlight)
        super.onSaveInstanceState(outState)
    }

    private fun handleReadyState() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || collapsing || waitingForOverlayPermission) return
        val state = model.state.value
        if (!state.configured && !state.demoMode) return
        if (state.configured && TelegramReceiveService.wantsReceiving(this) && Build.VERSION.SDK_INT >= 33 &&
            !has(Manifest.permission.POST_NOTIFICATIONS)) {
            if (notificationPermissionInFlight) return
            val permissionPrefs = getSharedPreferences("homeway_permissions", MODE_PRIVATE)
            if (!permissionPrefs.getBoolean("notificationAsked", false)) {
                permissionPrefs.edit().putBoolean("notificationAsked", true).apply()
                notificationPermissionInFlight = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (state.configured && TelegramReceiveService.wantsReceiving(this) && !TelegramReceiveService.runtime.value.running &&
            (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))) startTelegramReceiving()
        if (enableOverlayOnResume) {
            enableOverlayOnResume = false
            startAndCollapse()
        } else if (launchWantsBubble) {
            launchWantsBubble = false // Once per explicit launcher entry, never every onResume.
            if (Settings.canDrawOverlays(this)) startAndCollapse() else overlayPromptVisible = true
        }
    }

    private fun startTelegramReceiving() {
        if (!model.state.value.configured) return
        try { TelegramReceiveService.start(this) }
        catch (_: RuntimeException) { model.showError("가족 소식 수신을 시작하지 못했어요. 앱을 다시 열고 가족 연결에서 수신을 켜 주세요.") }
    }

    private fun enableOverlay() {
        overlayPromptVisible = false
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (overlayPermissionGranted) {
            startAndCollapse()
            return
        }
        waitingForOverlayPermission = true
        FloatingStarService.setMessengerVisible(true)
        try {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            waitingForOverlayPermission = false
            model.showError("설정 → 앱 → 특별한 접근 → 다른 앱 위에 표시에서 우리 오는 길을 허용해 주세요.")
        }
    }

    private fun disableOverlay() {
        collapseJob?.cancel()
        collapsing = false
        launchWantsBubble = false
        enableOverlayOnResume = false
        overlayPromptVisible = false
        FloatingStarService.stop(this)
    }

    private fun startAndCollapse() {
        if (collapsing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (!overlayPermissionGranted) {
            overlayPromptVisible = true
            return
        }
        val state = model.state.value
        if (!state.configured && !state.demoMode) return
        collapsing = true
        launchWantsBubble = false
        overlayPromptVisible = false
        collapseJob = lifecycleScope.launch {
            try {
                // Android 15: create the service while this Activity is still visible.
                FloatingStarService.setMessengerVisible(false)
                FloatingStarService.start(this@MainActivity)
                val result = withTimeoutOrNull(4000) {
                    FloatingStarService.runtime.first { it.visible || it.error != null }
                }
                if (result?.visible == true) {
                    moveTaskToBack(true)
                } else {
                    FloatingStarService.stop(this@MainActivity)
                    FloatingStarService.setMessengerVisible(true)
                    model.showError(result?.error ?: "별 아이콘을 표시하지 못했어요. 앱에서 다시 켜 주세요.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                FloatingStarService.stop(this@MainActivity)
                FloatingStarService.setMessengerVisible(true)
                model.showError("별 아이콘을 시작하지 못했어요. 앱에서 다시 켜 주세요.")
            } finally {
                collapsing = false
            }
        }
    }
    private fun has(permission:String)=ContextCompat.checkSelfPermission(this,permission)==PackageManager.PERMISSION_GRANTED
    private fun requestLocation(automatic:Boolean,action:()->Unit) {
        val permissions=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        if(automatic && Build.VERSION.SDK_INT>=29) permissions+=Manifest.permission.ACTIVITY_RECOGNITION
        if(automatic && Build.VERSION.SDK_INT>=33) permissions+=Manifest.permission.POST_NOTIFICATIONS
        if(permissions.all(::has)) { action(); return }
        requestingAutomatic=automatic; afterPermission=action
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
