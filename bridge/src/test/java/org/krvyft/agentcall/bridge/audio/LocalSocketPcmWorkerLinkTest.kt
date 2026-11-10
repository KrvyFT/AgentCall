package org.krvyft.agentcall.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥接 → 音频工作进程 PCM lane 的对端信任判定（Task 7A2）。
 *
 * 服务端 abstract 名字可能被非 root 进程抢先绑定：连接后、发送任何字节之前必须先核对
 * 对端 uid 是 root，否则 fail closed。真机 LocalSocket 不可在 JVM 里跑，因此信任判定
 * 是独立可测的纯函数（[LocalSocketPcmWorkerLink.requireRootServer]）。
 */
class LocalSocketPcmWorkerLinkTest {

    @Test
    fun nonRootWorkerServerUidIsRejectedWithAMachineCode() {
        for (uid in listOf<Int?>(null, 1, 10_001, -1)) {
            val failure = runCatching { LocalSocketPcmWorkerLink.requireRootServer(uid) }.exceptionOrNull()
            assertTrue("uid " + uid + " 必须被拒绝", failure is AudioPcmLinkException)
            assertEquals("audio_pcm_worker_untrusted", (failure as AudioPcmLinkException).code)
        }
    }

    @Test
    fun rootWorkerServerUidIsAccepted() {
        assertEquals(0, AudioPcmProtocol.ROOT_UID)
        LocalSocketPcmWorkerLink.requireRootServer(AudioPcmProtocol.ROOT_UID)
        assertThrows(AudioPcmLinkException::class.java) {
            LocalSocketPcmWorkerLink.requireRootServer(null)
        }
    }
}
