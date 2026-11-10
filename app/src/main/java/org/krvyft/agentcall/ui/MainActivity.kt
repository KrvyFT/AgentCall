package org.krvyft.agentcall.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.krvyft.agentcall.R
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.rules.ConfigValidator
import org.krvyft.agentcall.core.model.EndpointKind
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
import org.krvyft.agentcall.service.CalibrationVerdict
import org.krvyft.agentcall.service.ConnectionTestReport
import org.krvyft.agentcall.service.ConnectionTestSupport
import org.krvyft.agentcall.service.EndpointTestOutcome
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
    private lateinit var textConnectionResults: TextView

    private var bridgeReachable: Boolean? = null
    private var bridgeStatus: BridgeStatus? = null
    private var currentEnvironment: EnvironmentKey? = null
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
        // 关闭是本地 IO：放到 IO dispatcher，绝不在主线程 runBlocking；
        // BridgeClient 的 lane reader 运行在 [scope] 上，所以关闭完成后才取消它。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runCatching { bridge?.close() } }
                runCatching { diagnosticsClient?.close() }
            } finally {
                scope.cancel()
            }
        }
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
        textConnectionResults = findViewById(R.id.text_connection_results)
        evidence = CalibrationEvidenceStore(this)
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.button_start).setOnClickListener {
            ContextCompat.startForegroundService(this, AgentCallService.startIntent(this))
            Toast.makeText(this, R.string.main_toast_service_started, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.button_stop).setOnClickListener {
            // 停止一个未运行的服务不该先把它创建出来；stopService 对未运行的服务是无操作。
            stopService(Intent(this, AgentCallService::class.java))
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
        findViewById<Button>(R.id.button_connection_test).setOnClickListener { confirmConnectionTest() }
    }

    /**
     * 连接测试必须由用户显式确认：对话框文案明确“真实付费 API 请求”，取消后零调用。
     * 这不是安全检查的替代——编排层同样要求 confirmed=true。
     */
    private fun confirmConnectionTest() {
        AlertDialog.Builder(this)
            .setTitle(R.string.connection_confirm_title)
            .setMessage(R.string.connection_confirm_message)
            .setNegativeButton(R.string.connection_confirm_negative, null)
            .setPositiveButton(R.string.connection_confirm_positive) { _, _ -> runConnectionTest() }
            .show()
    }

    private fun runConnectionTest() {
        val currentConfig = config
        if (currentConfig == null) {
            textConnectionResults.visibility = TextView.VISIBLE
            textConnectionResults.text = getString(R.string.connection_needs_config)
            return
        }
        val bridge = client ?: BridgeClient(
            control = LocalSocketControlTransport(),
            pcm = LocalSocketPcmLaneTransport(),
            scope = scope,
            logger = { },
        ).also { client = it }
        textConnectionResults.visibility = TextView.VISIBLE
        textConnectionResults.text = getString(R.string.connection_running)
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    // 显式 confirmed=true 只可能来自上面的对话框；UI 从不自动调用。
                    ConnectionTestSupport.run(bridge, currentConfig, confirmed = true)
                }.getOrNull()
            }
            if (report == null) {
                textConnectionResults.text = getString(R.string.connection_request_failed, "request_failed")
                return@launch
            }
            textConnectionResults.text = renderConnectionReport(report)
        }
    }

    private fun renderConnectionReport(report: ConnectionTestReport): String {
        val lines = report.results.map { result ->
            val label = when (result.kind) {
                EndpointKind.ASR -> "ASR"
                EndpointKind.LLM -> "LLM"
                EndpointKind.TTS -> "TTS"
            }
            val outcome = when (result.outcome) {
                EndpointTestOutcome.PASSED -> getString(R.string.connection_passed)
                EndpointTestOutcome.FAILED ->
                    getString(R.string.connection_failed, result.code ?: "request_failed")
                EndpointTestOutcome.NOT_CONFIGURED -> getString(R.string.connection_not_configured)
            }
            label + "：" + outcome
        }
        return lines.joinToString("\n")
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
                currentEnvironment = facts.environment
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

        statusService.text = getString(R.string.main_status_service_prefix, serviceText(status))
        statusSession.text = getString(R.string.main_status_session_prefix, sessionText(status))
        textBlockers.text = when {
            status.ready -> getString(R.string.main_note_ready)
            status.blockers.isEmpty() -> getString(R.string.main_note_no_blockers)
            else -> getString(
                R.string.main_note_blockers,
                status.blockers.joinToString("；") { StatusText.blocker(it, resources) },
            )
        }
        val error = errorText(status)
        if (error == null) {
            textError.visibility = TextView.GONE
        } else {
            textError.visibility = TextView.VISIBLE
            textError.text = getString(R.string.main_error_prefix, error)
        }
        buttonTakeover.isEnabled = status.activeSession != null

        statusRoot.text = getString(R.string.main_status_root_prefix, rootText())
        statusHook.text = getString(R.string.main_status_hook_prefix, hookText())
        statusConfig.text = getString(R.string.main_status_config_prefix, configText())
        statusCalibration.text = getString(R.string.main_status_calibration_prefix, calibrationText())
    }

    private fun rootText(): String = when (bridgeReachable) {
        true -> getString(R.string.main_bridge_reachable)
        false -> getString(R.string.main_bridge_unreachable)
        null -> getString(R.string.main_mode_unknown)
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
        val state = StatusText.sessionState(status.sessionState, resources)
        return if (session == null) state else state + " · " + session.callId
    }

    private fun errorText(status: AgentCallStatus): String? {
        // 报告本身已绑定会话、并在新会话第一个状态时清空，因此这里不会再显示上一通电话的结果。
        val handback = status.lastHandback?.let { StatusText.handback(it.code, resources) }
        val error = status.lastError
        return when {
            handback != null && error == null -> handback
            handback != null && error != null -> handback + "；" + StatusText.takeoverRejection(error, resources)
            error != null -> StatusText.takeoverRejection(error, resources)
            else -> null
        }
    }

    private fun hookText(): String {
        val status = bridgeStatus ?: return getString(R.string.main_hook_unknown)
        return when (status.hookConnected) {
            HookPresence.PRESENT -> StatusText.hookPresence(HookPresence.PRESENT, resources)
            HookPresence.ABSENT -> StatusText.hookPresence(HookPresence.ABSENT, resources)
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
        // “已验证”必须经 core 的 CalibrationPolicy：API 36 / 环境变化 / 证据不完整都不算。
        if (CalibrationVerdict.canAutoAnswer(record, currentEnvironment)) {
            return getString(R.string.main_calibration_verified)
        }
        if (!record.allVerified) return getString(R.string.main_calibration_unverified)
        return if (currentEnvironment == null || record.environment == null) {
            getString(R.string.main_calibration_environment_missing)
        } else {
            getString(R.string.main_calibration_policy_blocked)
        }
    }

    private data class BridgeFacts(
        val reachable: Boolean,
        val config: AgentConfig?,
        val status: BridgeStatus?,
        val environment: EnvironmentKey?,
    )

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001

        /** 页面关闭连接的有界收尾（本地 socket 关闭，不应阻塞 UI）。 */
        private const val CLOSE_TIMEOUT_MS: Long = 2_000L
    }
}
