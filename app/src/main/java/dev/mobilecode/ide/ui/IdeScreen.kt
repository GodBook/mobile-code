package dev.mobilecode.ide.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobilecode.ide.*
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.MobileCodeEditor
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen(model: IdeViewModel,onImport: ()->Unit,onExport: ()->Unit,onExportSource: ()->Unit,onRun: (Boolean)->Unit,onCopy: (String)->Unit,onInstallUpdate: ()->Unit) {
    val state=model.state.collectAsStateWithLifecycle().value
    val settings by remember(model) { model.settings.map { it.copy(lastProject="",lastFile="",cursorLine=0,cursorColumn=0) }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue=EditorSettings())
    val session by model.session.collectAsStateWithLifecycle()
    val tools by model.toolchains.collectAsStateWithLifecycle()
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val languageStatus by model.languageStatus.collectAsStateWithLifecycle()
    val update by model.app.updates.state.collectAsStateWithLifecycle()
    val snackbar=remember { SnackbarHostState() }
    var create by rememberSaveable { mutableStateOf(false) }
    var settingDialog by rememberSaveable { mutableStateOf(false) }
    var updateDialog by rememberSaveable { mutableStateOf(false) }
    var fileDialog by rememberSaveable { mutableStateOf(false) }
    var runDialog by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var quickOpen by rememberSaveable { mutableStateOf(false) }
    var projectSearch by rememberSaveable { mutableStateOf(false) }
    var goLine by rememberSaveable { mutableStateOf(false) }
    var storageDialog by rememberSaveable { mutableStateOf(false) }
    var navigationDialog by rememberSaveable { mutableStateOf("") }
    var focusEditor by rememberSaveable { mutableStateOf(false) }
    var recovery by remember { mutableStateOf<RecoveryDraft?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<MobileCodeEditor?>(null) }
    val drawer=rememberDrawerState(DrawerValue.Closed); val scope=rememberCoroutineScope()
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it,actionLabel="关闭",duration=SnackbarDuration.Long); model.clearError() } }
    BackHandler(enabled=state.project!=null) { if(search) search=false else if(focusEditor) focusEditor=false else if(state.preview!=null) model.closePreview() else model.home() }
    LaunchedEffect(state.commandSerial) { when(state.command) { "quick-open"->quickOpen=true; "find"->search=true; "project-search"->projectSearch=true; "go-line"->goLine=true } }
    LaunchedEffect(state.navigationBusy,state.locations,navigationDialog) { if(navigationDialog=="定义" && !state.navigationBusy && state.locations.size==1) navigationDialog="" }
    fun navigateCode(method: String,title: String) { editor?.let { navigationDialog=title; model.codeNavigation(method,Position(it.cursor.leftLine,it.cursor.leftColumn)) } }

    val browser: @Composable ()->Unit = { FileBrowserV2(model,state,onFile={ model.openFile(it); scope.launch { drawer.close() } },onNew={ fileDialog=true },onHome={ model.home(); scope.launch { drawer.close() } }) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=840.dp
        val content: @Composable ()->Unit = {
            Scaffold(snackbarHost={ SnackbarHost(snackbar) },topBar={
                TopAppBar(title={ Column { Text(state.project?.config?.name ?: "Mobile Code",maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(state.project?.config?.language?.title ?: "手机上的离线编程工作台",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } },
                    navigationIcon={ if(state.project!=null) IconAction(Icons.Outlined.Menu,"项目文件") { scope.launch { drawer.open() } } },
                    actions={
                        if(update.hasUpdate) IconAction(Icons.Outlined.SystemUpdate,"发现软件更新") { updateDialog=true }
                        if(state.project!=null) {
                            if(session.active) IconAction(Icons.Outlined.Stop,"停止",onClick={ model.stop() })
                            else {
                                IconAction(Icons.Outlined.PlayArrow,"运行",enabled=tools.toolchain!=null && !state.busy) { onRun(false) }
                                IconAction(Icons.Outlined.BugReport,"调试",enabled=tools.toolchain!=null && !state.busy) { onRun(true) }
                            }
                            IconAction(Icons.Outlined.MoreVert,"更多") { menu=true }
                            DropdownMenu(expanded=menu,onDismissRequest={ menu=false }) {
                                DropdownMenuItem(text={ Text("保存所有文件") },onClick={ menu=false; model.flush() })
                                DropdownMenuItem(text={ Text("查找与替换") },onClick={ menu=false; search=true },enabled=state.document!=null)
                                DropdownMenuItem(text={ Text("快速打开文件") },onClick={ menu=false; quickOpen=true })
                                DropdownMenuItem(text={ Text("项目文本搜索") },onClick={ menu=false; projectSearch=true })
                                DropdownMenuItem(text={ Text("转到行") },onClick={ menu=false; goLine=true })
                                DropdownMenuItem(text={ Text("转到定义") },onClick={ menu=false; navigateCode("textDocument/definition","定义") },enabled=state.preview==null)
                                DropdownMenuItem(text={ Text("查找引用") },onClick={ menu=false; navigateCode("textDocument/references","引用") },enabled=state.preview==null)
                                DropdownMenuItem(text={ Text("文档大纲") },onClick={ menu=false; navigateCode("textDocument/documentSymbol","大纲") },enabled=state.preview==null)
                                DropdownMenuItem(text={ Text("导航返回") },onClick={ menu=false; model.navigateBack() })
                                DropdownMenuItem(text={ Text("导航前进") },onClick={ menu=false; model.navigateBack(true) })
                                DropdownMenuItem(text={ Text("新建文件") },onClick={ menu=false; fileDialog=true },enabled=!session.sourceLocked)
                                DropdownMenuItem(text={ Text("运行配置与测试输入") },onClick={ menu=false; runDialog=true },enabled=!session.active)
                                DropdownMenuItem(text={ Text("导出项目 ZIP") },onClick={ menu=false; onExport() })
                                DropdownMenuItem(text={ Text("导出当前源码") },onClick={ menu=false; onExportSource() },enabled=state.document!=null && state.preview==null)
                                DropdownMenuItem(text={ Text("导出未保存内容") },onClick={ menu=false; model.prepareBufferExport(); onExportSource() },enabled=state.document!=null)
                                DropdownMenuItem(text={ Text("存储、历史与回收站") },onClick={ menu=false; storageDialog=true })
                                DropdownMenuItem(text={ Text(if(focusEditor) "退出专注编辑" else "专注编辑") },onClick={ menu=false; focusEditor=!focusEditor })
                                DropdownMenuItem(text={ Text("关闭其他标签") },onClick={ menu=false; model.closeOthers() })
                                DropdownMenuItem(text={ Text("关闭已保存标签") },onClick={ menu=false; model.closeSaved() })
                                DropdownMenuItem(text={ Text("重新打开关闭的标签") },onClick={ menu=false; model.reopenClosed() })
                                DropdownMenuItem(text={ Text("快捷键与操作说明") },onClick={ menu=false; help=true })
                                DropdownMenuItem(text={ Text("展开全部代码") },onClick={ menu=false; editor?.unfoldAll() })
                                DropdownMenuItem(text={ Text("设置") },onClick={ menu=false; settingDialog=true })
                                DropdownMenuItem(text={ Text("项目首页") },onClick={ menu=false; model.home() })
                            }
                        } else IconAction(Icons.Outlined.Settings,"设置") { settingDialog=true }
                    })
            }) { padding ->
                if(state.project==null) ProjectHomeV2(model,state,tools,onCreate={ create=true },onImport=onImport,onExportSource=onExportSource,onStorage={ storageDialog=true },
                    modifier=Modifier.padding(padding))
                else Row(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if(wide) { Box(Modifier.width(240.dp).fillMaxHeight()) { browser() }; VerticalDivider() }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val workbenchHeight=maxHeight
                    Column(Modifier.fillMaxSize()) {
                        if(tools.toolchain==null && workbenchHeight>=300.dp) ReadinessBanner(model,tools)
                        if(state.recoveries.isNotEmpty() && workbenchHeight>=300.dp) TextButton(onClick={ recovery=state.recoveries.first() }) { Text("有 ${state.recoveries.size} 份恢复草稿，点击对照") }
                        if(state.preview!=null) Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer),verticalAlignment=Alignment.CenterVertically) {
                            Text(state.preview!!.title,Modifier.weight(1f).padding(start=16.dp),style=MaterialTheme.typography.labelMedium)
                            IconAction(Icons.Outlined.Close,"关闭快照") { model.closePreview() }
                        } else if(state.documents.isNotEmpty() && workbenchHeight>=200.dp) ScrollableTabRow(selectedTabIndex=state.documents.keys.indexOf(state.activePath).coerceAtLeast(0),edgePadding=0.dp,
                            indicator={ positions -> positions.getOrNull(state.documents.keys.indexOf(state.activePath))?.let { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it)) } }) {
                            state.documents.values.forEach { doc -> Tab(selected=doc.path==state.activePath,onClick={ model.openFile(doc.path) },text={ Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(doc.path.substringAfterLast('/')+if(doc.dirty) " •" else "",fontFamily=FontFamily.Monospace,fontSize=12.sp)
                                IconButton(onClick={ model.closeFile(doc.path) },modifier=Modifier.size(48.dp)) { Icon(Icons.Outlined.Close,"关闭 ${doc.path}",Modifier.size(20.dp)) }
                            } }) }
                        }
                        if(search && editor!=null) InlineSearchBar(editor!!,session.sourceLocked || state.preview!=null,onDismiss={ search=false })
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if(state.document!=null) EditorHost(model,state,settings,session,diagnostics,Modifier.fillMaxSize()) { editor=it }
                            else Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text("选择文件开始编写",color=MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick={ scope.launch { drawer.open() } }) { Text("打开项目文件") }
                            }
                        }
                        if(state.signature.isNotBlank() && workbenchHeight>=300.dp) Text(state.signature,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal=12.dp,vertical=6.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        val failed=state.saveStates.entries.firstOrNull { it.value.phase=="failed" }
                        if(failed!=null) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("${failed.key.substringAfterLast('/')} 保存失败：${failed.value.message}",Modifier.weight(1f),fontSize=12.sp,maxLines=2)
                            TextButton(onClick={ model.retrySave(failed.key) }) { Text("重试") }
                            TextButton(onClick={ model.prepareBufferExport(failed.key); onExportSource() }) { Text("导出草稿") }
                        }
                        if(workbenchHeight>=240.dp && !focusEditor) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            val save=state.saveStates[state.activePath]
                            val savedTime=remember(save?.savedAt) { save?.savedAt?.takeIf { it>0 }?.let { java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(it)) }.orEmpty() }
                            Text(if(session.sourceLocked || state.preview!=null) "只读" else when(save?.phase) { "failed"->"保存失败"; "saving"->"保存中"; "pending"->"待保存"; else->"已保存 $savedTime".trim() },Modifier.clickable { if(save?.phase=="failed") model.retrySave(state.activePath) },fontSize=12.sp,color=if(save?.phase=="failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp)); Text(if(tools.toolchain==null) tools.message else languageStatus,Modifier.weight(1f).clickable { model.retryLanguage() },fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${(editor?.cursor?.leftLine ?: 0)+1}:${(editor?.cursor?.leftColumn ?: 0)+1}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(workbenchHeight>=300.dp && !focusEditor) KeyboardBar(editor,enabled=!session.sourceLocked && state.preview==null,language=state.project!!.config.language)
                        if(!focusEditor || session.active) OutputPanel(model,state,session,diagnostics,onCopy,onExportSource,settings.consoleFontSize,workbenchHeight)
                    }
                    }
                }
            }
        }
        if(state.project!=null && !wide) ModalNavigationDrawer(drawerState=drawer,drawerContent={ ModalDrawerSheet(Modifier.width(290.dp)) { browser() } },content=content) else content()
    }
    if(create) CreateProjectDialog(onDismiss={ create=false },onCreate={ name,language,multi -> create=false; model.createProject(name,language,multi) })
    if(fileDialog) EntryDialog("新建文件","项目内相对路径",initial="src/utils.${state.project?.config?.language?.extension ?: "txt"}",validate={ value -> runCatching { val target=resolveInside(state.project!!.directory,value.trim()); require(!target.exists()) { "同名文件已存在" }; null }.getOrElse { it.message ?: "路径无效" } },onDismiss={ fileDialog=false }) { value -> fileDialog=false; model.createFile(value.trim()) }
    if(settingDialog) SettingsDialog(model,settings,onDismiss={ settingDialog=false },onUpdates={ updateDialog=true })
    if(updateDialog) UpdateDialog(model.app.updates,installationAllowed=!session.active,onInstall=onInstallUpdate,onDismiss={ updateDialog=false })
    if(runDialog && state.project!=null) RunConfiguration(state.project!!.config,state.files,onDismiss={ runDialog=false }) { entry,args,timeout,preload -> runDialog=false; model.configureRun(entry,args,timeout,preload) }
    if(quickOpen) QuickOpenDialog(state,onDismiss={ quickOpen=false },onOpen={ model.openFile(it) })
    if(projectSearch) ProjectSearchDialog(model,state,onDismiss={ projectSearch=false })
    if(goLine) EntryDialog("转到行","行号（从1开始）",validate={ if(it.toIntOrNull()?.let { n -> n>0 }==true) null else "请输入正整数" },onDismiss={ goLine=false }) { model.goToLine(it.toInt()); goLine=false }
    if(storageDialog) StorageDialog(model,state,onExport=onExportSource,onDismiss={ storageDialog=false })
    if(navigationDialog.isNotEmpty()) NavigationDialog(model,state,navigationDialog,onDismiss={ navigationDialog="" })
    recovery?.let { RecoveryDialog(model,it,onDismiss={ recovery=null }) }
    state.importChoice?.let { choice -> AlertDialog(onDismissRequest=model::cancelImport,title={ Text("选择导入语言") },text={ Column { Text("检测到多种语言，请选择本项目的编译运行语言。"); choice.candidates.forEach { language -> TextButton(onClick={ model.importProject(choice.uri,language) }) { Text(language.title) } } } },confirmButton={ TextButton(onClick=model::cancelImport) { Text("取消") } }) }
    if(help) AlertDialog(onDismissRequest={ help=false },title={ Text("操作说明") },text={ Text("Ctrl+P 快速打开\nCtrl+F 文件查找\nCtrl+Shift+F 项目搜索\nCtrl+L 转到行\nCtrl+S 保存\nAlt+← / → 导航返回/前进\n\n点击行号设置断点；暂停后可单步、查看变量。调试源码只读。语义状态可点击重启。\n\n输入写入成功后才清空；结束输入（EOF）会关闭当前会话输入。") },confirmButton={ TextButton(onClick={ help=false }) { Text("关闭") } })
}
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun IconAction(icon: ImageVector,label: String,enabled: Boolean=true,onClick: ()->Unit) {
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={ PlainTooltip { Text(label) } },state=rememberTooltipState()) {
        IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).testTag(label)) { Icon(icon,label) }
    }
}

@Composable
private fun KeyboardBar(editor: MobileCodeEditor?,enabled: Boolean,language: CodeLanguage) {
    fun key(code: Int) { editor?.requestFocus(); editor?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code)); editor?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code)) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceContainer),verticalAlignment=Alignment.CenterVertically) {
        IconAction(Icons.Outlined.Undo,"撤销",enabled && editor?.canUndo()==true) { editor?.undo() }
        IconAction(Icons.Outlined.Redo,"重做",enabled && editor?.canRedo()==true) { editor?.redo() }
        TextButton(onClick={ key(KeyEvent.KEYCODE_TAB) },enabled=enabled,modifier=Modifier.height(48.dp)) { Text("Tab",fontFamily=FontFamily.Monospace) }
        TextButton(onClick={ key(KeyEvent.KEYCODE_DPAD_LEFT) },modifier=Modifier.size(48.dp)) { Text("←") }
        TextButton(onClick={ key(KeyEvent.KEYCODE_DPAD_RIGHT) },modifier=Modifier.size(48.dp)) { Text("→") }
        TextButton(onClick={ key(KeyEvent.KEYCODE_DPAD_UP) },modifier=Modifier.size(48.dp)) { Text("↑") }
        TextButton(onClick={ key(KeyEvent.KEYCODE_DPAD_DOWN) },modifier=Modifier.size(48.dp)) { Text("↓") }
        TextButton(onClick={ editor?.unindentSelection() },enabled=enabled) { Text("退格缩进") }
        (if(language==CodeLanguage.PYTHON) listOf(":","(",")","[","]","'","\"","#","=",",") else listOf("{","}","(",")","[","]",";",":","\"","/","\\")).forEach { symbol -> TextButton(onClick={ editor?.insertText(symbol,1); editor?.requestFocus() },enabled=enabled,modifier=Modifier.size(48.dp)) { Text(symbol,fontFamily=FontFamily.Monospace) } }
    }
}

@Composable
private fun OutputPanel(model: IdeViewModel,state: IdeState,session: SessionState,diagnostics: List<Diagnostic>,onCopy: (String)->Unit,onExportSource: ()->Unit,consoleFontSize: Float,maxWorkbenchHeight: Dp) {
    val compact=maxWorkbenchHeight<480.dp
    var height by rememberSaveable { mutableFloatStateOf(300f) }
    var expanded by rememberSaveable { mutableStateOf(!compact) }
    var explicitExpansion by rememberSaveable { mutableStateOf(false) }
    var restoreInputFocus by remember { mutableStateOf(false) }
    val inputFocus=remember { FocusRequester() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var inputRevision by remember { mutableLongStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var findOutput by rememberSaveable { mutableStateOf(false) }
    var outputQuery by rememberSaveable { mutableStateOf("") }
    var watchDialog by remember { mutableStateOf(false) }
    var channel by remember { mutableIntStateOf(0) }
    var channelMenu by remember { mutableStateOf(false) }
    var clearedCount by remember(session.id,channel) { mutableLongStateOf(0) }
    val density=LocalDensity.current
    val keyboard=WindowInsets.ime.getBottom(density)>0
    val panelHeight=if(!expanded) 48.dp else minOf(height.dp,(maxWorkbenchHeight-200.dp).coerceAtLeast(48.dp))
    LaunchedEffect(compact) { if(compact && !explicitExpansion && session.phase!=SessionPhase.PAUSED) expanded=false }
    LaunchedEffect(session.phase) { if(session.phase==SessionPhase.PAUSED) { expanded=true; tab=3 } else if(session.phase==SessionPhase.FAILED && diagnostics.isNotEmpty()) { expanded=true; tab=1 } }
    val body: @Composable (Dp,Boolean)->Unit = { actualHeight,showExpanded ->
    Column(Modifier.fillMaxWidth().height(actualHeight).background(MaterialTheme.colorScheme.surfaceContainer).testTag(if(showExpanded) "output-expanded" else "output-collapsed")) {
        Row(Modifier.fillMaxWidth().height(48.dp).pointerInput(density) { detectVerticalDragGestures { change,amount -> change.consume(); height=(height-amount/density.density).coerceIn(144f,460f) } },verticalAlignment=Alignment.CenterVertically) {
            Text(when(session.phase) { SessionPhase.IDLE->"控制台"; SessionPhase.BUILDING->"编译中"; SessionPhase.PAUSED->"已暂停"; SessionPhase.RUNNING->"运行中"; SessionPhase.STARTING,SessionPhase.PREPARING->"准备中"; else->session.reason },Modifier.weight(1f).padding(start=12.dp),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(!session.active && session.id!=null) Text("${session.elapsedMs/1000.0}s · ${session.exitCode?.let { "退出 $it" } ?: "—"}",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            IconAction(if(showExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,if(showExpanded) "折叠控制台" else "展开控制台") { expanded=!expanded; explicitExpansion=expanded; if(!expanded) restoreInputFocus=false }
        }
        if(showExpanded) {
            if(session.debugging && session.active) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.SpaceEvenly) {
                IconAction(Icons.Outlined.PlayArrow,"继续",session.phase==SessionPhase.PAUSED) { model.debugCommand("continue") }
                IconAction(Icons.Outlined.SkipNext,"单步跳过",session.phase==SessionPhase.PAUSED) { model.debugCommand("next") }
                IconAction(Icons.Outlined.CallMade,"单步进入",session.phase==SessionPhase.PAUSED) { model.debugCommand("stepIn") }
                IconAction(Icons.Outlined.CallReceived,"单步跳出",session.phase==SessionPhase.PAUSED) { model.debugCommand("stepOut") }
                IconAction(Icons.Outlined.Pause,"暂停",session.phase==SessionPhase.RUNNING) { model.debugCommand("pause") }
                IconAction(Icons.Outlined.Refresh,"重新调试") { model.restart() }
            }
            ScrollableTabRow(selectedTabIndex=tab,edgePadding=0.dp) { listOf("输出","问题 ${diagnostics.size}","调用栈","变量","观察","断点").forEachIndexed { index,title -> Tab(selected=tab==index,onClick={ tab=index },text={ Text(title,fontSize=12.sp) }) } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when(tab) {
                    0 -> {
                        val scroll=rememberScrollState(); val full=when(channel) { 1->session.standardOutput; 2->session.standardError; 3->session.buildOutput; else->session.output }
                        val count=session.outputCounts[listOf("all","stdout","stderr","build")[channel]] ?: full.length.toLong()
                        val text=full.takeLast(minOf(32_000L,(count-clearedCount).coerceAtLeast(0)).toInt())
                        LaunchedEffect(text,follow) { if(follow) scroll.scrollTo(scroll.maxValue) }
                        Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Box { TextButton(onClick={ channelMenu=true }) { Text(listOf("全部","标准输出","标准错误","编译")[channel],fontSize=10.sp) }; DropdownMenu(channelMenu,{ channelMenu=false }) { listOf("全部","标准输出","标准错误","编译").forEachIndexed { index,title -> DropdownMenuItem(text={ Text(title) },onClick={ channel=index; channelMenu=false }) } } }
                            TextButton(onClick={ follow=!follow }) { Text(if(follow) "暂停跟随" else "回到底部",fontSize=12.sp) }
                            TextButton(onClick={ clearedCount=count }) { Text("清空视图",fontSize=12.sp) }
                            TextButton(onClick={ findOutput=!findOutput }) { Text("查找",fontSize=12.sp) }
                            TextButton(onClick={ onCopy(full) }) { Text("复制内存输出",fontSize=12.sp) }
                            TextButton(onClick={ model.app.sessions.snapshot?.let { snapshot -> val log=java.io.File(snapshot.directory,"console.log"); if(log.isFile) { model.prepareFileExport(log); onExportSource() } } }) { Text("导出日志",fontSize=12.sp) }
                        }
                        if(findOutput) OutlinedTextField(outputQuery,{ outputQuery=it },singleLine=true,placeholder={ Text("在当前显示输出中查找") },modifier=Modifier.fillMaxWidth())
                        Text("显示最近 ${text.length} 字符 · 已保存日志上限10MiB",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=8.dp))
                        val annotated=remember(text,outputQuery,findOutput) { androidx.compose.ui.text.buildAnnotatedString {
                            append(text.ifEmpty { "程序输出会显示在这里" })
                            if(findOutput && outputQuery.isNotEmpty()) { var start=text.indexOf(outputQuery,ignoreCase=true); var hits=0
                                while(start>=0 && hits++<1000) { addStyle(androidx.compose.ui.text.SpanStyle(background=Color(0xFF8C6F20)),start,start+outputQuery.length); start=text.indexOf(outputQuery,start+outputQuery.length,ignoreCase=true) }
                            }
                        } }
                        SelectionContainer(Modifier.weight(1f)) { Text(annotated,Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp),fontFamily=FontFamily.Monospace,fontSize=consoleFontSize.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    1 -> ProblemsList(model,diagnostics,state,session) { if(compact) expanded=false }
                    2 -> LazyColumn(Modifier.fillMaxSize()) { items(session.frames,key={ it.id }) { frame -> ListItem(headlineContent={ Text(frame.name,fontFamily=FontFamily.Monospace,fontSize=12.sp,maxLines=1) },supportingContent={ Text("${frame.path?.substringAfterLast('/')} : ${frame.line+1}",fontSize=10.sp) },modifier=Modifier.clickable { model.selectFrame(frame); if(compact) expanded=false }) } }
                    3 -> if(session.variables.isEmpty()) Text("暂停后查看当前栈帧的变量",Modifier.padding(12.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) else key(session.id,session.stopSequence,session.frameId) { LazyColumn { items(session.variables) { variable -> VariableRow(model,variable,onCopy=onCopy) } } }
                    4 -> Column {
                        TextButton(onClick={ watchDialog=true }) { Icon(Icons.Outlined.Add,null,Modifier.size(18.dp)); Text("添加观察项",fontSize=11.sp) }
                        val values=if(session.phase==SessionPhase.PAUSED) state.watchValues else state.watchExpressions.map { DebugVariable(it,"暂停后刷新") }
                        key(session.id,session.stopSequence,session.frameId) { LazyColumn { items(values,key={ it.name }) { value -> Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.weight(1f)) { VariableRow(model,value,onCopy=onCopy) }; IconAction(Icons.Outlined.Close,"移除 ${value.name}") { model.removeWatch(value.name) } } } } }
                    }
                    5 -> LazyColumn { items(state.breakpoints) { point ->
                        val index=state.breakpoints.filter { it.path==point.path }.indexOf(point)
                        val bound=session.breakpointResults[point.path]?.getOrNull(index)
                        ListItem(headlineContent={ Text("${point.path}:${point.line+1}",fontSize=13.sp) },supportingContent={ Text(if(bound==null) "待调试器验证" else if(bound.verified) "有效 · 绑定第${bound.line+1}行" else bound.message.ifBlank { "未能绑定" },fontSize=12.sp) },
                            trailingContent={ IconButton(onClick={ model.toggleBreakpoint(point.path,point.line) }) { Icon(Icons.Outlined.Close,"移除断点") } },modifier=Modifier.clickable { model.openFile(point.path,Position(point.line,0)); if(compact) expanded=false })
                    } }
                }
            }
            if(tab==0) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                val interactive=state.project?.config?.preloadedInput==null
                fun send() {
                    if(sending) return
                    sending=true; val submitted=input; val revision=inputRevision; val id=session.id
                    model.input(submitted) { if(inputRevision==revision && model.session.value.id==id) { input=""; inputRevision++ } }.invokeOnCompletion { sending=false }
                }
                OutlinedTextField(value=input,onValueChange={ input=it; inputRevision++ },placeholder={ Text(if(!interactive) "预置输入将发送后关闭" else if(session.phase==SessionPhase.PAUSED) "继续运行后发送" else if(session.inputOpen) "可发送输入" else if(session.phase==SessionPhase.RUNNING) "输入已结束（EOF）" else "输入草稿 · 管道未打开",fontSize=12.sp) },singleLine=true,enabled=interactive,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={ if(session.canSendInput && interactive) send() }),modifier=Modifier.weight(1f).testTag("stdin").focusRequester(inputFocus).onFocusChanged { if(it.isFocused) { restoreInputFocus=true; explicitExpansion=true } },textStyle=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ send() },enabled=session.canSendInput && interactive && !sending,modifier=Modifier.testTag("send-stdin")) { Text(if(session.inputSending || sending) "发送中" else "发送",fontSize=12.sp) }
                TextButton(onClick={ model.eof() },enabled=session.canSendInput && interactive) { Text("结束输入",fontSize=12.sp) }
            }
        }
    }
    }
    if(compact) {
        body(48.dp,false)
        if(expanded) Dialog(onDismissRequest={ expanded=false; explicitExpansion=false; restoreInputFocus=false },properties=DialogProperties(usePlatformDefaultWidth=false)) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(MaterialTheme.colorScheme.surfaceContainer)) {
                LaunchedEffect(Unit) { if(restoreInputFocus && tab==0) { withFrameNanos { }; inputFocus.requestFocus() } }
                body(maxHeight,true)
            }
        }
    } else body(panelHeight,expanded)
    if(watchDialog) TextEntryDialog("添加观察项","例如 count、person.name、items[2]",onDismiss={ watchDialog=false }) { model.addWatch(it); watchDialog=false }
}

@Composable
private fun VariableRow(model: IdeViewModel,variable: DebugVariable,depth: Int=0,onCopy: (String)->Unit={}) {
    var open by remember(variable.reference,variable.name) { mutableStateOf(false) }
    var children by remember(variable.reference,variable.name) { mutableStateOf<List<DebugVariable>>(emptyList()) }
    var details by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=(8+depth*12).dp).clickable(enabled=variable.reference!=0 && depth<3) {
            open=!open; if(open) model.expandVariable(variable) { children=it }
        },verticalAlignment=Alignment.CenterVertically) {
            if(variable.reference!=0 && depth<3) Icon(if(open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,null,Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp))
            Text(variable.name,Modifier.widthIn(max=130.dp).padding(end=10.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(variable.value,Modifier.weight(1f),fontFamily=FontFamily.Monospace,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            IconButton(onClick={ details=true }) { Icon(Icons.Outlined.MoreHoriz,"查看 ${variable.name} 详情",Modifier.size(18.dp)) }
        }
        if(open) children.forEach { VariableRow(model,it,depth+1,onCopy) }
    }
    if(details) AlertDialog(onDismissRequest={ details=false },title={ Text(variable.name) },text={ SelectionContainer { Text("${variable.type}\n${variable.value}\n\n值由调试器返回，可能受长度限制。",Modifier.verticalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,fontSize=13.sp) } },confirmButton={ TextButton(onClick={ onCopy(variable.value) }) { Text("复制值") } },dismissButton={ TextButton(onClick={ details=false }) { Text("关闭") } })
}

@Composable
private fun CreateProjectDialog(onDismiss: ()->Unit,onCreate: (String,CodeLanguage,Boolean)->Unit) {
    var name by remember { mutableStateOf("我的第一个项目") }; var language by remember { mutableStateOf(CodeLanguage.PYTHON) }; var multi by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("新建项目") },text={ Column {
        OutlinedTextField(name,{ name=it },label={ Text("项目名称") },singleLine=true,modifier=Modifier.testTag("project-name"))
        Spacer(Modifier.height(12.dp)); Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { CodeLanguage.entries.forEach { item -> FilterChip(selected=language==item,onClick={ language=item },label={ Text(item.title) },modifier=Modifier.testTag("language-${item.name}")) } }
        Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(multi,{ multi=it }); Text("轻量多文件模板",fontSize=14.sp) }
    } },confirmButton={ TextButton(onClick={ onCreate(name,language,multi) },enabled=name.isNotBlank() && name.length<=64,modifier=Modifier.testTag("create-project")) { Text("创建") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}

@Composable
private fun TextEntryDialog(title: String,label: String,initial: String="",onDismiss: ()->Unit,onConfirm: (String)->Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text(title) },text={ OutlinedTextField(text,{ text=it },label={ Text(label) },singleLine=true) },
        confirmButton={ TextButton(onClick={ onConfirm(text) },enabled=text.isNotBlank()) { Text("确定") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}

@Composable
private fun RunConfiguration(config: ProjectConfig,files: List<String>,onDismiss: ()->Unit,onConfirm: (String,List<String>,Int,String?)->Unit) {
    var entry by remember { mutableStateOf(config.entry) }; val args=remember { mutableStateListOf<String>().apply { addAll(config.arguments) } }
    var entryMenu by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf(config.runTimeoutSeconds.toString()) }; var preset by remember { mutableStateOf(config.preloadedInput!=null) }
    var input by remember { mutableStateOf(config.preloadedInput.orEmpty()) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("运行配置") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(config.language==CodeLanguage.PYTHON || config.language==CodeLanguage.JAVA) {
            OutlinedTextField(entry,{ entry=it },label={ Text(if(config.language==CodeLanguage.JAVA) "入口类完整名称" else "入口脚本路径") },singleLine=true)
            Box { TextButton(onClick={ entryMenu=true }) { Text("从项目文件选择候选入口") }; DropdownMenu(entryMenu,{ entryMenu=false }) {
                files.filter(config.language::isSource).forEach { path ->
                    val candidate=if(config.language==CodeLanguage.JAVA) config.sourceRoots.fold(path) { result,root -> result.removePrefix("$root/") }.removeSuffix(".java").replace('/','.') else path
                    DropdownMenuItem(text={ Text(candidate) },onClick={ entry=candidate; entryMenu=false })
                }
            } }
        }
        Text("程序参数 · 每项独立传入，允许空字符串",style=MaterialTheme.typography.bodySmall)
        args.forEachIndexed { index,arg -> Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(arg,{ args[index]=it },singleLine=true,label={ Text("参数 ${index+1}") },modifier=Modifier.weight(1f))
            IconButton(onClick={ args.removeAt(index) }) { Icon(Icons.Outlined.Close,"移除参数 ${index+1}") }
        } }
        TextButton(onClick={ args.add("") },enabled=args.size<100) { Text("添加参数") }
        OutlinedTextField(timeout,{ timeout=it.filter(Char::isDigit) },label={ Text("运行上限（1～600 秒）") },singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        Row(verticalAlignment=Alignment.CenterVertically) { Switch(preset,{ preset=it }); Spacer(Modifier.width(12.dp)); Text("使用预置标准输入",fontSize=13.sp) }
        if(preset) OutlinedTextField(input,{ input=it },label={ Text("启动后发送，然后结束输入") },minLines=3,maxLines=6)
    } },confirmButton={ TextButton(onClick={ onConfirm(entry,args.toList(),timeout.toInt(),if(preset) input else null) },enabled=timeout.toIntOrNull() in 1..600 && entry.isNotBlank() && args.all { it.length<=8192 && !it.contains('\u0000') }) { Text("保存") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}

@Composable
private fun SettingsDialog(model: IdeViewModel,current: EditorSettings,onDismiss: ()->Unit,onUpdates: ()->Unit) {
    var draft by remember { mutableStateOf(current) }; var licenses by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf(current.runTimeout.toString()) }
    val tools by model.toolchains.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest=onDismiss,title={ Text("设置") },text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("外观主题",style=MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FilterChip(selected=!draft.dark && !draft.followSystem,onClick={ draft=draft.copy(dark=false,followSystem=false) },label={ Text("亮色") },leadingIcon={ Icon(Icons.Outlined.LightMode,null,Modifier.size(18.dp)) },modifier=Modifier.weight(1f).testTag("theme-light"))
            FilterChip(selected=draft.dark && !draft.followSystem,onClick={ draft=draft.copy(dark=true,followSystem=false) },label={ Text("暗色") },leadingIcon={ Icon(Icons.Outlined.DarkMode,null,Modifier.size(18.dp)) },modifier=Modifier.weight(1f).testTag("theme-dark"))
        }
        FilterChip(selected=draft.followSystem,onClick={ draft=draft.copy(followSystem=true) },label={ Text("跟随系统") },modifier=Modifier.testTag("theme-system"))
        Text("编辑器",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=12.dp))
        Row(verticalAlignment=Alignment.CenterVertically) { Text("自动换行",Modifier.weight(1f)); Switch(draft.wordWrap,{ draft=draft.copy(wordWrap=it) }) }
        Text("代码字号 · ${draft.fontSize.toInt()}",Modifier.padding(top=12.dp)); Slider(draft.fontSize,{ draft=draft.copy(fontSize=it) },valueRange=10f..30f,steps=19)
        Text("控制台字号 · ${draft.consoleFontSize.toInt()}"); Slider(draft.consoleFontSize,{ draft=draft.copy(consoleFontSize=it) },valueRange=12f..20f,steps=7)
        Text("运行",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=12.dp))
        OutlinedTextField(timeout,{ timeout=it.filter(Char::isDigit); it.toIntOrNull()?.takeIf { n -> n in 1..600 }?.let { n -> draft=draft.copy(runTimeout=n) } },singleLine=true,label={ Text("新项目默认上限（1～600秒）") },isError=timeout.toIntOrNull() !in 1..600,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        Row { listOf(30,60,120,300).forEach { seconds -> TextButton(onClick={ timeout=seconds.toString(); draft=draft.copy(runTimeout=seconds) }) { Text("${seconds}s") } } }
        Text("已有项目请在“运行配置”中修改上限。",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical=12.dp)); Text(tools.error ?: tools.message,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={ model.retryTools() },enabled=!model.session.value.active) { Text("重新检查运行组件") }
        TextButton(onClick={ model.retryLanguage() }) { Text("重启当前语言服务") }
        TextButton(onClick={ licenses=true }) { Text("开源组件说明") }
        TextButton(onClick=onUpdates,modifier=Modifier.testTag("软件更新")) { Text("软件更新") }
        Text("Mobile Code ${dev.mobilecode.ide.BuildConfig.VERSION_NAME}\nAndroid 10+ · ${dev.mobilecode.ide.BuildConfig.RUNTIME_ABI}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } },confirmButton={ TextButton(onClick={ model.updateSettings(draft); onDismiss() },enabled=timeout.toIntOrNull() in 1..600) { Text("保存") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
    if(licenses) {
        val context=LocalContext.current
        val text=remember { runCatching { context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() } }.getOrDefault("组件许可证随源码与交付材料提供。") }
        AlertDialog(onDismissRequest={ licenses=false },title={ Text("开源组件") },text={ SelectionContainer { Text(text,Modifier.verticalScroll(rememberScrollState()),fontSize=11.sp) } },confirmButton={ TextButton(onClick={ licenses=false }) { Text("关闭") } })
    }
}
