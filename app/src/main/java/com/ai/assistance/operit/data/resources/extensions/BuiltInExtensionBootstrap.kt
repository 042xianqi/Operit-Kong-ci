package com.ai.assistance.operit.data.resources.extensions

import android.content.Context
import com.ai.assistance.operit.data.resources.packs.PackResources
import com.ai.assistance.operit.data.resources.packs.ResourcePackSpec
import com.ai.assistance.operit.util.logging.AppLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 启动时补齐 APK 已声明、但还没下载到 `files/runtime/resources/` 的内置扩展。
 *
 * 背景（真机证据）：`builtin-extensions.json` 只有声明，40 个包的正文全靠资源仓库
 * 下载；此前唯一触发下载的入口是用户手动点「检查更新」
 * （`ExtensionUpdates.kt`）。新装应用 `loadAvailablePackages` 因此恒为
 * `available=0, errors=0`，日志看不出任何原因。
 *
 * 只补缺失项：已就位（`PackResources.isReady`）的包不会再下载。失败逐个记录，
 * 下次启动自动重试，不做任何降级。
 */
object BuiltInExtensionBootstrap {

    private const val TAG = "BuiltInExtensionBootstrap"

    /** 同时下载的包数。资源仓库是 GitHub Release，2 路并发即可跑满移动网络。 */
    private const val CONCURRENCY = 2

    /** 一次补装的结果；空 Application 启动日志与单测都用它。 */
    data class Outcome(
        val declared: Int,
        val installedBefore: Int,
        val downloaded: Int,
        val failures: List<String>,
    ) {
        val installedAfter: Int get() = installedBefore + downloaded
    }

    suspend fun ensureInstalled(
        context: Context,
        onInstalled: suspend (ResourcePackSpec) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val specs = BuiltInExtensionResources.specs(context)
        val missing = specs.filterNot { spec -> PackResources.isReady(context, spec.id) }
        val installedBefore = specs.size - missing.size
        AppLogger.i(
            TAG,
            "builtin bootstrap: declared=${specs.size} installed=$installedBefore missing=${missing.size}" +
                if (missing.isEmpty()) "" else " ids=${missing.joinToString(",") { it.id }}"
        )
        if (missing.isEmpty()) {
            return@withContext Outcome(specs.size, installedBefore, 0, emptyList())
        }

        val gate = Semaphore(CONCURRENCY)
        val failures = coroutineScope {
            missing
                .map { spec ->
                    async {
                        gate.withPermit {
                            runCatching {
                                PackResources.download(context, spec.id) { _, _ -> }
                            }.onSuccess {
                                AppLogger.i(TAG, "builtin bootstrap: installed ${spec.id}@${spec.version}")
                                onInstalled(spec)
                            }.exceptionOrNull()?.let { error ->
                                AppLogger.e(TAG, "builtin bootstrap: failed ${spec.id}", error)
                                "${spec.id}: ${error.message ?: error.javaClass.simpleName}"
                            }
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        val downloaded = missing.size - failures.size
        AppLogger.i(
            TAG,
            "builtin bootstrap done: installed=$installedBefore+$downloaded/${specs.size} failed=${failures.size}" +
                if (failures.isEmpty()) "" else " failures=${failures.joinToString("; ")}"
        )
        Outcome(specs.size, installedBefore, downloaded, failures)
    }

    /** 资源根目录，仅用于日志定位（真机 `files/runtime/resources/`）。 */
    fun resourcesRoot(context: Context): File = File(context.filesDir, "runtime/resources")
}
