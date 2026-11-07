package com.baah.nativehost

import android.content.Context
import android.util.Log
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.lang.ref.Reference
import java.util.concurrent.CompletableFuture

/** A native Tasker run stays active until NEO reports the Python worker's outcome. */
internal object PiTaskDispatcher {
    interface Api : Library {
        fun MaaResourceCreate(): Pointer?
        fun MaaResourceDestroy(resource: Pointer)
        fun MaaResourcePostBundle(resource: Pointer, path: String): Long
        fun MaaResourceWait(resource: Pointer, id: Long): Int
        fun MaaResourceRegisterCustomAction(resource: Pointer, name: String, callback: Action, arg: Pointer?): Byte
        fun MaaTaskerCreate(): Pointer?
        fun MaaTaskerDestroy(tasker: Pointer)
        fun MaaTaskerBindResource(tasker: Pointer, resource: Pointer): Byte
        fun MaaTaskerBindController(tasker: Pointer, controller: Pointer): Byte
        fun MaaTaskerPostTask(tasker: Pointer, entry: String, override: String): Long
        fun MaaTaskerWait(tasker: Pointer, id: Long): Int
    }

    fun interface Action : Callback {
        fun invoke(context: Pointer?, taskId: Long, node: Pointer?, action: Pointer?, params: Pointer?,
                   recoId: Long, box: Pointer?, arg: Pointer?): Byte
    }

    private const val SUCCEEDED = 3000
    private val api by lazy { Native.load("MaaFramework", Api::class.java) }

    class Run {
        val accepted = CompletableDeferred<JSONObject>()
        val outcome = CompletableFuture<Boolean>()
        lateinit var job: Job
    }

    fun launch(scope: CoroutineScope, context: Context, controller: Pointer, entry: PiProject.Entry,
               startRunner: () -> JSONObject): Run {
        val run = Run()
        run.job = scope.launch(Dispatchers.IO) {
            try {
                execute(context, controller, entry, run, startRunner)
            } catch (failure: Throwable) {
                run.accepted.completeExceptionally(failure)
                Log.w("NeoPi", "Tasker could not execute ${entry.node}", failure)
            }
        }
        return run
    }

    private fun execute(context: Context, controller: Pointer, entry: PiProject.Entry, run: Run,
                        startRunner: () -> JSONObject) {
        val bundle = File(context.filesDir, "pi-v2-resource")
        val node = File(bundle, "pipeline/run.json")
        node.parentFile!!.mkdirs()
        context.assets.open("${entry.bundleAsset}/pipeline/run.json").use { source ->
            node.outputStream().use(source::copyTo)
        }

        val callback = Action { _, _, _, _, _, _, _, _ ->
            try {
                val accepted = startRunner()
                val generation = accepted.getInt("runner_generation")
                check(accepted.optBoolean("ok")) { "BAAH 拒绝启动任务" }
                Log.i("NeoPi", "Tasker started ${entry.node}: generation=$generation")
                run.accepted.complete(accepted)
                if (run.outcome.get()) 1 else 0
            } catch (failure: Throwable) {
                run.accepted.completeExceptionally(failure)
                0
            }
        }
        val resource = checkNotNull(api.MaaResourceCreate()) { "无法创建 PI 资源" }
        try {
            val loadId = api.MaaResourcePostBundle(resource, bundle.path)
            check(loadId != 0L && api.MaaResourceWait(resource, loadId) == SUCCEEDED) { "PI 资源加载失败" }
            check(api.MaaResourceRegisterCustomAction(resource, "NeoBaahRun", callback, null).toInt() != 0)
            val tasker = checkNotNull(api.MaaTaskerCreate()) { "无法创建 MaaFramework Tasker" }
            try {
                check(api.MaaTaskerBindResource(tasker, resource).toInt() != 0)
                check(api.MaaTaskerBindController(tasker, controller).toInt() != 0)
                val taskId = api.MaaTaskerPostTask(tasker, entry.node, entry.pipelineOverride)
                check(taskId != 0L) { "无法提交 PI 任务" }
                val status = api.MaaTaskerWait(tasker, taskId)
                Log.i("NeoPi", "Tasker finished ${entry.node}: id=$taskId status=$status")
                check(run.accepted.isCompleted) { "PI 节点没有调用 BAAH" }
                check((status == SUCCEEDED) == run.outcome.getNow(false)) { "PI 状态与 BAAH 运行结果不一致：$status" }
            } finally {
                api.MaaTaskerDestroy(tasker)
            }
        } finally {
            api.MaaResourceDestroy(resource)
            Reference.reachabilityFence(callback)
        }
    }
}
