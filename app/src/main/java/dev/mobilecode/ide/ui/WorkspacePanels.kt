package dev.mobilecode.ide.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobilecode.ide.*
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import dev.mobilecode.ide.runtime.ToolchainStatus
import io.github.rosemoe.sora.widget.MobileCodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

internal fun bytesLabel(bytes: Long)="%.1f MiB".format(java.util.Locale.ROOT,bytes/1048576.0)
internal fun timeLabel(time: Long)=if(time>0) DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(time)) else "尚未打开"

@Composable
internal fun PanelAction(label: String,onClick: ()->Unit,enabled: Boolean=true) {
    TextButton(onClick=onClick,enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) { Text(label) }
}

@Composable
internal fun EntryDialog(title: String,label: String,initial: String="",validate: (String)->String?={ if(it.isBlank()) "请输入内容" else null },onDismiss: ()->Unit,onConfirm: (String)->Unit) {
    var text by remember { mutableStateOf(initial) }
    val error=validate(text)
    AlertDialog(onDismissRequest=onDismiss,title={ Text(title) },text={ OutlinedTextField(text,{ text=it },label={ Text(label) },singleLine=true,isError=error!=null,
        supportingText={ error?.let { Text(it) } },modifier=Modifier.testTag("entry-value")) },
        confirmButton={ TextButton(onClick={ onConfirm(text) },enabled=error==null) { Text("确定") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}

@Composable
internal fun ConfirmDialog(title: String,message: String,onDismiss: ()->Unit,onConfirm: ()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={ Text(title) },text={ Text(message) },
        confirmButton={ TextButton(onClick=onConfirm) { Text("确认") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}

@Composable
internal fun ReadinessBanner(model: IdeViewModel,tools: ToolchainStatus) {
    var details by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal=12.dp,vertical=4.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(if(tools.error!=null) "本地组件未就绪" else tools.message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ details=!details }) { Text(if(details) "收起" else "查看") }
        }
        if(tools.error==null) LinearProgressIndicator(progress={ tools.progress },modifier=Modifier.fillMaxWidth())
        if(details) {
            Text(tools.error ?: "组件全部来自安装包。准备期间可继续编辑，就绪后才能运行和调试。",style=MaterialTheme.typography.bodySmall)
            if(tools.error!=null) PanelAction("重新检查",onClick={ model.retryTools() })
        }
    }
}

@Composable
internal fun ProjectHomeV2(model: IdeViewModel,state: IdeState,tools: ToolchainStatus,onCreate: ()->Unit,onImport: ()->Unit,onExportSource: ()->Unit,onStorage: ()->Unit,modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<CodeLanguage?>(null) }
    var selected by remember { mutableStateOf<Project?>(null) }
    var operation by remember { mutableStateOf("") }
    val problems by model.app.workspace.problems.collectAsStateWithLifecycle()
    val importing by model.app.workspace.importProgress.collectAsStateWithLifecycle()
    val visible=state.projects.filter { (filter==null || it.config.language==filter) && it.config.name.contains(query,ignoreCase=true) }
        .sortedWith(compareByDescending<Project> { state.memories[it.config.id]?.pinned==true }.thenByDescending { state.memories[it.config.id]?.openedAt ?: 0L }.thenByDescending { it.updatedAt })
    Column(modifier.fillMaxSize().padding(horizontal=16.dp)) {
        if(state.projects.isEmpty() && problems.isEmpty()) {
            Text("从一个能运行的小程序开始",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(top=16.dp,bottom=8.dp))
            Text("四种语言都在本机运行，断网也能编程。",style=MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onCreate,modifier=Modifier.heightIn(min=48.dp).testTag("new-project")) { Text("新建项目") }
            OutlinedButton(onClick=onImport,enabled=!importing.running,modifier=Modifier.heightIn(min=48.dp)) { Text("导入 ZIP") }
            TextButton(onClick=onStorage) { Text("存储与历史") }
        }
        if(tools.toolchain==null) ReadinessBanner(model,tools)
        if(importing.running) Row(verticalAlignment=Alignment.CenterVertically) {
            Text("${importing.message} · ${bytesLabel(importing.bytes)}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            PanelAction("取消导入",onClick=model::cancelImport)
        }
        OutlinedTextField(query,{ query=it },placeholder={ Text("搜索项目名称") },singleLine=true,modifier=Modifier.fillMaxWidth().testTag("project-search"),trailingIcon={ if(query.isNotEmpty()) TextButton(onClick={ query="" }) { Text("清除") } })
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=filter==null,onClick={ filter=null },label={ Text("全部") })
            CodeLanguage.entries.forEach { language -> FilterChip(selected=filter==language,onClick={ filter=language },label={ Text(language.title) }) }
        }
        LazyColumn(Modifier.weight(1f)) {
            val recent=state.projects.maxByOrNull { state.memories[it.config.id]?.openedAt ?: 0L }
            if(query.isBlank() && filter==null && recent!=null && (state.memories[recent.config.id]?.openedAt ?: 0L)>0) item {
                TextButton(onClick={ model.openProject(recent) },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("继续：${recent.config.name}",maxLines=1,overflow=TextOverflow.Ellipsis) }
            }
            items(visible,key={ it.config.id }) { project ->
                val memory=state.memories[project.config.id]
                ListItem(headlineContent={ Text((if(memory?.pinned==true) "置顶 · " else "")+project.config.name,maxLines=2) },
                    supportingContent={ Text("${project.config.language.title} · 最近打开 ${timeLabel(memory?.openedAt ?: 0)}",fontSize=12.sp) },
                    leadingContent={ Icon(Icons.Outlined.Code,null) },trailingContent={ IconButton(onClick={ selected=project; operation="menu" }) { Icon(Icons.Outlined.MoreVert,"管理 ${project.config.name}") } },
                    modifier=Modifier.testTag("project-${project.config.id}").clickable { model.openProject(project) })
                HorizontalDivider()
            }
            if(visible.isEmpty()) item { Column(Modifier.padding(vertical=16.dp)) {
                Text(if(state.projects.isEmpty()) "创建项目后，修改会自动保存。" else "没有匹配的项目")
                if(query.isNotBlank() || filter!=null) PanelAction("清除筛选",onClick={ query=""; filter=null })
            } }
            items(problems,key={ "problem:${it.directory.name}" }) { problem ->
                Column(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
                    Text("项目暂无法读取",color=MaterialTheme.colorScheme.error)
                    Text("${problem.directory.name.take(12)} · ${problem.reason}",style=MaterialTheme.typography.bodySmall)
                    Text("原目录仍在，重新检查不会覆盖配置。",style=MaterialTheme.typography.bodySmall)
                    Row { PanelAction("重新检查",onClick={ model.refreshStorage(); model.viewRefresh() }); PanelAction("导出原目录",onClick={ model.prepareRecoveryExport(problem); onExportSource() }) }
                }
            }
        }
    }
    selected?.let { project -> when(operation) {
        "menu" -> AlertDialog(onDismissRequest={ selected=null },title={ Text(project.config.name) },text={ Column {
            PanelAction(if(state.memories[project.config.id]?.pinned==true) "取消置顶" else "置顶",onClick={ model.pin(project); selected=null })
            PanelAction("重命名项目",onClick={ operation="rename" })
            PanelAction("复制项目",onClick={ operation="copy" })
            PanelAction("移入回收站",onClick={ operation="delete" })
        } },confirmButton={ TextButton(onClick={ selected=null }) { Text("关闭") } })
        "rename","copy" -> EntryDialog(if(operation=="copy") "复制项目" else "重命名项目","项目名称",if(operation=="copy") "${project.config.name} 副本" else project.config.name,
            validate={ if(it.isBlank() || it.trim().length>64) "请输入1～64个字符" else null },onDismiss={ selected=null }) { name ->
            if(operation=="copy") model.copyProject(project,name) else model.renameProject(project,name); selected=null
        }
        "delete" -> ConfirmDialog("移入回收站？","项目及源码会移入应用内回收站，可在“存储与历史”中恢复，不会自动永久删除。",onDismiss={ selected=null }) { model.deleteProject(project); selected=null }
    } }
}

@Composable
internal fun FileBrowserV2(model: IdeViewModel,state: IdeState,onFile: (String)->Unit,onNew: ()->Unit,onHome: ()->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf("") }
    val directories=remember(state.files) { state.files.flatMap { path -> val parts=path.split('/'); (1 until parts.size).map { parts.take(it).joinToString("/") } }.toSet() }
    val entries=remember(state.files,directories) { (state.files+directories).distinct().sorted() }
    LaunchedEffect(state.activePath) { collapsed=collapsed.filterNot { state.activePath.startsWith("$it/") }.toSet() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("项目文件",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            IconButton(onClick=onNew) { Icon(Icons.Outlined.Add,"新建文件") }
        }
        OutlinedTextField(query,{ query=it },singleLine=true,placeholder={ Text("查找文件") },modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp))
        Text(state.activePath.ifBlank { state.project?.config?.name.orEmpty() },Modifier.padding(12.dp),fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        val visible=if(query.isNotBlank()) state.files.filter { it.contains(query,ignoreCase=true) } else entries.filter { entry -> collapsed.none { entry.startsWith("$it/") } }
        LazyColumn(Modifier.weight(1f)) {
            items(visible,key={ it }) { path ->
                val directory=path in directories
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(if(path==state.activePath) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface)
                    .clickable { if(directory) collapsed=if(path in collapsed) collapsed-path else collapsed+path else onFile(path) }
                    .padding(start=if(query.isBlank()) (12+path.count { it=='/' }*12).dp else 12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(directory) { if(path in collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore } else Icons.Outlined.Description,null,Modifier.size(20.dp))
                    Text(if(query.isBlank()) path.substringAfterLast('/') else path,Modifier.weight(1f).padding(start=8.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(!directory) IconButton(onClick={ selected=path; operation="menu" }) { Icon(Icons.Outlined.MoreVert,"管理文件 $path",Modifier.size(20.dp)) }
                }
            }
        }
        PanelAction("项目首页",onClick=onHome)
    }
    selected?.let { path -> when(operation) {
        "menu" -> AlertDialog(onDismissRequest={ selected=null },title={ Text(path) },text={ Column {
            Text("移动文件不会自动重写 import、include 或 Java 类名。",style=MaterialTheme.typography.bodySmall)
            PanelAction("重命名 / 移动",onClick={ operation="move" })
            PanelAction("移入回收站",onClick={ operation="delete" })
        } },confirmButton={ TextButton(onClick={ selected=null }) { Text("关闭") } })
        "move" -> EntryDialog("重命名 / 移动文件","项目内相对路径",path,validate={ value -> runCatching {
            val target=resolveInside(state.project!!.directory,value.trim()); require(!target.exists()) { "目标已存在" }; null
        }.getOrElse { it.message ?: "路径无效" } },onDismiss={ selected=null }) { model.moveFile(path,it); selected=null }
        "delete" -> ConfirmDialog("移入回收站？","$path 可在回收站恢复，活动任务期间不能删除。",onDismiss={ selected=null }) { model.deleteFile(path); selected=null }
    } }
}

@Composable
internal fun WorkspaceDialog(title: String,onDismiss: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),color=MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) { Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); TextButton(onClick=onDismiss) { Text("关闭") } }
                content()
            }
        }
    }
}

@Composable
internal fun QuickOpenDialog(state: IdeState,onDismiss: ()->Unit,onOpen: (String)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    WorkspaceDialog("快速打开文件",onDismiss) {
        OutlinedTextField(query,{ query=it },singleLine=true,label={ Text("文件名或路径") },modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("quick-open-query"))
        LazyColumn(Modifier.weight(1f)) { items(state.files.filter { it.contains(query,ignoreCase=true) },key={ it }) { path -> ListItem(headlineContent={ Text(path,fontFamily=FontFamily.Monospace,fontSize=14.sp) },modifier=Modifier.clickable { onOpen(path); onDismiss() }) } }
    }
}

@Composable
internal fun ProjectSearchDialog(model: IdeViewModel,state: IdeState,onDismiss: ()->Unit) {
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var sourceOnly by rememberSaveable { mutableStateOf(false) }
    var suffix by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query,matchCase,sourceOnly,suffix) { model.search(query,matchCase,sourceOnly,suffix) }
    DisposableEffect(Unit) { onDispose { model.cancelSearch() } }
    WorkspaceDialog("项目文本搜索",onDismiss) {
        OutlinedTextField(query,{ query=it },singleLine=true,label={ Text("搜索源码和资源，最多显示1000处") },modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp))
        Row(Modifier.padding(horizontal=16.dp).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) { Checkbox(matchCase,{ matchCase=it }); Text("区分大小写"); Checkbox(sourceOnly,{ sourceOnly=it }); Text("仅源码根"); Text(if(state.searching) "搜索中…" else "${state.searchResults.size} 处",fontSize=12.sp) }
        OutlinedTextField(suffix,{ suffix=it.trim() },singleLine=true,label={ Text("文件后缀，可选（例如 .py、.cpp）") },modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp))
        LazyColumn(Modifier.weight(1f)) { items(state.searchResults) { hit -> ListItem(headlineContent={ Text(hit.text,maxLines=2,overflow=TextOverflow.Ellipsis,fontFamily=FontFamily.Monospace,fontSize=13.sp) },
            supportingContent={ Text("${hit.path}:${hit.line+1}") },modifier=Modifier.clickable { model.openFile(hit.path,Position(hit.line,hit.column)); onDismiss() }) } }
    }
}

@Composable
internal fun StorageDialog(model: IdeViewModel,state: IdeState,onExport: ()->Unit,onDismiss: ()->Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cleanup by remember { mutableStateOf<StorageItem?>(null) }
    var expandedHistory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { model.refreshStorage() }
    WorkspaceDialog("存储与运行历史",onDismiss) {
        ScrollableTabRow(selectedTabIndex=tab,edgePadding=0.dp) { listOf("存储","运行历史","回收站").forEachIndexed { index,title -> Tab(selected=tab==index,onClick={ tab=index },text={ Text(title) }) } }
        Text("可用 ${bytesLabel(model.app.filesDir.usableSpace)} · 项目、恢复草稿与待安装更新不会作为缓存删除。",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
        when(tab) {
            0 -> LazyColumn(Modifier.weight(1f)) { items(state.storage,key={ it.id }) { item -> ListItem(headlineContent={ Text("${item.label} · ${bytesLabel(item.bytes)}") },supportingContent={ Text(item.detail,fontSize=12.sp) },
                trailingContent={ if(item.cleanable) TextButton(onClick={ cleanup=item },enabled=item.bytes>0) { Text("清理") } }) } }
            1 -> LazyColumn(Modifier.weight(1f)) { items(state.history,key={ it.id }) { entry -> Column {
                ListItem(headlineContent={ Text("${state.projects.firstOrNull { it.config.id==entry.projectId }?.config?.name ?: "历史项目"} · ${entry.status}") },
                    supportingContent={ Text("${timeLabel(entry.startedAt)} · ${entry.durationMs/1000.0}s · 退出 ${entry.exitCode ?: "—"}\n${if(entry.files.isEmpty()) "产物已清理或无可用文件" else "${entry.files.size}个日志/生成文件"}",fontSize=12.sp) },
                    modifier=Modifier.clickable { expandedHistory=if(expandedHistory==entry.id) null else entry.id })
                if(expandedHistory==entry.id) entry.files.forEach { file -> Row(Modifier.padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(file.relativeTo(File(model.app.workspace.sessionsRoot,entry.id)).invariantSeparatorsPath,Modifier.weight(1f),fontSize=12.sp,maxLines=2)
                    TextButton(onClick={ model.prepareFileExport(file); onExport() }) { Text("导出") }
                } }
            } } }
            2 -> LazyColumn(Modifier.weight(1f)) { items(state.recycled,key={ it.id }) { entry -> ListItem(headlineContent={ Text(entry.name) },supportingContent={ Text("${if(entry.project) "项目" else entry.path} · ${timeLabel(entry.removedAt)}",fontSize=12.sp) },trailingContent={ TextButton(onClick={ model.restore(entry) }) { Text("恢复") } }) } }
        }
        PanelAction("刷新容量与历史",onClick={ model.refreshStorage() })
    }
    cleanup?.let { item -> ConfirmDialog("清理${item.label}？","预计释放 ${bytesLabel(item.bytes)}。${item.detail}。历史产物删除后无法恢复，请先导出需要的文件。",onDismiss={ cleanup=null }) { model.cleanStorage(item.id); cleanup=null } }
}

@Composable
internal fun NavigationDialog(model: IdeViewModel,state: IdeState,title: String,onDismiss: ()->Unit) {
    WorkspaceDialog(title,onDismiss) {
        if(state.navigationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(state.locations) { location -> ListItem(headlineContent={ Text(location.path.substringAfterLast('/')) },supportingContent={ Text("${location.path} : ${location.position.line+1}",fontSize=12.sp) },modifier=Modifier.clickable { model.openLocation(location); onDismiss() }) }
            items(state.symbols) { symbol -> ListItem(headlineContent={ Text("  ".repeat(symbol.depth)+symbol.name,fontFamily=FontFamily.Monospace) },supportingContent={ Text("第 ${symbol.location.position.line+1} 行",fontSize=12.sp) },modifier=Modifier.clickable { model.openLocation(symbol.location); onDismiss() }) }
            if(!state.navigationBusy && state.locations.isEmpty() && state.symbols.isEmpty()) item { Text("暂无结果。可从工作台状态区重新启动语义服务。",Modifier.padding(16.dp)) }
        }
    }
}

@Composable
internal fun RecoveryDialog(model: IdeViewModel,draft: RecoveryDraft,onDismiss: ()->Unit) {
    val project=model.state.value.project ?: return
    var original by remember { mutableStateOf("") }
    LaunchedEffect(draft) { original=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { resolveInside(project.directory,draft.path).readText() }.getOrDefault("（原文件暂无法读取）") } }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("发现未保存草稿") },text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(draft.path)
        Text(if(sha256(original.toByteArray())!=draft.originalHash) "原文件自草稿生成后可能已改变，请对照后选择。" else "恢复只在你确认后替换编辑器内容。",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        Text("磁盘内容（预览）",Modifier.padding(top=12.dp)); SelectionContainer { Text(original.take(1500),fontFamily=FontFamily.Monospace,fontSize=12.sp) }
        Text("恢复草稿（预览）",Modifier.padding(top=12.dp)); SelectionContainer { Text(draft.text.take(1500),fontFamily=FontFamily.Monospace,fontSize=12.sp) }
        TextButton(onClick={ model.previewRecovery(draft); onDismiss() }) { Text("查看完整草稿（只读，尚未应用）") }
        TextButton(onClick={ model.discardRecovery(draft); onDismiss() }) { Text("明确放弃此草稿") }
    } },confirmButton={ TextButton(onClick={ model.recover(draft); onDismiss() }) { Text("恢复到编辑器") } },dismissButton={ TextButton(onClick=onDismiss) { Text("稍后处理") } })
}

@Composable
internal fun InlineSearchBar(editor: MobileCodeEditor,readOnly: Boolean,onDismiss: ()->Unit) {
    var query by rememberSaveable { mutableStateOf("") }; var replacement by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf(false) }; var matchCase by rememberSaveable { mutableStateOf(true) }
    var whole by rememberSaveable { mutableStateOf(false) }; var regex by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var count by remember { mutableIntStateOf(0) }; var current by remember { mutableIntStateOf(0) }
    DisposableEffect(editor) { onDispose { if(!editor.isReleased) editor.searcher.stopSearch() } }
    LaunchedEffect(editor,query,matchCase,whole,regex) {
        error=null
        try { if(query.isBlank()) editor.searcher.stopSearch() else {
            if(regex) java.util.regex.Pattern.compile(query)
            editor.searcher.search(query,EditorSearcher.SearchOptions(if(regex) EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION else if(whole) EditorSearcher.SearchOptions.TYPE_WHOLE_WORD else EditorSearcher.SearchOptions.TYPE_NORMAL,!matchCase))
        } } catch(problem: Exception) { error=problem.message; editor.searcher.stopSearch() }
    }
    LaunchedEffect(editor) { while(!editor.isReleased) { count=runCatching { editor.searcher.matchedPositionCount }.getOrDefault(0); current=runCatching { editor.searcher.currentMatchedPositionIndex+1 }.getOrDefault(0); delay(150) } }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal=8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(query,{ query=it },placeholder={ Text("查找") },singleLine=true,isError=error!=null,modifier=Modifier.weight(1f).testTag("inline-find"))
            Text("$current/$count",fontSize=12.sp,modifier=Modifier.padding(horizontal=4.dp))
            IconButton(onClick={ editor.searcher.gotoPrevious() },enabled=count>0) { Icon(Icons.Outlined.KeyboardArrowUp,"上一个匹配") }
            IconButton(onClick={ editor.searcher.gotoNext() },enabled=count>0) { Icon(Icons.Outlined.KeyboardArrowDown,"下一个匹配") }
            IconButton(onClick=onDismiss) { Icon(Icons.Outlined.Close,"关闭查找") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=matchCase,onClick={ matchCase=!matchCase },label={ Text("大小写") })
            FilterChip(selected=whole,onClick={ whole=!whole; regex=false },label={ Text("整词") })
            FilterChip(selected=regex,onClick={ regex=!regex; whole=false },label={ Text("正则") })
            FilterChip(selected=replace,onClick={ replace=!replace },label={ Text("替换") })
        }
        error?.let { Text("表达式无效：$it",color=MaterialTheme.colorScheme.error,fontSize=12.sp,maxLines=2) }
        if(replace) Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(replacement,{ replacement=it },singleLine=true,label={ Text("替换为") },enabled=!readOnly,modifier=Modifier.weight(1f))
            TextButton(onClick={ editor.searcher.replaceCurrentMatch(replacement) },enabled=!readOnly && count>0 && error==null) { Text("替换") }
            TextButton(onClick={ editor.searcher.replaceAll(replacement) },enabled=!readOnly && count>0 && error==null) { Text("全部") }
        }
    }
}
