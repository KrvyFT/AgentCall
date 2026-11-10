package org.krvyft.agentcall.bridge.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.ConfigPatch
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.RedactedConfig
import org.krvyft.agentcall.core.model.SecretState
import org.krvyft.agentcall.core.model.SecretUpdate
import org.krvyft.agentcall.core.protocol.RpcCodec
import org.krvyft.agentcall.core.rules.ConfigValidator

/**
 * 配置存储的机器可读错误码。[ConfigStoreException.message] 只含错误码与公开字段，
 * 绝不包含密钥原值。
 */
object ConfigErrorCode {
    /** CAS 冲突：expectedRevision 与磁盘上的 revision 不一致。 */
    const val STALE_REVISION: String = "config_conflict"

    /** 补丁应用后的配置未通过 [ConfigValidator]。 */
    const val INVALID_CONFIG: String = "config_invalid"

    /** 密钥替换值为空。 */
    const val INVALID_SECRET: String = "secret_invalid"

    /** 磁盘上的 schema 比本版本新，拒绝读取/覆盖。 */
    const val UNSUPPORTED_SCHEMA: String = "config_schema_unsupported"

    /** 配置/配对文件内容损坏。 */
    const val CORRUPT: String = "config_corrupt"

    /** 文件系统读写或权限设置失败。 */
    const val STORAGE_FAILED: String = "config_storage_failed"

    /** 配对记录字段不合法。 */
    const val PAIRING_INVALID: String = "pairing_invalid"
}

class ConfigStoreException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 配对时记录并核验的对端身份（root CLI 首次配对时写入）。
 *
 * 包名、签名摘要与版本都由 bridge 进程在配对时从 PackageManager 重新解析，
 * 不接受消息自述。签名摘要是后续授权锚点；版本只作为配对时的证据记录，
 * Google 电话升级后仍需按 spec §9 重新探测能力（不需要重新配对）。
 * 普通请求不能自注册信任。
 */
data class PairingRecord(
    val appPackage: String,
    val appSignatureSha256: String,
    val appVersionName: String,
    val dialerPackage: String,
    val dialerSignatureSha256: String,
    val dialerVersionName: String,
    val pairedAtMs: Long,
)

/**
 * /data/adb/agentcall 下的 root 私有配置主副本（spec §6.4/§11）。
 *
 * - 单文件原子写：公开配置与密钥在同一份 JSON 里，通过同目录临时文件 + rename
 *   一次性替换；revision 与内容一起落盘，CAS 不会读到半份配置。
 * - CAS：[update] 的 expectedRevision 必须等于磁盘 revision，否则抛
 *   [ConfigErrorCode.STALE_REVISION] 且不写任何字节。
 * - schema 迁移：读到旧 schema 先写 config.schema-旧.json.bak 备份再重写；
 *   读到更新的 schema 直接失败，不降级覆盖。
 * - root 私有：目录 0700、文件 0600；密钥只在内存与主副本里，脱敏导出只给
 *   CONFIGURED/MISSING。
 * - 单写者：同一进程内用锁串行化；跨进程并发不在本类承诺内
 *   （特权进程是唯一写入者），需要时由上层保证。
 *
 * @param root 主副本目录；生产为 [DEFAULT_ROOT]，测试注入临时目录。
 * @param clockMs 配对时间来源，测试可注入固定值。
 */
class ConfigStore(
    root: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    private val rootDirectory: File = root

    private val lock = Any()

    init {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw ConfigStoreException(ConfigErrorCode.STORAGE_FAILED, "config root could not be created")
        }
        if (!rootDirectory.isDirectory) {
            throw ConfigStoreException(ConfigErrorCode.STORAGE_FAILED, "config root is not a directory")
        }
        try {
            restrict(rootDirectory, directory = true)
        } catch (io: IOException) {
            throw ConfigStoreException(ConfigErrorCode.STORAGE_FAILED, "config root permissions could not be restricted", io)
        }
    }

    /** 读取脱敏配置；缺失主副本时返回默认配置（总开关关闭，revision 0）。 */
    fun read(): RedactedConfig = synchronized(lock) {
        val stored = load()
        redact(stored.config, stored.secrets)
    }

    /**
     * 以 [expectedRevision] 为 CAS 期望值应用补丁。成功返回新的脱敏配置与递增 revision；
     * 冲突/校验失败/空密钥抛 [ConfigStoreException]，磁盘内容保持不变。
     */
    fun update(expectedRevision: Long, patch: ConfigPatch): RedactedConfig = synchronized(lock) {
        val stored = load()
        if (stored.config.revision != expectedRevision) {
            throw ConfigStoreException(
                ConfigErrorCode.STALE_REVISION,
                "config revision conflict: expected " + expectedRevision + ", current " + stored.config.revision,
            )
        }
        val nextConfig = applyPatch(stored.config, patch)
        val validation = ConfigValidator.validate(nextConfig)
        if (!validation.isValid) {
            // 校验问题只含公开字段；绝不拼接任何补丁里的密钥值。
            throw ConfigStoreException(
                ConfigErrorCode.INVALID_CONFIG,
                "config rejected: " + validation.issues.joinToString(",") { it.code.name + "@" + it.field },
            )
        }
        val nextSecrets = applySecrets(stored.secrets, patch.secrets)
        persist(nextConfig, nextSecrets)
        redact(nextConfig, nextSecrets)
    }

    /** 读取某项密钥的当前值；只允许 APP 角色的 IPC 方法调用它，CLI/HOOK 无此方法。 */
    fun readSecret(kind: EndpointKind): String? = synchronized(lock) { load().secrets[kind] }

    /** 读取配对记录；未配对返回 null。 */
    fun readPairing(): PairingRecord? = synchronized(lock) {
        val file = File(rootDirectory, PAIRING_FILE_NAME)
        if (!file.isFile) return null
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (io: IOException) {
            throw ConfigStoreException(ConfigErrorCode.STORAGE_FAILED, "pairing record could not be read", io)
        }
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            throw ConfigStoreException(ConfigErrorCode.CORRUPT, "pairing record is not a JSON object")
        }
        val json = parsed.asJsonObject
        val appPackage = stringOrNull(json, "appPackage")
        val appSignature = stringOrNull(json, "appSignatureSha256")
        val dialerPackage = stringOrNull(json, "dialerPackage")
        val dialerSignature = stringOrNull(json, "dialerSignatureSha256")
        val pairedAt = longOrNull(json, "pairedAtMs")
        if (appPackage == null || appSignature == null || dialerPackage == null ||
            dialerSignature == null || pairedAt == null
        ) {
            throw ConfigStoreException(ConfigErrorCode.CORRUPT, "pairing record is incomplete")
        }
        PairingRecord(
            appPackage = appPackage,
            appSignatureSha256 = appSignature,
            appVersionName = stringOrNull(json, "appVersionName") ?: UNKNOWN_VERSION,
            dialerPackage = dialerPackage,
            dialerSignatureSha256 = dialerSignature,
            dialerVersionName = stringOrNull(json, "dialerVersionName") ?: UNKNOWN_VERSION,
            pairedAtMs = pairedAt,
        )
    }

    /** 写入配对记录（调用方必须已确认 root CLI 身份）。 */
    fun writePairing(record: PairingRecord) = synchronized(lock) {
        if (!isValidPackageName(record.appPackage) ||
            !isValidPackageName(record.dialerPackage) ||
            !isValidSignature(record.appSignatureSha256) ||
            !isValidSignature(record.dialerSignatureSha256) ||
            !isValidVersion(record.appVersionName) ||
            !isValidVersion(record.dialerVersionName)
        ) {
            throw ConfigStoreException(ConfigErrorCode.PAIRING_INVALID, "pairing record fields are invalid")
        }
        val json = JsonObject()
        json.addProperty("appPackage", record.appPackage)
        json.addProperty("appSignatureSha256", record.appSignatureSha256)
        json.addProperty("appVersionName", record.appVersionName)
        json.addProperty("dialerPackage", record.dialerPackage)
        json.addProperty("dialerSignatureSha256", record.dialerSignatureSha256)
        json.addProperty("dialerVersionName", record.dialerVersionName)
        json.addProperty("pairedAtMs", record.pairedAtMs)
        atomicWrite(File(rootDirectory, PAIRING_FILE_NAME), json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /** 记录一次配对（时间来自注入时钟），返回实际写入的记录。 */
    fun pair(
        appPackage: String,
        appSignatureSha256: String,
        appVersionName: String,
        dialerPackage: String,
        dialerSignatureSha256: String,
        dialerVersionName: String,
    ): PairingRecord {
        val record = PairingRecord(
            appPackage = appPackage,
            appSignatureSha256 = appSignatureSha256,
            appVersionName = appVersionName,
            dialerPackage = dialerPackage,
            dialerSignatureSha256 = dialerSignatureSha256,
            dialerVersionName = dialerVersionName,
            pairedAtMs = clockMs(),
        )
        writePairing(record)
        return record
    }

    // ------------------------------------------------------------------ 内部

    private data class Stored(val config: AgentConfig, val secrets: Map<EndpointKind, String>)

    /**
     * 读取主副本。旧 schema 会先备份再原地迁移（revision 不变，不构成一次用户写入），
     * 更新的 schema 直接失败，避免旧版本覆盖新版本配置。
     */
    private fun load(): Stored {
        val file = File(rootDirectory, CONFIG_FILE_NAME)
        if (!file.isFile) return Stored(AgentConfig(), emptyMap())
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (io: IOException) {
            throw ConfigStoreException(ConfigErrorCode.STORAGE_FAILED, "config file could not be read", io)
        }
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config file is not a JSON object")
        }
        val json = parsed.asJsonObject
        val schema = schemaOf(json)
        if (schema > SCHEMA_VERSION) {
            throw ConfigStoreException(
                ConfigErrorCode.UNSUPPORTED_SCHEMA,
                "config schema " + schema + " is newer than supported " + SCHEMA_VERSION,
            )
        }
        val config = RpcCodec.decodeAgentConfig(text)
            ?: throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config file could not be decoded")
        if (schema < SCHEMA_VERSION) {
            backupLegacy(file, schema, text)
            persist(config, emptyMap())
            return Stored(config, emptyMap())
        }
        return Stored(config, decodeSecrets(json))
    }

    private fun schemaOf(json: JsonObject): Int {
        val element = json.get("schema") ?: return 0
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config schema field is invalid")
        }
        val schema = element.asInt
        if (schema < 0) throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config schema field is invalid")
        return schema
    }

    private fun decodeSecrets(json: JsonObject): Map<EndpointKind, String> {
        val element = json.get("secrets") ?: return emptyMap()
        if (element.isJsonNull) return emptyMap()
        if (!element.isJsonObject) {
            throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config secrets section is invalid")
        }
        val secrets = mutableMapOf<EndpointKind, String>()
        for ((kindName, value) in element.asJsonObject.entrySet()) {
            val kind = EndpointKind.entries.firstOrNull { it.name == kindName }
                ?: throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config contains an unknown secret kind")
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isEmpty()) {
                throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config contains an invalid secret value")
            }
            secrets[kind] = value.asString
        }
        return secrets
    }

    private fun applyPatch(current: AgentConfig, patch: ConfigPatch): AgentConfig = current.copy(
        enabled = patch.enabled ?: current.enabled,
        mode = patch.mode ?: current.mode,
        blacklist = patch.blacklist ?: current.blacklist,
        whitelist = patch.whitelist ?: current.whitelist,
        region = patch.region ?: current.region,
        handleUnknownNumbers = patch.handleUnknownNumbers ?: current.handleUnknownNumbers,
        answerDelayMs = patch.answerDelayMs ?: current.answerDelayMs,
        maxCallDurationMs = patch.maxCallDurationMs ?: current.maxCallDurationMs,
        enabledPhoneAccounts = patch.enabledPhoneAccounts ?: current.enabledPhoneAccounts,
        asr = mergeEndpoint(current.asr, patch.asr),
        llm = mergeEndpoint(current.llm, patch.llm),
        tts = mergeEndpoint(current.tts, patch.tts),
        voice = patch.voice ?: current.voice,
        speed = patch.speed ?: current.speed,
        openingLine = patch.openingLine ?: current.openingLine,
        prompt = patch.prompt ?: current.prompt,
        revision = current.revision + 1,
    )

    /** 补丁里 null 表示保持原值；非 null 一律按归一化规则处理。 */
    private fun mergeEndpoint(current: ApiEndpoint?, patched: ApiEndpoint?): ApiEndpoint? =
        if (patched == null) current else normalizeEndpoint(patched)

    /** 唯一端点归一化规则：空端点（baseUrl 与 model 均为空）表示清除 → null，绝不持久化。 */
    private fun normalizeEndpoint(endpoint: ApiEndpoint): ApiEndpoint? =
        if (endpoint.baseUrl.isEmpty() && endpoint.model.isEmpty()) null else endpoint

    private fun applySecrets(
        current: Map<EndpointKind, String>,
        updates: Map<EndpointKind, SecretUpdate>,
    ): Map<EndpointKind, String> {
        val next = current.toMutableMap()
        for ((kind, update) in updates) {
            when (update) {
                is SecretUpdate.Keep -> Unit
                is SecretUpdate.Clear -> next.remove(kind)
                is SecretUpdate.Replace -> {
                    if (update.value.isBlank()) {
                        // 只报告种类，不回显用户输入的值。
                        throw ConfigStoreException(
                            ConfigErrorCode.INVALID_SECRET,
                            "secret value for " + kind.name + " must not be blank",
                        )
                    }
                    next[kind] = update.value
                }
            }
        }
        return next
    }

    private fun redact(config: AgentConfig, secrets: Map<EndpointKind, String>): RedactedConfig {
        val states = EndpointKind.entries.associateWith { kind ->
            if (secrets.containsKey(kind)) SecretState.CONFIGURED else SecretState.MISSING
        }
        return RedactedConfig(config = config, secrets = states)
    }

    private fun backupLegacy(file: File, schema: Int, originalText: String) {
        val backup = File(rootDirectory, "config.schema-" + schema + ".json.bak")
        atomicWrite(backup, originalText.toByteArray(StandardCharsets.UTF_8))
    }

    /** 公开配置与密钥同文件落盘：一次 rename 同时提交内容与 revision。 */
    private fun persist(config: AgentConfig, secrets: Map<EndpointKind, String>) {
        val encoded = runCatching { JsonParser.parseString(RpcCodec.encodeAgentConfig(config)) }.getOrNull()
        if (encoded == null || !encoded.isJsonObject) {
            throw ConfigStoreException(ConfigErrorCode.CORRUPT, "config could not be encoded")
        }
        val json = encoded.asJsonObject
        json.addProperty("schema", SCHEMA_VERSION)
        json.addProperty("revision", config.revision)
        val secretsJson = JsonObject()
        for ((kind, value) in secrets) {
            secretsJson.addProperty(kind.name, value)
        }
        json.add("secrets", secretsJson)
        atomicWrite(
            File(rootDirectory, CONFIG_FILE_NAME),
            json.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    /** 同目录临时文件 + fsync + rename；失败时清理临时文件，绝不留半份配置。 */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            restrict(temp, directory = false)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (io: IOException) {
            // 只报告异常类型：路径与内容都不进入机器可读错误。
            throw ConfigStoreException(ConfigErrorCode.STORAGE_FAILED, "config write failed: " + io.javaClass.simpleName, io)
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    private fun restrict(target: File, directory: Boolean) {
        val permissions = PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")
        try {
            Files.setPosixFilePermissions(target.toPath(), permissions)
        } catch (unsupported: UnsupportedOperationException) {
            target.setReadable(false, false)
            target.setWritable(false, false)
            target.setExecutable(false, false)
            target.setReadable(true, true)
            target.setWritable(true, true)
            if (directory) target.setExecutable(true, true)
        }
    }

    private fun isValidPackageName(value: String): Boolean =
        value.length in 3..128 && PACKAGE_PATTERN.matches(value)

    private fun isValidSignature(value: String): Boolean = SIGNATURE_PATTERN.matches(value)

    private fun isValidVersion(value: String): Boolean =
        value.isNotEmpty() && value.length <= 128 && value.none { it.isISOControl() }

    private fun stringOrNull(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun longOrNull(json: JsonObject, name: String): Long? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val asDouble = element.asDouble
        val asLong = element.asLong
        return if (asDouble == asLong.toDouble()) asLong else null
    }

    companion object {
        /** 当前配置 schema；升级时必须递增并保留旧版本备份。 */
        const val SCHEMA_VERSION: Int = 1

        const val CONFIG_FILE_NAME: String = "config.json"
        const val PAIRING_FILE_NAME: String = "pairing.json"

        /** 生产主副本目录。 */
        const val DEFAULT_ROOT: String = "/data/adb/agentcall"

        /** 无法解析版本名时的显式占位，不伪装成真实版本。 */
        const val UNKNOWN_VERSION: String = "UNKNOWN"

        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val SIGNATURE_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}