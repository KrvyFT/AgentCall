package org.krvyft.agentcall.bridge.audio

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking

/**
 * 音频工作角色的独立 app_process 入口（spec §6.3，C1）。
 *
 * 由模块 service.sh 以 root app_process 拉起，只做四件事：构造真实 Android 音频适配、
 * 构造 [AudioWorker]、绑定 root 私有的角色通道、等待关闭。它绝不下发通话命令，也绝不
 * 在空闲时打开音频；本文件的真机行为（SELinux 域、AudioManager 反射、HAL）未验证。
 */
object AudioWorkerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            AudioWorkerRuntime.run(args)
        } catch (t: Throwable) {
            System.err.println("{\"error\":{\"code\":\"audio_worker_start_failed\",\"message\":\"" + t.javaClass.simpleName + "\"}}")
            1
        }
        if (exitCode != 0) {
            System.exit(exitCode)
        }
    }
}

internal object AudioWorkerRuntime {

    fun run(args: Array<String>): Int {
        val provider = AndroidAudioPlatform.audioManagerProvider()
        val isolation = AndroidAudioManagerIsolation(provider)
        val bridge = FrameworkAudioBridge(AndroidCallAudioFramework(provider))
        val lease = ArmedSessionLease()
        val worker = AudioWorker(bridge, isolation, AndroidAudioPlatform.clock(), lease)
        worker.start()
        val dispatcher = AudioWorkerDispatcher(worker, lease)
        val server = AudioWorkerSocketServer(dispatcher)
        // PCM lane 与生命周期通道分开 bind：音频是长生命周期双向流，绝不混进角色 JSON 通道。
        val pcmService = AudioPcmSessionService(
            sessions = PcmAudioSessionSource { id -> worker.activeAudio(id) },
            lease = lease,
        )
        val pcmServer = AudioWorkerPcmSocketServer(pcmService)
        try {
            server.start()
        } catch (io: java.io.IOException) {
            System.err.println("{\"error\":{\"code\":\"audio_role_socket_bind_failed\",\"message\":\"" + io.javaClass.simpleName + "\"}}")
            return 1
        }
        try {
            pcmServer.start()
        } catch (io: java.io.IOException) {
            System.err.println("{\"error\":{\"code\":\"audio_pcm_socket_bind_failed\",\"message\":\"" + io.javaClass.simpleName + "\"}}")
            server.close()
            return 1
        }
        System.out.println(
            "{\"state\":\"ready\",\"role\":\"AUDIO_WORKER\",\"socket\":\"" + AudioRoleProtocol.SOCKET_NAME +
                "\",\"pcmSocket\":\"" + AudioPcmProtocol.SOCKET_NAME + "\"}",
        )
        val shutdown = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    pcmServer.close()
                    server.close()
                    runBlocking { worker.stopKeepingIsolation() }
                    worker.close()
                    shutdown.countDown()
                },
                "agentcall-audio-worker-shutdown",
            ),
        )
        try {
            shutdown.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return 0
    }
}
