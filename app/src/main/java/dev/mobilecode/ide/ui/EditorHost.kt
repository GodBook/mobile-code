package dev.mobilecode.ide.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import dev.mobilecode.ide.*
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.EditorSettings
import dev.mobilecode.ide.editor.MobileLanguage
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.*
import io.github.rosemoe.sora.widget.MobileCodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

private data class EditorEntry(val editor: MobileCodeEditor,var navigation: Long=0,var appearance: String="")
private class EditorRegistry {
    val entries=mutableMapOf<String,EditorEntry>(); var closed=false; var updating=false
    fun close() { closed=true; entries.values.forEach { it.editor.release() }; entries.clear() }
}

@Composable
fun EditorHost(model: IdeViewModel,state: IdeState,settings: EditorSettings,session: SessionState,diagnostics: List<Diagnostic>,
               modifier: Modifier=Modifier,onEditor: (MobileCodeEditor)->Unit) {
    val project=state.project ?: return
    val document=state.document ?: return
    val registry=remember(project.config.id) { EditorRegistry() }
    DisposableEffect(registry) { onDispose { registry.close() } }
    val path=document.path
    val preview=state.preview!=null
    val key=if(preview) "snapshot:$path:${document.text.hashCode()}" else path
    val currentModel by rememberUpdatedState(model)
    AndroidView(modifier=modifier.testTag("code-editor"),factory={ FrameLayout(it) },update={ container ->
        val entry=registry.entries.getOrPut(key) {
            val editor=MobileCodeEditor(container.context)
            editor.typefaceText=Typeface.MONOSPACE
            editor.tabWidth=4
            editor.isLineNumberEnabled=true
            editor.setEditorLanguage(MobileLanguage(project.config.language,
                document={ if(preview) document else currentModel.document(path) },
                completion={ doc,pos -> currentModel.app.languages.complete(doc,pos) },
                blocks={ ranges -> if(!registry.closed && !editor.isReleased) editor.setFoldRanges(ranges.map { it.first }.toIntArray(),ranges.map { it.second }.toIntArray()) }))
            editor.subscribeEvent(ContentChangeEvent::class.java) { event,_ ->
                if(!registry.updating && !preview && event.action!=ContentChangeEvent.ACTION_SET_NEW_TEXT && currentModel.state.value.project?.config?.id==project.config.id) {
                    val start=event.changeStart; val end=event.changeEnd
                    val delta=(end.line-start.line)*(if(event.action==ContentChangeEvent.ACTION_DELETE) -1 else 1)
                    currentModel.edit(path,editor.text.toString(),start.line,delta,end.line)
                }
            }
            editor.subscribeEvent(SelectionChangeEvent::class.java) { _,_ -> if(!registry.updating && !preview && currentModel.state.value.activePath==path) currentModel.savePosition(editor.cursor.leftLine,editor.cursor.leftColumn) }
            editor.breakpointClick=MobileCodeEditor.BreakpointClick { line -> if(!preview) currentModel.toggleBreakpoint(path,line) }
            registry.updating=true; editor.setText(document.text); registry.updating=false
            EditorEntry(editor)
        }
        val editor=entry.editor
        if(editor.parent!==container) { (editor.parent as? ViewGroup)?.removeView(editor); container.removeAllViews(); container.addView(editor,FrameLayout.LayoutParams(-1,-1)) }
        if(editor.text.toString()!=document.text) { registry.updating=true; editor.setText(document.text); registry.updating=false }
        val appearance="${settings.dark}:${settings.fontSize}:${settings.wordWrap}"
        if(entry.appearance!=appearance) {
            entry.appearance=appearance
            val colors=EditorColorScheme()
            val background=if(settings.dark) 0xFF15181C.toInt() else 0xFFFAFAF7.toInt()
            colors.setColor(EditorColorScheme.WHOLE_BACKGROUND,background)
            colors.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND,background)
            colors.setColor(EditorColorScheme.TEXT_NORMAL,if(settings.dark) 0xFFDCE2EA.toInt() else 0xFF27313D.toInt())
            colors.setColor(EditorColorScheme.LINE_NUMBER,0xFF778595.toInt())
            colors.setColor(EditorColorScheme.CURRENT_LINE,if(settings.dark) 0xFF1F262F.toInt() else 0xFFF0F2ED.toInt())
            colors.setColor(EditorColorScheme.KEYWORD,if(settings.dark) 0xFFC7A5F5.toInt() else 0xFF7645A0.toInt())
            colors.setColor(EditorColorScheme.LITERAL,if(settings.dark) 0xFFA9D68A.toInt() else 0xFF3B6E31.toInt())
            colors.setColor(EditorColorScheme.COMMENT,if(settings.dark) 0xFF7C8A9D.toInt() else 0xFF6B787D.toInt())
            colors.setColor(EditorColorScheme.IDENTIFIER_NAME,if(settings.dark) 0xFF7CC7DA.toInt() else 0xFF127486.toInt())
            editor.colorScheme=colors; editor.setTextSize(settings.fontSize); editor.isWordwrap=settings.wordWrap
        }
        editor.isEditable=!preview && !session.sourceLocked
        val points=if(session.sourceLocked) session.breakpointResults[path] ?: state.breakpoints.filter { it.path==path } else state.breakpoints.filter { it.path==path }
        editor.setBreakpointStates(points.map { it.line }.toIntArray(),IntArray(points.size) { if(points[it].verified) 1 else if(points[it].message.isNotBlank()) -1 else 0 })
        val frame=session.frames.find { it.id==session.frameId } ?: session.frames.firstOrNull()
        editor.setExecutionLine(if(session.phase==SessionPhase.PAUSED && frame?.path?.endsWith("/$path")==true) frame.line else -1)
        val regions=diagnostics.filter { it.path.endsWith("/$path") && (it.version==null || it.version==document.version) }.map { diagnostic ->
            val start=Positions.index(document.text,diagnostic.range.start); val end=Positions.index(document.text,diagnostic.range.end).coerceAtLeast(start+1).coerceAtMost(document.text.length)
            DiagnosticRegion(start,end,if(diagnostic.severity==1) DiagnosticRegion.SEVERITY_ERROR else DiagnosticRegion.SEVERITY_WARNING)
        }
        editor.diagnostics=DiagnosticsContainer().apply { addDiagnostics(regions) }
        if(state.navigation.serial!=0L && entry.navigation!=state.navigation.serial) {
            entry.navigation=state.navigation.serial
            editor.post {
                if(registry.closed || editor.isReleased || editor.parent!==container) return@post
                val line=state.navigation.line.coerceIn(0,editor.lineCount-1)
                editor.setSelection(line,state.navigation.column.coerceIn(0,editor.text.getColumnCount(line)))
            }
        }
        onEditor(editor)
    })
}
