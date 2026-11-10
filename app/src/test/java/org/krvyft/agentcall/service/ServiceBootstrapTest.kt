package org.krvyft.agentcall.service

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.rules.ConfigValidator
import org.krvyft.agentcall.core.session.SessionLimits

/**
 * 服务就绪判定的 JVM 测试（Task 7B2，spec §6.2/§8/§11）。
 *
 * 核心不变量：**启动请求本身不是就绪**。判定只来自探针事实，缺一项即 false，
 * 调用方必须保留原响铃；任何一次 true 都不缓存（下一次必须重新探测）。
 */
class ServiceBootstrapTest {

    private class Fakes(
        var bridge: Boolean = true,
        var hook: HookPresence = HookPresence.PRESENT,
        var enabled: Boolean = true,
        var valid: Boolean = true,
        var calibration: Boolean = true,
        var online: Boolean = true,
    ) {
        val bridgeCalls = AtomicInteger()
        val hookCalls = AtomicInteger()
        val configCalls = AtomicInteger()
        val calibrationCalls = AtomicInteger()
        val networkCalls = AtomicInteger()

        val probes = ReadinessProbes(
            bridge = BridgeProbe {
                bridgeCalls.incrementAndGet()
                bridge
            },
            hook = HookPresenceProbe {
                hookCalls.incrementAndGet()
                hook
            },
            config = ConfigProbe {
                configCalls.incrementAndGet()
                ConfigFacts(enabled = enabled, valid = valid)
            },
            calibration = CalibrationProbe {
                calibrationCalls.incrementAndGet()
                calibration
            },
            network = NetworkProbe {
                networkCalls.incrementAndGet()
                online
            },
        )

        fun bootstrap(probeTimeoutMs: Long = ServiceBootstrap.PROBE_TIMEOUT_MS): ServiceBootstrap =
            ServiceBootstrap(probes, probeTimeoutMs = probeTimeoutMs)
    }

    @Test
    fun allProbesGreenIsReady() = runBlocking {
        val decision = Fakes().bootstrap().evaluate()
        assertTrue(decision.ready)
        assertTrue(decision.blockers.isEmpty())
        assertTrue(decision.codes.isEmpty())
    }

    @Test
    fun startRequestAloneIsNotReady() = runBlocking {
        // “服务已被启动”不是就绪事实：桥接控制不可达时，ensureReady 必须为 false。
        val fakes = Fakes(bridge = false)
        val bootstrap = fakes.bootstrap()
        assertFalse(bootstrap.ensureReady())
        assertEquals(listOf(BootstrapBlocker.BRIDGE_UNREACHABLE), bootstrap.evaluate().blockers)
        assertEquals(2, fakes.bridgeCalls.get())
    }

    @Test
    fun readinessIsReevaluatedOnEveryCallAndNeverCached() = runBlocking {
        val fakes = Fakes()
        val bootstrap = fakes.bootstrap()
        assertTrue(bootstrap.ensureReady())
        fakes.bridge = false
        assertFalse(bootstrap.ensureReady())
        assertEquals(2, fakes.bridgeCalls.get())
        fakes.bridge = true
        assertTrue(bootstrap.ensureReady())
        assertEquals(3, fakes.bridgeCalls.get())
    }

    @Test
    fun unknownHookPresenceBlocksAnswer() = runBlocking {
        val decision = Fakes(hook = HookPresence.UNKNOWN).bootstrap().evaluate()
        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.HOOK_PRESENCE_UNKNOWN), decision.blockers)
    }

    @Test
    fun missingHookBlocksAnswer() = runBlocking {
        val decision = Fakes(hook = HookPresence.ABSENT).bootstrap().evaluate()
        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.HOOK_NOT_CONNECTED), decision.blockers)
    }

    @Test
    fun disabledConfigBlocksAnswer() = runBlocking {
        val decision = Fakes(enabled = false).bootstrap().evaluate()
        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.CONFIG_DISABLED), decision.blockers)
    }

    @Test
    fun invalidConfigBlocksAnswer() = runBlocking {
        val decision = Fakes(valid = false).bootstrap().evaluate()
        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.CONFIG_INVALID), decision.blockers)
    }

    @Test
    fun unverifiedCalibrationBlocksAnswer() = runBlocking {
        val decision = Fakes(calibration = false).bootstrap().evaluate()
        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.CALIBRATION_BLOCKED), decision.blockers)
    }

    @Test
    fun offlineNetworkBlocksAnswer() = runBlocking {
        val decision = Fakes(online = false).bootstrap().evaluate()
        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.NETWORK_UNREACHABLE), decision.blockers)
    }

    @Test
    fun allBlockersAreReportedInAStableOrder() = runBlocking {
        val decision = Fakes(
            bridge = false,
            hook = HookPresence.UNKNOWN,
            enabled = false,
            valid = false,
            calibration = false,
            online = false,
        ).bootstrap().evaluate()
        assertFalse(decision.ready)
        assertEquals(
            listOf(
                BootstrapBlocker.BRIDGE_UNREACHABLE,
                BootstrapBlocker.HOOK_PRESENCE_UNKNOWN,
                BootstrapBlocker.CONFIG_DISABLED,
                BootstrapBlocker.CONFIG_INVALID,
                BootstrapBlocker.CALIBRATION_BLOCKED,
                BootstrapBlocker.NETWORK_UNREACHABLE,
            ),
            decision.blockers,
        )
    }

    @Test
    fun probeFailuresAreMachineReadableCodes() = runBlocking {
        val decision = Fakes(bridge = false, hook = HookPresence.ABSENT).bootstrap().evaluate()
        val codes = decision.codes
        assertEquals(codes.distinct(), codes)
        assertTrue(codes.all { code -> code.matches(Regex("[a-z][a-z0-9_]*")) })
    }

    @Test
    fun aProbeThatNeverReturnsFailsClosedAndIsBounded() = runBlocking {
        val probes = ReadinessProbes(
            bridge = BridgeProbe { awaitCancellation() },
            hook = HookPresenceProbe { HookPresence.PRESENT },
            config = ConfigProbe { ConfigFacts(enabled = true, valid = true) },
            calibration = CalibrationProbe { true },
            network = NetworkProbe { true },
        )
        val bootstrap = ServiceBootstrap(probes, probeTimeoutMs = 100L)
        val startedAt = System.nanoTime()
        assertFalse(bootstrap.ensureReady())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("elapsed=" + elapsedMs, elapsedMs in 50L..2_000L)
        assertEquals(listOf(BootstrapBlocker.BRIDGE_UNREACHABLE), bootstrap.evaluate().blockers)
    }

    @Test
    fun hookPresenceWindowMatchesTheFiveSecondHeartbeatTimeout() {
        assertEquals(5_000L, ServiceBootstrap.HOOK_PRESENCE_WINDOW_MS)
        assertEquals(SessionLimits.HEARTBEAT_TIMEOUT_MS, ServiceBootstrap.HOOK_PRESENCE_WINDOW_MS)
        assertEquals(ServiceBootstrap.HOOK_PRESENCE_WINDOW_MS, ServiceBootstrap.PROBE_TIMEOUT_MS)
    }

    @Test
    fun redactedConfigProbeUsesTheCoreValidator() = runBlocking {
        // 地区不可用是 ConfigValidator 的判定；探针必须复用同一个校验器，不能自写一套。
        val invalid = RedactedConfigProbe {
            "{\"config\":{\"enabled\":true,\"mode\":\"BLACKLIST\",\"blacklist\":[],\"whitelist\":[]," +
                "\"region\":\"ZZ\",\"handleUnknownNumbers\":false,\"answerDelayMs\":3000," +
                "\"maxCallDurationMs\":180000,\"enabledPhoneAccounts\":[],\"asr\":null,\"llm\":null," +
                "\"tts\":null,\"voice\":\"\",\"speed\":1.0,\"openingLine\":\"\",\"prompt\":\"\"," +
                "\"revision\":7},\"secrets\":{\"ASR\":\"MISSING\",\"LLM\":\"MISSING\",\"TTS\":\"MISSING\"}}"
        }
        assertEquals(ConfigFacts(enabled = true, valid = false), invalid.read())

        val valid = RedactedConfigProbe {
            "{\"config\":{\"enabled\":true,\"mode\":\"BLACKLIST\",\"blacklist\":[],\"whitelist\":[]," +
                "\"region\":\"CN\",\"handleUnknownNumbers\":false,\"answerDelayMs\":3000," +
                "\"maxCallDurationMs\":180000,\"enabledPhoneAccounts\":[],\"asr\":null,\"llm\":null," +
                "\"tts\":null,\"voice\":\"\",\"speed\":1.0,\"openingLine\":\"\",\"prompt\":\"\"," +
                "\"revision\":7},\"secrets\":{\"ASR\":\"MISSING\",\"LLM\":\"MISSING\",\"TTS\":\"MISSING\"}}"
        }
        assertEquals(ConfigFacts(enabled = true, valid = true), valid.read())
    }

    @Test
    fun redactedConfigProbeFailsClosedOnUnusablePayloads() = runBlocking {
        assertEquals(ConfigFacts(enabled = false, valid = false), RedactedConfigProbe { null }.read())
        assertEquals(ConfigFacts(enabled = false, valid = false), RedactedConfigProbe { "not json" }.read())
        assertEquals(ConfigFacts(enabled = false, valid = false), RedactedConfigProbe { "{}" }.read())
        assertEquals(
            ConfigFacts(enabled = false, valid = false),
            RedactedConfigProbe { "{\"config\":{}}" }.read(),
        )
        // 真实校验器对默认启用的正常配置应判定有效，证明上面失败只来自负载不可用。
        assertTrue(ConfigValidator.validate(org.krvyft.agentcall.core.model.AgentConfig()).isValid)
    }

    @Test
    fun productionProbesRecordAnUnavailableHookPresenceInsteadOfFakingIt() {
        // 桥接未向 APP 角色开放 status：必须表达为 UNKNOWN（阻塞自动接听），不是 PRESENT。
        assertEquals(HookPresence.UNKNOWN, HookPresence.fromStatusField(null))
        assertEquals(HookPresence.ABSENT, HookPresence.fromStatusField("false"))
        assertEquals(HookPresence.ABSENT, HookPresence.fromStatusField(false))
        assertEquals(HookPresence.PRESENT, HookPresence.fromStatusField("true"))
        assertEquals(HookPresence.PRESENT, HookPresence.fromStatusField(true))
        assertEquals(HookPresence.UNKNOWN, HookPresence.fromStatusField("unknown"))
    }
}
