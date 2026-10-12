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
import dev.mobilecode.ide.editor.CodeColors
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.*
import io.github.rosemoe.sora.widget.MobileCodeEditor

private data class EditorEntry(val editor: MobileCodeEditor,var text: String,var navigation: Long=0,
    var dark: Boolean?=null,var fontSize: Float=0f,var wordWrap: Boolean?=null,
    var diagnostics: List<Diagnostic>?=null,var diagnosticVersion: Int=-1)
private class EditorRegistry {
    val entries=mutableMapOf<String,EditorEntry>(); var closed=false; var updating=false
    fun remove(key: String) { entries.remove(key)?.editor?.let { (it.parent as? ViewGroup)?.removeView(it); it.release() } }
    fun close() { closed=true; entries.keys.toList().forEach(::remove) }
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
    val snapshotDocument=if(preview) document else null
    val key=if(preview) "snapshot:${state.navigation.serial}:$path" else path
    val currentModel by rememberUpdatedState(model)
    AndroidView(modifier=modifier.testTag("code-editor"),factory={ FrameLayout(it).apply {
        // Compose must see a focusable host before update() attaches the editor.
        isFocusable=true; isFocusableInTouchMode=true
        descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
    } },update={ container ->
        registry.entries.keys.filter { it!=key && it !in state.documents }.forEach(registry::remove)
        val entry=registry.entries.getOrPut(key) {
            val editor=MobileCodeEditor(container.context)
            val created=EditorEntry(editor,document.text)
            editor.typefaceText=Typeface.MONOSPACE
            editor.tabWidth=4
            editor.isLineNumberEnabled=true
            editor.setEditorLanguage(MobileLanguage(project.config.language,
                document={ snapshotDocument ?: currentModel.document(path) },
                completion={ doc,pos -> currentModel.app.languages.complete(doc,pos) },
                blocks={ ranges -> if(!registry.closed && !editor.isReleased) editor.setFoldRanges(ranges.map { it.first }.toIntArray(),ranges.map { it.second }.toIntArray()) }))
            editor.subscribeEvent(ContentChangeEvent::class.java) { event,_ ->
                if(!registry.updating && !preview && event.action!=ContentChangeEvent.ACTION_SET_NEW_TEXT && currentModel.state.value.project?.config?.id==project.config.id) {
                    val start=event.changeStart; val end=event.changeEnd
                    val delta=(end.line-start.line)*(if(event.action==ContentChangeEvent.ACTION_DELETE) -1 else 1)
                    val text=editor.text.toString()
                    created.text=text
                    currentModel.edit(path,text,start.line,delta,end.line)
                }
            }
            editor.subscribeEvent(SelectionChangeEvent::class.java) { _,_ -> if(!registry.updating && !preview && currentModel.state.value.activePath==path) currentModel.savePosition(editor.cursor.leftLine,editor.cursor.leftColumn) }
            editor.breakpointClick=MobileCodeEditor.BreakpointClick { line -> if(!preview) currentModel.toggleBreakpoint(path,line) }
            registry.updating=true; editor.setText(document.text); registry.updating=false
            created
        }
        val editor=entry.editor
        if(editor.parent!==container) { (editor.parent as? ViewGroup)?.removeView(editor); container.removeAllViews(); container.addView(editor,FrameLayout.LayoutParams(-1,-1)) }
        if(entry.text!==document.text && entry.text!=document.text) {
            registry.updating=true; editor.setText(document.text); registry.updating=false
        }
        entry.text=document.text
        if(entry.dark!=settings.dark) { entry.dark=settings.dark; editor.colorScheme=CodeColors(settings.dark) }
        if(entry.fontSize!=settings.fontSize) { entry.fontSize=settings.fontSize; editor.setTextSize(settings.fontSize) }
        if(entry.wordWrap!=settings.wordWrap) { entry.wordWrap=settings.wordWrap; editor.isWordwrap=settings.wordWrap }
        editor.isEditable=!preview && !session.sourceLocked
        val points=if(session.sourceLocked) session.breakpointResults[path] ?: state.breakpoints.filter { it.path==path } else state.breakpoints.filter { it.path==path }
        editor.setBreakpointStates(points.map { it.line }.toIntArray(),IntArray(points.size) { if(points[it].verified) 1 else if(points[it].message.isNotBlank()) -1 else 0 })
        val frame=session.frames.find { it.id==session.frameId } ?: session.frames.firstOrNull()
        editor.setExecutionLine(if(session.phase==SessionPhase.PAUSED && frame?.path?.endsWith("/$path")==true) frame.line else -1)
        if(entry.diagnostics!=diagnostics || entry.diagnosticVersion!=document.version) {
            entry.diagnostics=diagnostics; entry.diagnosticVersion=document.version
            val regions=diagnostics.filter { it.path.endsWith("/$path") && (it.version==null || it.version==document.version) }.map { diagnostic ->
                val start=Positions.index(document.text,diagnostic.range.start); val end=Positions.index(document.text,diagnostic.range.end).coerceAtLeast(start+1).coerceAtMost(document.text.length)
                DiagnosticRegion(start,end,if(diagnostic.severity==1) DiagnosticRegion.SEVERITY_ERROR else DiagnosticRegion.SEVERITY_WARNING)
            }
            editor.diagnostics=DiagnosticsContainer().apply { addDiagnostics(regions) }
        }
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
