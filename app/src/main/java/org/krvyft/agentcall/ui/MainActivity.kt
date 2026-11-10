package org.krvyft.agentcall.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.krvyft.agentcall.R
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.policy.CalibrationPolicy
import org.krvyft.agentcall.core.rules.ConfigValidator
import org.krvyft.agentcall.ipc.BridgeClient
import org.krvyft.agentcall.ipc.LocalSocketControlTransport
import org.krvyft.agentcall.ipc.LocalSocketPcmLaneTransport
import org.krvyft.agentcall.service.AgentCallRuntime
import org.krvyft.agentcall.service.AgentCallService
import org.krvyft.agentcall.service.AgentCallStatus
import org.krvyft.agentcall.service.BridgeDiagnostics
import org.krvyft.agentcall.service.BridgeStatus
import org.krvyft.agentcall.service.BridgeStatusResult
import org.krvyft.agentcall.service.CalibrationEvidenceStore
import org.krvyft.agentcall.service.EnvironmentResult
import org.krvyft.agentcall.service.HookPresence
import org.krvyft.agentcall.service.StatusText
import org.krvyft.agentcall.service.hasCompleteVoiceEndpoints
import org.krvyft.agentcall.service.parseRedactedAgentConfig

/**
 * 启动向导 / 主页（Task 7B2）。
 *
 * 只展示**可读取到的真实事实**：root 桥接可达性、Hook 心跳、前台服务就绪、校准本机记录、
 * 配置校验与会话状态。桥接没有向 APP 开放 status/diagnose 时显示“未知/不可用”，
 * 绝不把读取失败显示成成功（spec §11）。
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshing = AtomicBoolean(false)

    private var client: BridgeClient? = null
    private var diagnostics: BridgeDiagnostics? = null
    private var evidence: CalibrationEvidenceStore? = null

    private lateinit var statusRoot: TextView
    private lateinit var statusHook: TextView
    private lateinit var statusService: TextView
    private lateinit var statusCalibration: TextView
    private lateinit var statusConfig: TextView
    private lateinit var statusSession: TextView
    private lateinit var textBlockers: TextView
    private lateinit var textError: TextView
    private lateinit var buttonNotification: Button
    private lateinit var buttonTakeover: Button

    private var bridgeReachable: Boolean? = null
    private var bridgeStatus: BridgeStatus? = null
    private var environmentAvailable: Boolean = false
    private var config: AgentConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireButtons()
        scope.launch {
            AgentCallRuntime.status.collect { renderRuntime(it) }
        }
        renderRuntime(AgentCallRuntime.status.value)
    }

    override fun onResume() {
        super.onResume()
        refreshFacts()
    }

    override fun onDestroy() {
        val bridge = client
        val diagnosticsClient = diagnostics
        client = null
        diagnostics = null
        // 关闭必须在取消 scope 之前完成：BridgeClient 的 lane reader 运行在该 scope 上。
        runBlocking {
            runCatching { bridge?.close() }
            runCatching { diagnosticsClient?.close() }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        statusRoot = findViewById(R.id.status_root)
        statusHook = findViewById(R.id.status_hook)
        statusService = findViewById(R.id.status_service)
        statusCalibration = findViewById(R.id.status_calibration)
        statusConfig = findViewById(R.id.status_config)
        statusSession = findViewById(R.id.status_session)
        textBlockers = findViewById(R.id.text_blockers)
        textError = findViewById(R.id.text_error)
        buttonNotification = findViewById(R.id.button_notification)
        buttonTakeover = findViewById(R.id.button_takeover)
        evidence = CalibrationEvidenceStore(this)
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.button_start).setOnClickListener {
            ContextCompat.startForegroundService(this, AgentCallService.startIntent(this))
            Toast.makeText(this, R.string.main_toast_service_started, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.button_stop).setOnClickListener {
            startService(AgentCallService.stopIntent(this))
        }
        findViewById<Button>(R.id.button_retry).setOnClickListener {
            ContextCompat.startForegroundService(this, AgentCallService.startIntent(this, retry = true))
            refreshFacts()
        }
        buttonNotification.setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.button_calibration).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        buttonTakeover.setOnClickListener { openTakeover() }
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        Toast.makeText(this, R.string.main_toast_notification_requested, Toast.LENGTH_SHORT).show()
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            renderRuntime(AgentCallRuntime.status.value)
            refreshFacts()
        }
    }

    private fun openTakeover() {
        val session = AgentCallRuntime.status.value.activeSession
        if (session == null) {
            Toast.makeText(this, R.string.main_toast_no_active_call, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(TakeoverActivity.intent(this, session))
    }

    // ------------------------------------------------------------------ 事实刷新

    private fun refreshFacts() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val facts = withContext(Dispatchers.IO) { readFacts() }
                bridgeReachable = facts.reachable
                config = facts.config
                bridgeStatus = facts.status
                environmentAvailable = facts.environment != null
                renderRuntime(AgentCallRuntime.status.value)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private suspend fun readFacts(): BridgeFacts {
        val bridge = client ?: BridgeClient(
            control = LocalSocketControlTransport(),
            pcm = LocalSocketPcmLaneTransport(),
            scope = scope,
            logger = { },
        ).also { client = it }
        val reachable = runCatching { bridge.ensureReady() }.getOrDefault(false)
        val config = runCatching { parseRedactedAgentConfig(bridge.readRedactedConfig()) }.getOrNull()
        val diagnosticsClient = diagnostics ?: BridgeDiagnostics().also { diagnostics = it }
        val status = when (val result = runCatching { diagnosticsClient.status() }.getOrNull()) {
            is BridgeStatusResult.Available -> result.status
            else -> null
        }
        val environment = when (val result = runCatching { diagnosticsClient.environment() }.getOrNull()) {
            is EnvironmentResult.Available -> result.environment
            else -> null
        }
        return BridgeFacts(reachable, config, status, environment)
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderRuntime(status: AgentCallStatus) {
        val permissionGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        buttonNotification.visibility = if (permissionGranted) Button.GONE else Button.VISIBLE

        statusService.text = getString(R.string.main_status_service_prefix) + serviceText(status)
        statusSession.text = getString(R.string.main_status_session_prefix) + sessionText(status)
        textBlockers.text = when {
            status.ready -> getString(R.string.main_note_ready)
            status.blockers.isEmpty() -> getString(R.string.main_note_no_blockers)
            else -> getString(R.string.main_note_blockers) +
                status.blockers.joinToString("；") { StatusText.blocker(it) }
        }
        val error = errorText(status)
        if (error == null) {
            textError.visibility = TextView.GONE
        } else {
            textError.visibility = TextView.VISIBLE
            textError.text = getString(R.string.main_error_prefix) + error
        }
        buttonTakeover.isEnabled = status.activeSession != null

        statusRoot.text = getString(R.string.main_status_root_prefix) + when (bridgeReachable) {
            true -> getString(R.string.main_bridge_reachable)
            false -> getString(R.string.main_bridge_unreachable)
            null -> getString(R.string.main_mode_unknown)
        }
        statusHook.text = getString(R.string.main_status_hook_prefix) + hookText()
        statusConfig.text = getString(R.string.main_status_config_prefix) + configText()
        statusCalibration.text = getString(R.string.main_status_calibration_prefix) + calibrationText()
    }

    private fun serviceText(status: AgentCallStatus): String = when {
        status.foregroundError != null ->
            getString(R.string.main_service_foreground_failed, status.foregroundError)
        status.serviceRunning && status.foregroundActive ->
            getString(R.string.main_service_running) + " · " +
                getString(if (status.ready) R.string.main_service_ready else R.string.main_service_not_ready)
        else -> getString(R.string.main_service_not_running)
    }

    private fun sessionText(status: AgentCallStatus): String {
        val session = status.activeSession
        val state = StatusText.sessionState(status.sessionState)
        return if (session == null) state else state + " · " + session.callId
    }

    private fun errorText(status: AgentCallStatus): String? {
        val handback = StatusText.handback(status.lastHandback)
        val error = status.lastError
        return when {
            handback != null && error == null -> handback
            handback != null && error != null -> handback + "；" + StatusText.takeoverRejection(error)
            error != null -> StatusText.takeoverRejection(error)
            else -> null
        }
    }

    private fun hookText(): String {
        val status = bridgeStatus ?: return getString(R.string.main_hook_unknown)
        return when (status.hookConnected) {
            HookPresence.PRESENT -> StatusText.hookPresence(HookPresence.PRESENT)
            HookPresence.ABSENT -> StatusText.hookPresence(HookPresence.ABSENT)
            HookPresence.UNKNOWN -> getString(R.string.main_hook_unknown)
        }
    }

    private fun configText(): String {
        val config = config ?: return getString(R.string.main_config_unavailable)
        val parts = ArrayList<String>(3)
        parts += getString(if (config.enabled) R.string.main_config_enabled else R.string.main_config_disabled)
        if (!ConfigValidator.validate(config).isValid) parts += getString(R.string.main_config_invalid)
        parts += getString(
            if (config.hasCompleteVoiceEndpoints()) {
                R.string.main_config_endpoints_ready
            } else {
                R.string.main_config_endpoints_missing
            },
        )
        return parts.joinToString(" · ")
    }

    private fun calibrationText(): String {
        val record = evidence?.record() ?: return getString(R.string.main_calibration_unverified)
        if (!record.allVerified) {
            val missing = if (!environmentAvailable) {
                getString(R.string.main_calibration_environment_missing)
            } else {
                getString(R.string.main_calibration_unverified)
            }
            return missing
        }
        val environment = record.environment
        if (environment == null) return getString(R.string.main_calibration_environment_missing)
        val policyAllows = CalibrationPolicy.canAutoAnswer(environment, record.toEvidence()!!)
        return getString(R.string.main_calibration_verified) +
            if (policyAllows) "" else "（" + getString(R.string.main_calibration_environment_missing) + "）"
    }

    private data class BridgeFacts(
        val reachable: Boolean,
        val config: AgentConfig?,
        val status: BridgeStatus?,
        val environment: EnvironmentKey?,
    )

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
