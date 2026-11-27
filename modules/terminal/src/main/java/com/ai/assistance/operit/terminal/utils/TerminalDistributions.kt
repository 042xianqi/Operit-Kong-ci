package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.data.TerminalDistribution

/**
 * 可下载发行版的注册表。
 *
 * **数据逐字来自** Operit2 `hosts/common/operit-host-native-common/src/TerminalRootfs.rs:44-88`
 * 的 `distributions()`。URL 前缀是 `TerminalRootfs.rs:24` 的
 * `ROOTFS_UPSTREAM = "https://github.com/termux/proot-distro/releases/download"`。
 *
 * 三条记录与 Rust 侧完全一致（id / name / release / 文件名 / sha256 / 字节数 / notice），
 * 便于随时交叉核对。**改动这里之前先确认上游是否也改了**，否则 sha256 会失配。
 *
 * 注意：Alpine **不在**这个列表里。它不是「可下载发行版」，而是构建期打进 APK 的
 * 托管运行时（见 `tools/android-runtime/build_alpine_rootfs_wsl.sh`）。两条路互不相干。
 */
object TerminalDistributions {

    private const val ROOTFS_UPSTREAM = "https://github.com/termux/proot-distro/releases/download"

    /** Ubuntu 24.04 LTS。对应 `TerminalRootfs.rs:46-55`。 */
    val UBUNTU_24_04 = TerminalDistribution(
        id = "ubuntu-24_04",
        rootfsDirectoryName = "ubuntu",
        name = "Ubuntu 24.04 LTS",
        architecture = "aarch64",
        url = "$ROOTFS_UPSTREAM/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
        sha256 = "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa",
        downloadBytes = 64_133_552L,
        notice = "",
    )

    /** Debian 13 (trixie)。对应 `TerminalRootfs.rs:57-64`。 */
    val DEBIAN_13 = TerminalDistribution(
        id = "debian-13",
        name = "Debian 13 (trixie)",
        architecture = "aarch64",
        url = "$ROOTFS_UPSTREAM/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz",
        sha256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
        downloadBytes = 35_409_704L,
        notice = "",
    )

    /**
     * Fedora 42。对应 `TerminalRootfs.rs:66-73`。
     *
     * 上游标注：**与 Android 15 及以上不兼容**。保留条目但可按 notice 过滤。
     */
    val FEDORA_42 = TerminalDistribution(
        id = "fedora-42",
        name = "Fedora 42",
        architecture = "aarch64",
        url = "$ROOTFS_UPSTREAM/v4.29.0/fedora-aarch64-pd-v4.29.0.tar.xz",
        sha256 = "0f683e7c3b250660b490479f0c1c57e7450082f6ddc81375ddc863e611a76c65",
        downloadBytes = 33_788_404L,
        notice = "Upstream reports this image incompatible with Android 15 and later.",
    )

    /** 全部已注册发行版，顺序与 Rust 侧一致。 */
    val all: List<TerminalDistribution> = listOf(UBUNTU_24_04, DEBIAN_13, FEDORA_42)

    /** 按 id 查找。 */
    fun findById(id: String): TerminalDistribution? = all.firstOrNull { it.id == id }

    /**
     * 当前设备可安装的发行版。
     *
     * 对应 `hosts/android/src/terminal.rs:259-262`：
     * 非 aarch64 设备直接返回空列表，不暴露安装入口。
     */
    fun availableOnCurrentDevice(): List<TerminalDistribution> =
        all.filter { it.isSupportedOnCurrentDevice() }
}