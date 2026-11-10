package org.krvyft.agentcall.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.krvyft.agentcall.R
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.service.BridgeDiagnostics
import org.krvyft.agentcall.service.CalibrationEvidenceStore
import org.krvyft.agentcall.service.CalibrationManualStep
import org.krvyft.agentcall.service.EnvironmentResult

/**
 * 校准页（Task 7B2，spec §9）。
 *
 * 展示五个人工测试步骤和 未验证/已验证 状态。边界必须如实：
 * - 本页保存的只是用户确认过的测试进度，绑定桥接 diagnose 给出的完整 EnvironmentKey；
 * - 是否允许自动接听由 root 桥接自己的校准证据决定，APK 无法写入桥接校准；
 * - 取不到环境时（APP 无权调用 diagnose）五步全部勾选也不显示已验证。
 */
class CalibrationActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diagnostics: BridgeDiagnostics? = null
    private lateinit var store: CalibrationEvidenceStore
    private var environment: EnvironmentKey? = null
    private var environmentError: String? = null
    private var rendering = false

    private lateinit var environmentView: TextView
    private lateinit var stateView: TextView
    private lateinit var environmentNote: TextView
    private lateinit var checkboxes: Map<CalibrationManualStep, CheckBox>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        store = CalibrationEvidenceStore(this)
        environmentView = findViewById(R.id.calibration_environment)
        stateView = findViewById(R.id.calibration_state)
        environmentNote = findViewById(R.id.calibration_environment_note)
        checkboxes = mapOf(
            CalibrationManualStep.DOWNLINK_RECOGNIZED to findViewById(R.id.step_downlink),
            CalibrationManualStep.PEER_HEARD_SPEECH to findViewById(R.id.step_peer_heard),
            CalibrationManualStep.MICROPHONE_ISOLATED to findViewById(R.id.step_mic_isolated),
            CalibrationManualStep.MANUAL_RESTORE to findViewById(R.id.step_manual_restore),
            CalibrationManualStep.PROCESS_FAILURE to findViewById(R.id.step_process_failure),
        )
        for ((step, checkbox) in checkboxes) {
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (rendering) return@setOnCheckedChangeListener
                store.markStep(step, checked, environment)
                render()
            }
        }
        findViewById<Button>(R.id.button_refresh_environment).setOnClickListener { refreshEnvironment() }
        findViewById<Button>(R.id.button_reset).setOnClickListener {
            store.reset()
            Toast.makeText(this, R.string.calibration_toast_reset, Toast.LENGTH_SHORT).show()
            render()
        }
        render()
        refreshEnvironment()
    }

    override fun onDestroy() {
        val diagnosticsClient = diagnostics
        diagnostics = null
        runBlocking { runCatching { diagnosticsClient?.close() } }
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshEnvironment() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val client = diagnostics ?: BridgeDiagnostics().also { diagnostics = it }
                runCatching { client.environment() }.getOrNull()
            }
            when (result) {
                is EnvironmentResult.Available -> {
                    environment = result.environment
                    environmentError = null
                    // 环境变化时本机进度必须重来（证据绑定完整环境）。
                    store.bindEnvironment(result.environment)
                }
                is EnvironmentResult.Unavailable -> {
                    environment = null
                    environmentError = result.code
                }
                null -> {
                    environment = null
                    environmentError = "bridge_unavailable"
                }
            }
            render()
        }
    }

    private fun render() {
        val record = store.record()
        val verified = record.allVerified &&
            environment != null &&
            record.environment == environment &&
            record.toEvidence() != null
        val environmentText = when {
            environment != null -> getString(R.string.calibration_environment_available, environment!!.apiLevel)
            environmentError != null -> getString(R.string.calibration_environment_unavailable, environmentError)
            else -> getString(R.string.calibration_environment_unknown)
        }
        environmentView.text = getString(R.string.calibration_environment_prefix, environmentText)
        val verifiedLabel = getString(
            if (verified) R.string.calibration_state_verified else R.string.calibration_state_unverified,
        )
        stateView.text = getString(
            R.string.calibration_state_prefix,
            verifiedLabel,
            record.completed.size,
            CalibrationManualStep.entries.size,
        )
        environmentNote.visibility = if (environment == null) TextView.VISIBLE else TextView.GONE

        rendering = true
        for ((step, checkbox) in checkboxes) {
            checkbox.isChecked = step in record.completed
        }
        rendering = false
    }
}
