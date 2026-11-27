package com.ai.assistance.operit.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalRuntimeTest {

    @Test
    fun `alpine login command is an interactive login shell without a script operand`() {
        // `sh -l /etc/operit/terminal.sh` 会把 rc 当成脚本执行完就退出，PTY 立刻关闭。
        // 本地终端的提示符钩子由 ENV=/etc/operit/prompt.sh 提供，不占用位置参数。
        assertEquals("/bin/sh -l -i", TerminalRuntime.ALPINE.buildLoginCommand())
    }

    @Test
    fun `debian login command has no rcfile operand`() {
        // 本地终端由宿主 `ProotCommand` 直接 exec `bash --login -i`；
        // 这份字符串只服务 SSH / chroot 旧路径，因此不能再带 --rcfile。
        assertEquals("/bin/bash -l -i", TerminalRuntime.DEBIAN_LIKE.buildLoginCommand())
    }

    @Test
    fun `apt suite follows the distribution instead of a hardcoded noble`() {
        // Debian 13 的代号是 trixie；写成 noble 会让 apt update 全部 404。
        assertEquals("noble", TerminalRuntime.forDistribution("ubuntu-24_04").aptSuite)
        assertEquals("trixie", TerminalRuntime.forDistribution("debian-13").aptSuite)
        // Fedora 不吃 /etc/apt/sources.list：空串表示不写这个文件。
        assertEquals("", TerminalRuntime.forDistribution("fedora-42").aptSuite)
        // Alpine 走 apk 仓库；字段存在但不参与 apt 写入。
        assertEquals(
            PackageSourceSpec.AlpineRepositories,
            TerminalRuntime.forDistribution("alpine").packageSource,
        )
    }
}
