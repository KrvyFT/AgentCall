package org.krvyft.agentcall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import org.krvyft.agentcall.R
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.ui.MainActivity

/**
 * 会话通知（Task 7B2，spec §6.2/§11）。
 *
 * 边界：
 * - 不使用 [Notification.CallStyle] 或全屏 Intent：本应用不是默认拨号器，不能伪装系统来电 UI；
 * - 这是普通前台服务通知，接管 action 绑定**生成它时的 SessionId**（Intent data 里编码）；
 * - 旧通知的 action 即使被再次触发，也只会携带旧 SessionId，由 [TakeoverGuard] 拒绝；
 * - 通知内容只有状态与机器码，绝不含号码、密钥或音频。
 */
class CallNotification(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_session),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_session_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** 前台服务通知（没有会话时）。 */
    fun foreground(status: AgentCallStatus): Notification =
        base(status, title = context.getString(R.string.notification_service_title))
            .setContentText(readinessText(status))
            .setStyle(NotificationCompat.BigTextStyle().bigText(readinessText(status) + "\n" + blockedText(status)))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /** 会话通知：带“我来接听”action，target 绑定 [id]。 */
    fun call(status: AgentCallStatus, id: SessionId): Notification {
        val state = StatusText.sessionState(status.sessionState)
        val text = context.getString(R.string.notification_call_text, state)
        return base(status, title = context.getString(R.string.notification_call_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(
                0,
                context.getString(R.string.notification_action_takeover),
                takeoverIntent(id),
            )
            .build()
    }

    private fun base(status: AgentCallStatus, title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun readinessText(status: AgentCallStatus): String = if (status.ready) {
        context.getString(R.string.notification_ready)
    } else {
        context.getString(R.string.notification_not_ready)
    }

    private fun blockedText(status: AgentCallStatus): String {
        if (status.ready) return ""
        if (status.blockers.isEmpty()) {
            return context.getString(R.string.notification_blocked_pending)
        }
        return status.blockers.joinToString("；") { StatusText.blocker(it) }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 接管 action：Intent **data** 编码 SessionId，因此不同会话是不同的 PendingIntent 身份，
     * 旧通知不会因为 requestCode 相同而“指向新通话”。
     */
    private fun takeoverIntent(id: SessionId): PendingIntent {
        val data = TakeoverTarget.encode(TakeoverTarget.of(id))
        val intent = Intent(context, AgentCallService::class.java)
            .setAction(AgentCallService.ACTION_TAKEOVER)
            .setData(Uri.parse(data))
        return PendingIntent.getService(
            context,
            TakeoverTarget.requestCode(TakeoverTarget.of(id)),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID: String = "agentcall_session"

        /** 前台服务与会话共用一个通知 id：会话切换时旧通知被替换而不是并存。 */
        const val NOTIFICATION_ID: Int = 1001
    }
}
