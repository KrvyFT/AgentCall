package org.krvyft.agentcall.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.krvyft.agentcall.bridge.ipc.ControlServer
import org.krvyft.agentcall.bridge.ipc.PeerVerifier
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.core.protocol.RpcCodec

/** CLI 的传输抽象；生产走 abstract Unix socket，测试可注入替身。 */
fun interface CliTransport {
    fun call(request: RpcRequest): RpcResponse
}

/**
 * root CLI：固定六条命令，不提供任意 shell/任意 RPC。
 *
 * - status / config-get / diagnose / shutdown 无参数。
 * - config-set 只从 stdin 读 JSON；密钥值绝不作为 argv 参数出现，也不回显。
 * - pair [packageName] 必须由 root 进程执行；签名摘要由 bridge 进程重新解析，
 *   CLI 与普通请求都不能自带签名。
 *
 * 输出始终是机器可读 JSON；错误只含错误码与固定短消息，不含密钥或路径。
 */
object AgentCallCli {

    const val EXIT_OK: Int = 0
    const val EXIT_SERVER_ERROR: Int = 1
    const val EXIT_USAGE: Int = 2
    const val EXIT_TRANSPORT: Int = 3

    const val ERROR_USAGE: String = "usage_invalid"
    const val ERROR_PAYLOAD_INVALID: String = "payload_invalid"
    const val ERROR_PAYLOAD_TOO_LARGE: String = "payload_too_large"
    const val ERROR_PAIR_REQUIRES_ROOT: String = "pair_requires_root"
    const val ERROR_TRANSPORT: String = "transport_failed"
    const val ERROR_CLI_FAILED: String = "cli_failed"

    private val COMMANDS = setOf("status", "config-get", "config-set", "pair", "diagnose", "shutdown")
    private const val MAX_STDIN_BYTES = RpcCodec.MAX_CONTROL_FRAME_BYTES - 1024
    private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            run(args, System.`in`, System.out)
        } catch (t: Throwable) {
            System.out.println(machineErrorJson(ERROR_CLI_FAILED, t.javaClass.simpleName))
            EXIT_USAGE
        }
        if (exitCode != EXIT_OK) {
            System.exit(exitCode)
        }
    }

    fun run(
        args: Array<String>,
        stdin: InputStream,
        stdout: PrintStream,
        transport: CliTransport = LocalSocketCliTransport(),
    ): Int {
        val command = args.firstOrNull() ?: return usage(stdout, "missing command")
        if (command !in COMMANDS) return usage(stdout, "unknown command")
        val requestId = "cli-" + UUID.randomUUID().toString()

        val request = when (command) {
            "status" -> {
                if (args.size != 1) return usage(stdout, "status takes no arguments")
                RpcRequest(requestId, RolePolicy.METHOD_STATUS)
            }
            "config-get" -> {
                if (args.size != 1) return usage(stdout, "config-get takes no arguments")
                RpcRequest(requestId, RolePolicy.METHOD_CONFIG_READ)
            }
            "config-set" -> {
                if (args.size != 1) return usage(stdout, "config-set takes no arguments; send the JSON patch on stdin")
                val bytes = readBounded(stdin)
                    ?: return failure(stdout, ERROR_PAYLOAD_TOO_LARGE, "stdin exceeds the control frame budget", EXIT_USAGE)
                val payload = String(bytes, StandardCharsets.UTF_8)
                if (RpcCodec.decodeConfigWrite(payload) == null) {
                    return failure(stdout, ERROR_PAYLOAD_INVALID, "stdin must be a config patch JSON object", EXIT_USAGE)
                }
                RpcRequest(requestId, RolePolicy.METHOD_CONFIG_WRITE, payload = payload)
            }
            "pair" -> {
                // 首次配对只能由 root CLI 发起；非 root 进程连服务端角色都不会是 CLI_ROOT。
                if (Process.myUid() != 0) {
                    return failure(stdout, ERROR_PAIR_REQUIRES_ROOT, "pairing must be executed by the root CLI", EXIT_USAGE)
                }
                if (args.size > 2) return usage(stdout, "pair takes at most one package name")
                val packageName = args.getOrNull(1) ?: PeerVerifier.AGENTCALL_PACKAGE
                if (!PACKAGE_PATTERN.matches(packageName) || packageName.length > 128) {
                    return failure(stdout, ERROR_PAYLOAD_INVALID, "package name is invalid", EXIT_USAGE)
                }
                val payload = JsonObject().apply { addProperty("packageName", packageName) }.toString()
                RpcRequest(requestId, RolePolicy.METHOD_PAIR, payload = payload)
            }
            "diagnose" -> {
                if (args.size != 1) return usage(stdout, "diagnose takes no arguments")
                RpcRequest(requestId, RolePolicy.METHOD_DIAGNOSE)
            }
            else -> {
                if (args.size != 1) return usage(stdout, "shutdown takes no arguments")
                RpcRequest(requestId, RolePolicy.METHOD_SHUTDOWN)
            }
        }

        return try {
            val response = transport.call(request)
            val error = response.error
            if (error != null) {
                failure(stdout, error.code, error.message, EXIT_SERVER_ERROR)
            } else {
                stdout.println(response.payload)
                EXIT_OK
            }
        } catch (io: IOException) {
            failure(stdout, ERROR_TRANSPORT, "bridge control socket is not reachable", EXIT_TRANSPORT)
        } catch (t: Exception) {
            // 例如 SecurityException：只回固定消息，不回异常文本。
            failure(stdout, ERROR_TRANSPORT, "bridge transport failed", EXIT_TRANSPORT)
        }
    }

    private fun readBounded(stdin: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = stdin.read(chunk)
            if (read < 0) break
            if (buffer.size() + read > MAX_STDIN_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun usage(stdout: PrintStream, reason: String): Int =
        failure(
            stdout,
            ERROR_USAGE,
            "usage: status | config-get | config-set (JSON on stdin) | pair [packageName] | diagnose | shutdown ($reason)",
            EXIT_USAGE,
        )

    private fun failure(stdout: PrintStream, code: String, message: String, exitCode: Int): Int {
        stdout.println(machineErrorJson(code, message))
        return exitCode
    }
}

/**
 * abstract Unix socket 客户端。每次命令一条连接、一个请求、一个响应；
 * 超时由 socket soTimeout 控制，失败抛 [IOException]。
 */
class LocalSocketCliTransport(
    private val socketName: String = ControlServer.DEFAULT_CONTROL_SOCKET,
    private val timeoutMs: Int = ControlServer.READ_TIMEOUT_MS,
) : CliTransport {

    override fun call(request: RpcRequest): RpcResponse {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.setSoTimeout(timeoutMs)
            val output = socket.outputStream
            output.write(RpcCodec.encodeRequestFrame(request))
            output.flush()
            return when (val result = RpcCodec.readResponseFrame(socket.inputStream)) {
                is RpcCodec.ResponseResult.Accepted -> result.response
                is RpcCodec.ResponseResult.Rejected -> RpcResponse(
                    id = request.id,
                    error = RpcError(result.code, result.message),
                )
            }
        } finally {
            try {
                socket.close()
            } catch (ignored: IOException) {
                // 关闭失败不影响命令结果。
            }
        }
    }
}