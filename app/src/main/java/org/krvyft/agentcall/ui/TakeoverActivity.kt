package org.krvyft.agentcall.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.krvyft.agentcall.R
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.service.AgentCallRuntime
import org.krvyft.agentcall.service.AgentCallService
import org.krvyft.agentcall.service.AgentCallStatus
import org.krvyft.agentcall.service.StatusText
import org.krvyft.agentcall.service.TakeoverTarget

/**
 * 人工接管页（Task 7B2，spec §6.2/§8）。
 *
 * 页面绑定一个具体 [SessionId]（来自主页的当前会话）：提交的接管 Intent 用 data 编码同一
 * SessionId，服务端再用 [org.krvyft.agentcall.service.TakeoverGuard] 与当前活动会话核对。
 * 旧页面/旧通知的目标绝不作用于新通话。
 *
 * 结果展示遵守“撤销未确认不得显示交还成功”：只有 handback_confirmed / handback_not_owned
 * 才显示成功，撤销失败只显示机器码。
 */
class TakeoverActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: SessionId? = null

    private lateinit var sessionView: TextView
    private lateinit var stateView: TextView
    private lateinit var outcomeView: TextView
    private lateinit var confirmButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takeover)
        sessionView = findViewById(R.id.takeover_session)
        stateView = findViewById(R.id.takeover_state)
        outcomeView = findViewById(R.id.takeover_outcome)
        confirmButton = findViewById(R.id.button_takeover_confirm)
        session = readSession(intent)
        findViewById<Button>(R.id.button_takeover_close).setOnClickListener { finish() }
        confirmButton.setOnClickListener { submitTakeover() }
        scope.launch {
            AgentCallRuntime.status.collect { render(it) }
        }
        render(AgentCallRuntime.status.value)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun submitTakeover() {
        val target = session ?: return
        val status = AgentCallRuntime.status.value
        if (!status.serviceRunning) {
            Toast.makeText(this, R.string.takeover_service_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        val data = TakeoverTarget.encode(TakeoverTarget.of(target))
        val intent = Intent(this, AgentCallService::class.java)
            .setAction(AgentCallService.ACTION_TAKEOVER)
            .setData(Uri.parse(data))
        startService(intent)
    }

    private fun render(status: AgentCallStatus) {
        val target = session
        if (target == null) {
            sessionView.text = getString(R.string.takeover_session_prefix) + getString(R.string.takeover_missing_session)
            stateView.text = getString(R.string.takeover_state_prefix)
            outcomeView.text = getString(R.string.takeover_outcome_none)
            confirmButton.isEnabled = false
            return
        }
        sessionView.text = getString(R.string.takeover_session_prefix) + target.callId
        stateView.text = getString(R.string.takeover_state_prefix) + StatusText.sessionState(status.sessionState)
        outcomeView.text = getString(R.string.takeover_outcome_prefix) + outcomeText(status)
        // 只有目标仍是当前会话时才允许提交（旧页面 fail closed）。
        confirmButton.isEnabled = status.activeSession == target
    }

    private fun outcomeText(status: AgentCallStatus): String {
        val handback = status.lastHandback
        return when (handback) {
            "handback_confirmed" -> StatusText.handback(handback)!!
            "handback_not_owned" -> StatusText.handback(handback)!!
            else -> {
                val error = status.lastError
                if (error != null) {
                    getString(R.string.takeover_outcome_failed, StatusText.takeoverRejection(error))
                } else {
                    getString(R.string.takeover_outcome_none)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROCESS_EPOCH: String = "org.krvyft.agentcall.extra.PROCESS_EPOCH"
        const val EXTRA_CALL_ID: String = "org.krvyft.agentcall.extra.CALL_ID"

        fun intent(context: Context, session: SessionId): Intent =
            Intent(context, TakeoverActivity::class.java)
                .putExtra(EXTRA_PROCESS_EPOCH, session.processEpoch)
                .putExtra(EXTRA_CALL_ID, session.callId)

        private fun readSession(intent: Intent?): SessionId? {
            if (intent == null) return null
            val epoch = intent.getStringExtra(EXTRA_PROCESS_EPOCH) ?: return null
            val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return null
            if (epoch.isBlank() || callId.isBlank()) return null
            return SessionId(processEpoch = epoch, callId = callId)
        }
    }
}
