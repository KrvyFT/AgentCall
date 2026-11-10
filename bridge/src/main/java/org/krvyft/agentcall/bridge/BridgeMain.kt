package org.krvyft.agentcall.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.config.ConfigStoreException
import org.krvyft.agentcall.bridge.diagnostics.AndroidPlatformFacts
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.ReflectiveAudioApiProbe
import org.krvyft.agentcall.bridge.ipc.AndroidPackageIdentitySource
import org.krvyft.agentcall.bridge.ipc.ControlServer
import org.krvyft.agentcall.bridge.ipc.PackageIdentitySource
import org.krvyft.agentcall.bridge.ipc.PeerVerifier
import org.krvyft.agentcall.bridge.ipc.RootFramework
import org.krvyft.agentcall.bridge.ipc.RpcHandler
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.core.protocol.RpcCodec

/**
 * 特权运行容器版本。容器由 app_process 加载，没有 AndroidManifest 版本可用；
 * 任务 9 统一版本来源时只改这一处。
 */
internal object BridgeInfo {
    const val VERSION: String = "1.0"
}

/**
 * 特权运行容器的进程入口（app_process 按类名加载）。
 *
 * 只做四件事：解析配置目录、取系统 PackageManager、启动 [ControlServer]、等待 shutdown。
 * 任何不可用条件都以机器可读错误码退出，绝不降级为“信任所有对端”。
 */
object BridgeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            BridgeRuntime.run(args)
        } catch (t: Throwable) {
            System.err.println(machineErrorJson("bridge_start_failed", t.javaClass.simpleName))
            1
        }
        if (exitCode != 0) {
            System.exit(exitCode)
        }
    }
}

/**
 * 运行期装配。与 [BridgeMain] 分开，便于把可测试的配置/诊断/协议层和
 * 只能在真机/app_process 里成立的 Android 装配区分开。
 */
internal object BridgeRuntime {

    /** args[0] 可选：配置主副本目录；缺省为 [ConfigStore.DEFAULT_ROOT]。 */
    fun run(args: Array<String>): Int {
        val root = File(args.firstOrNull() ?: ConfigStore.DEFAULT_ROOT)

        // 没有系统 PackageManager 就无法核验包名/签名：必须失败退出，而不是信任消息自述。
        val packageManager = RootFramework.packageManager()
        if (packageManager == null) {
            System.err.println(
                machineErrorJson("package_manager_unavailable", "system PackageManager is unavailable in this process"),
            )
            return 1
        }
        val packages: PackageIdentitySource = AndroidPackageIdentitySource(packageManager)

        val store = try {
            ConfigStore(root)
        } catch (error: ConfigStoreException) {
            System.err.println(machineErrorJson(error.code, error.message ?: error.code))
            return 1
        }

        val verifier = PeerVerifier(packages) { runCatching { store.readPairing() }.getOrNull() }
        val probe = DeviceProbe(
            packages = packages,
            platform = AndroidPlatformFacts(),
            audioApiProbe = ReflectiveAudioApiProbe(),
            bridgeVersion = BridgeInfo.VERSION,
        )
        val shutdown = CountDownLatch(1)
        var server: ControlServer? = null
        val dispatcher = ControlDispatcher(
            store = store,
            packages = packages,
            probe = probe,
            shutdown = shutdown,
            connectionStats = {
                val current = server
                if (current == null) 0 to 0L else current.activeConnectionCount to current.rejectedConnectionCount
            },
        )

        // PCM 通道由 ControlServer 独立 bind；本任务没有音频生产者，因此不注入 sink，
        // 也就不会对外宣称一个只会丢帧的音频通道（任务 5 负责接入）。
        val controlServer = ControlServer(verifier = verifier, handler = dispatcher)
        server = controlServer
        try {
            controlServer.start()
        } catch (io: IOException) {
            System.err.println(machineErrorJson("socket_bind_failed", io.javaClass.simpleName))
            return 1
        }

        Runtime.getRuntime().addShutdownHook(
            Thread({ controlServer.close() }, "agentcall-bridge-shutdown"),
        )
        val ready = JsonObject()
        ready.addProperty("state", "ready")
        ready.addProperty("socket", ControlServer.DEFAULT_CONTROL_SOCKET)
        ready.addProperty("version", BridgeInfo.VERSION)
        System.out.println(ready.toString())

        try {
            shutdown.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            controlServer.close()
        }
        return 0
    }
}

/**
 * 固定命令表的分发器。鉴权已在 [ControlServer] 用 [RolePolicy] 完成；
 * 这里按方法名分派，未知方法返回机器可读错误。
 *
 * 密钥只经 config.secret.read（仅 APP）返回；其余方法一律返回脱敏配置。
 * 任何错误消息都不拼接请求负载或密钥。
 */
internal class ControlDispatcher(
    private val store: ConfigStore,
    private val packages: PackageIdentitySource,
    private val probe: DeviceProbe,
    private val shutdown: CountDownLatch,
    private val startedAtMs: Long = System.currentTimeMillis(),
    private val connectionStats: () -> Pair<Int, Long> = { 0 to 0L },
) : RpcHandler {

    override fun handle(peer: PeerIdentity, request: RpcRequest): RpcResponse = try {
        when (request.method) {
            RolePolicy.METHOD_STATUS -> ok(request, statusPayload())
            RolePolicy.METHOD_CONFIG_READ -> ok(request, RpcCodec.encodeRedacted(store.read()))
            RolePolicy.METHOD_CONFIG_SECRET_READ -> secretRead(request)
            RolePolicy.METHOD_CONFIG_WRITE -> configWrite(request)
            RolePolicy.METHOD_PAIR -> pair(request)
            RolePolicy.METHOD_DIAGNOSE -> ok(request, RpcCodec.encodeCapabilityReport(probe.inspect()))
            RolePolicy.METHOD_SHUTDOWN -> {
                shutdown.countDown()
                ok(request, "{\"stopping\":true}")
            }
            RolePolicy.METHOD_SESSION_REQUEST,
            RolePolicy.METHOD_AUDIO_CHANNEL,
            RolePolicy.METHOD_HANDOVER,
            RolePolicy.METHOD_HOOK_SNAPSHOT,
            RolePolicy.METHOD_HOOK_HEARTBEAT,
            RolePolicy.METHOD_CALL_COMMAND_RESULT,
            -> failure(request, "method_not_implemented", "session and audio routing are owned by a later task")
            else -> failure(request, "method_not_implemented", "method is not in the fixed command table")
        }
    } catch (error: ConfigStoreException) {
        // ConfigStoreException.message 只含公开字段与错误码。
        failure(request, error.code, error.message ?: error.code)
    } catch (t: Exception) {
        // 其它异常的文本可能含路径：只回类名。
        failure(request, "internal_error", t.javaClass.simpleName)
    }

    private fun statusPayload(): String {
        val redacted = store.read()
        val pairing = runCatching { store.readPairing() }.getOrNull()
        val (active, rejected) = connectionStats()
        val json = JsonObject()
        json.addProperty("bridgeVersion", BridgeInfo.VERSION)
        json.addProperty("protocolVersion", PROTOCOL_VERSION)
        json.addProperty("revision", redacted.config.revision)
        json.addProperty("enabled", redacted.config.enabled)
        json.addProperty("paired", pairing != null)
        json.addProperty("uptimeMs", System.currentTimeMillis() - startedAtMs)
        json.addProperty("activeConnections", active)
        json.addProperty("rejectedConnections", rejected)
        return json.toString()
    }

    /** 只有 APP 角色能到达这里（RolePolicy 固定表）；HOOK/CLI 都无法读取密钥。 */
    private fun secretRead(request: RpcRequest): RpcResponse {
        val kind = decodeSecretKind(request.payload)
            ?: return failure(request, "payload_invalid", "kind must be one of ASR, LLM, TTS")
        val value = store.readSecret(kind)
        val json = JsonObject()
        json.addProperty("kind", kind.name)
        if (value == null) {
            json.add("value", com.google.gson.JsonNull.INSTANCE)
        } else {
            json.addProperty("value", value)
        }
        return ok(request, json.toString())
    }

    private fun configWrite(request: RpcRequest): RpcResponse {
        val write = RpcCodec.decodeConfigWrite(request.payload)
            ?: return failure(request, "payload_invalid", "config patch payload is invalid")
        return ok(request, RpcCodec.encodeRedacted(store.update(write.expectedRevision, write.patch)))
    }

    /**
     * 配对只接受 CLI_ROOT（RolePolicy 已保证）。签名摘要由本进程重新解析，
     * 不接受消息里自带的签名或角色，普通请求不能自注册信任。
     */
    private fun pair(request: RpcRequest): RpcResponse {
        val packageName = decodePairPackage(request.payload)
            ?: return failure(request, "payload_invalid", "packageName is invalid")
        val appSignature = packages.signatureSha256(packageName)
            ?: return failure(request, "pairing_package_unknown", "package signature could not be resolved")
        val dialerSignature = packages.signatureSha256(PeerVerifier.DIALER_PACKAGE)
            ?: return failure(request, "pairing_dialer_unknown", "dialer signature could not be resolved")
        val record = store.pair(
            appPackage = packageName,
            appSignatureSha256 = appSignature,
            dialerPackage = PeerVerifier.DIALER_PACKAGE,
            dialerSignatureSha256 = dialerSignature,
        )
        val json = JsonObject()
        json.addProperty("appPackage", record.appPackage)
        json.addProperty("appSignatureSha256", record.appSignatureSha256)
        json.addProperty("dialerPackage", record.dialerPackage)
        json.addProperty("dialerSignatureSha256", record.dialerSignatureSha256)
        json.addProperty("pairedAtMs", record.pairedAtMs)
        return ok(request, json.toString())
    }

    private fun decodeSecretKind(payload: String): EndpointKind? {
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (json.keySet() != setOf("kind")) return null
        val kindElement = json.get("kind") ?: return null
        if (!kindElement.isJsonPrimitive || !kindElement.asJsonPrimitive.isString) return null
        val name = kindElement.asString
        return EndpointKind.entries.firstOrNull { it.name == name }
    }

    private fun decodePairPackage(payload: String): String? {
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (json.keySet() != setOf("packageName")) return null
        val element = json.get("packageName") ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val name = element.asString
        if (name.length !in 3..128 || !PACKAGE_PATTERN.matches(name)) return null
        return name
    }

    private fun ok(request: RpcRequest, payload: String): RpcResponse =
        RpcResponse(id = request.id, payload = payload)

    private fun failure(request: RpcRequest, code: String, message: String): RpcResponse =
        RpcResponse(
            id = request.id,
            error = RpcError(code, message.take(ControlServer.MAX_ERROR_MESSAGE_CHARS)),
        )

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}

/** 机器可读错误 JSON（stdout/stderr 都用同一形状，绝不含密钥）。 */
internal fun machineErrorJson(code: String, message: String): String {
    val error = JsonObject()
    error.addProperty("code", code)
    error.addProperty("message", message)
    val root = JsonObject()
    root.add("error", error)
    return root.toString()
}