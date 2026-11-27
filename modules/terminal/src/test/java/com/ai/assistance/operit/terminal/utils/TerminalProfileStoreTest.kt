package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.data.TerminalProfile
import com.ai.assistance.operit.terminal.data.TerminalProfileSettings
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端实例注册表的持久化与约束。
 *
 * `switchActiveProfile` / `removeProfile` 的真实行为拆成了
 * [TerminalProfileSettings.withDefault] / [TerminalProfileSettings.withoutProfile] 两个纯函数，
 * 这里连同 [TerminalProfileStore] 的落盘一起验证：切换必须先写盘再读回，
 * 否则进程重启后用户的选择会丢。
 */
class TerminalProfileStoreTest {

    private fun profile(id: String): TerminalProfile =
        TerminalProfile(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            distributionId = id,
            rootfsPath = "/data/user/0/test/files/usr/var/lib/proot-distro/installed-rootfs/$id",
            installed = true,
            createdAtMillis = 0L,
        )

    private fun twoProfiles(): TerminalProfileSettings =
        TerminalProfileSettings(
            revision = 1,
            defaultProfileId = "alpine",
            profiles = listOf(profile("alpine"), profile("ubuntu")),
        )

    private fun withStore(block: (TerminalProfileStore) -> Unit) {
        val dir = Files.createTempDirectory("operit-profiles-test").toFile()
        try {
            block(TerminalProfileStore(File(dir, "terminal-profiles.json")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `switched default profile survives a save and load round trip`() = withStore { store ->
        store.save(twoProfiles())

        val switched = requireNotNull(store.load()?.withDefault("ubuntu")) {
            "withDefault must accept a registered profile id"
        }
        store.save(switched)

        val reloaded = requireNotNull(store.load()) { "registry must load after save" }
        assertEquals("ubuntu", reloaded.defaultProfileId)
        assertEquals(2L, reloaded.revision)
        assertEquals("ubuntu", reloaded.defaultProfile()?.id)
    }

    @Test
    fun `switching to an unknown profile is rejected`() {
        assertNull(twoProfiles().withDefault("debian"))
    }

    @Test
    fun `removing the last profile fails`() = withStore { store ->
        store.save(
            TerminalProfileSettings(
                revision = 1,
                defaultProfileId = "alpine",
                profiles = listOf(profile("alpine")),
            )
        )
        val failure = runCatching { store.load()!!.withoutProfile("alpine") }.exceptionOrNull()
        assertTrue(
            "removing the last profile must throw, got $failure",
            failure is IllegalArgumentException &&
                failure.message.orEmpty().contains("last terminal profile"),
        )
        assertEquals(1, store.load()?.profiles?.size)
    }

    @Test
    fun `removing the active profile fails`() = withStore { store ->
        store.save(twoProfiles())
        val failure = runCatching { store.load()!!.withoutProfile("alpine") }.exceptionOrNull()
        assertTrue(
            "removing the active profile must throw, got $failure",
            failure is IllegalArgumentException &&
                failure.message.orEmpty().contains("active terminal profile"),
        )

        // 非默认实例可以删，默认实例保持不变。
        val removed = requireNotNull(store.load()?.withoutProfile("ubuntu"))
        store.save(removed)
        val reloaded = requireNotNull(store.load())
        assertEquals(listOf("alpine"), reloaded.profiles.map { it.id })
        assertEquals("alpine", reloaded.defaultProfileId)
    }

    @Test
    fun `removing an unknown profile is a no-op signal`() {
        assertNull(twoProfiles().withoutProfile("debian"))
    }
}
