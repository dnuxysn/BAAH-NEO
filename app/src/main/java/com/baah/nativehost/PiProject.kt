package com.baah.nativehost

import android.content.Context
import android.util.Log
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.project.PiParser
import com.aliothmoon.maafw.project.PiTextResolver

/** Resolves the PI entry for NEO's existing profile and single-task inputs. */
internal object PiProject {
    data class Entry(val node: String, val bundleAsset: String, val pipelineOverride: String)

    fun resolve(context: Context, singleTask: Boolean): Entry {
        val path = "pi/interface.json"
        val content = context.assets.open(path).bufferedReader().use { it.readText() }
        val project = PiParser.parseInterface(path, content)
        check(project.interfaceVersion == 2L) { "试验任务需要 PI V2" }
        val parsed = PiParser.parseFile(path, checkNotNull(project.root), object : PiTextResolver {
            override fun label(raw: String?) = raw
            override fun description(raw: String?) = raw
        })
        val errors = (project.diagnostics + parsed.diagnostics).filter { it.severity == DiagnosticSeverity.Error }
        check(errors.isEmpty()) { errors.joinToString { it.message.resolve(context) } }
        val name = if (singleTask) "single-task" else "workflow"
        val task = parsed.tasks.single { it.name == name }
        val controller = project.controllers.single { it.name == "Android" && it.type == "Adb" }
        check(task.controllers.isEmpty() || controller.name in task.controllers) { "PI 任务不支持 Android 控制器" }
        val resource = project.resources.single { task.resources.isEmpty() || it.name in task.resources }
        val bundleAsset = path.substringBeforeLast('/') + "/" + resource.paths.single()
        Log.i("NeoPi", "PI V2 resolved: $name -> ${task.entry}")
        return Entry(task.entry, bundleAsset, task.pipelineOverride.toString())
    }
}
