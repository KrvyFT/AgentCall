package org.krvyft.agentcall.bridge.config

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * root 私有权限（目录 0700、文件 0600）的唯一执行点。
 *
 * 优先使用 POSIX 权限；非 POSIX 文件系统（FAT/exFAT、部分网络或 Windows 挂载）没有
 * POSIX 属性时回退到 [File.setReadable]/[File.setWritable]/[File.setExecutable]。
 * 回退路径必须检查每个布尔返回值：只要无法保证“仅 owner 可读写”，就抛
 * [ConfigErrorCode.STORAGE_FAILED] 拒绝继续，绝不在权限未收紧的情况下写入明文密钥。
 */
internal object PrivateFilePermissions {

    /** 收紧 [target]；无法保证 owner-only 时抛 [ConfigStoreException]（STORAGE_FAILED）。 */
    fun enforce(target: File, directory: Boolean) {
        enforceWith(
            target = target,
            directory = directory,
            posixSetter = { file, wanted -> Files.setPosixFilePermissions(file.toPath(), wanted) },
            legacySetter = ::legacyRestrict,
        )
    }

    /** 测试注入点：模拟非 POSIX 文件系统与无法收紧的 File API。 */
    internal fun enforceWith(
        target: File,
        directory: Boolean,
        posixSetter: (File, Set<PosixFilePermission>) -> Unit,
        legacySetter: (File, Boolean) -> Boolean,
    ) {
        val wanted = PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")
        try {
            posixSetter(target, wanted)
            return
        } catch (unsupported: UnsupportedOperationException) {
            // 非 POSIX：没有 setPosixFilePermissions 可用，只能走 File 级回退。
        }
        if (!legacySetter(target, directory)) {
            throw ConfigStoreException(
                ConfigErrorCode.STORAGE_FAILED,
                "file permissions could not be restricted to owner-only",
            )
        }
    }

    /**
     * File 级回退：先移除所有（含组/其他）权限，再只给 owner 读/写（目录再加执行）。
     * 返回 false 表示至少一步没有生效，调用方必须失败关闭。
     */
    internal fun legacyRestrict(target: File, directory: Boolean): Boolean {
        var enforced = target.setReadable(false, false)
        enforced = target.setWritable(false, false) && enforced
        enforced = target.setExecutable(false, false) && enforced
        enforced = target.setReadable(true, true) && enforced
        enforced = target.setWritable(true, true) && enforced
        if (directory) {
            enforced = target.setExecutable(true, true) && enforced
        }
        return enforced
    }
}
