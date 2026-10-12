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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobilecode.ide.*
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.MobileCodeEditor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen(model: IdeViewModel,onImport: ()->Unit,onExport: ()->Unit,onExportSource: ()->Unit,onRun: (Boolean)->Unit,onCopy: (String)->Unit,onInstallUpdate: ()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
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
    var editor by remember { mutableStateOf<MobileCodeEditor?>(null) }
    val drawer=rememberDrawerState(DrawerValue.Closed); val scope=rememberCoroutineScope()
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it,actionLabel="关闭",duration=SnackbarDuration.Long); model.clearError() } }
    BackHandler(enabled=state.project!=null) { if(state.preview!=null) model.closePreview() else model.home() }

    val browser: @Composable ()->Unit = { FileBrowser(state,onFile={ model.openFile(it); scope.launch { drawer.close() } },onNew={ fileDialog=true },onHome={ model.home(); scope.launch { drawer.close() } }) }
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
                                DropdownMenuItem(text={ Text("新建文件") },onClick={ menu=false; fileDialog=true },enabled=!session.sourceLocked)
                                DropdownMenuItem(text={ Text("运行配置与测试输入") },onClick={ menu=false; runDialog=true },enabled=!session.active)
                                DropdownMenuItem(text={ Text("导出项目 ZIP") },onClick={ menu=false; onExport() })
                                DropdownMenuItem(text={ Text("导出当前源码") },onClick={ menu=false; onExportSource() },enabled=state.document!=null && state.preview==null)
                                DropdownMenuItem(text={ Text("展开全部代码") },onClick={ menu=false; editor?.unfoldAll() })
                                DropdownMenuItem(text={ Text("设置") },onClick={ menu=false; settingDialog=true })
                                DropdownMenuItem(text={ Text("项目首页") },onClick={ menu=false; model.home() })
                            }
                        } else IconAction(Icons.Outlined.Settings,"设置") { settingDialog=true }
                    })
            }) { padding ->
                if(state.project==null) ProjectHome(state,tools.message,tools.progress,tools.error,onCreate={ create=true },onImport=onImport,onOpen=model::openProject,
                    modifier=Modifier.padding(padding))
                else Row(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if(wide) { Box(Modifier.width(240.dp).fillMaxHeight()) { browser() }; VerticalDivider() }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if(state.preview!=null) Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer),verticalAlignment=Alignment.CenterVertically) {
                            Text(state.preview!!.title,Modifier.weight(1f).padding(start=16.dp),style=MaterialTheme.typography.labelMedium)
                            IconAction(Icons.Outlined.Close,"关闭快照") { model.closePreview() }
                        } else if(state.documents.isNotEmpty()) ScrollableTabRow(selectedTabIndex=state.documents.keys.indexOf(state.activePath).coerceAtLeast(0),edgePadding=0.dp) {
                            state.documents.values.forEach { doc -> Tab(selected=doc.path==state.activePath,onClick={ model.openFile(doc.path) },text={ Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(doc.path.substringAfterLast('/')+if(doc.dirty) " •" else "",fontFamily=FontFamily.Monospace,fontSize=12.sp)
                                Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.Close,"关闭 ${doc.path}",Modifier.size(20.dp).clickable { model.closeFile(doc.path) })
                            } }) }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if(state.document!=null) EditorHost(model,state,settings,session,diagnostics,Modifier.fillMaxSize()) { editor=it }
                            else Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text("选择文件开始编写",color=MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick={ scope.launch { drawer.open() } }) { Text("打开项目文件") }
                            }
                        }
                        if(state.signature.isNotBlank()) Text(state.signature,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal=12.dp,vertical=6.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(if(session.sourceLocked || state.preview!=null) "只读" else if(state.documents.values.any { it.dirty }) "正在保存" else "已保存",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp)); Text(if(tools.toolchain==null) tools.message else languageStatus,Modifier.weight(1f),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("UTF-8 · ${if(state.document?.text?.contains("\r\n")==true) "CRLF" else "LF"}",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        KeyboardBar(editor,enabled=!session.sourceLocked && state.preview==null)
                        OutputPanel(model,state,session,diagnostics,onCopy)
                    }
                }
            }
        }
        if(state.project!=null && !wide) ModalNavigationDrawer(drawerState=drawer,drawerContent={ ModalDrawerSheet(Modifier.width(290.dp)) { browser() } },content=content) else content()
    }
    if(create) CreateProjectDialog(onDismiss={ create=false },onCreate={ name,language,multi -> create=false; model.createProject(name,language,multi) })
    if(fileDialog) TextEntryDialog("新建文件","项目内相对路径",initial="src/utils.${state.project?.config?.language?.extension ?: "txt"}",onDismiss={ fileDialog=false }) { value -> fileDialog=false; model.createFile(value.trim()) }
    if(settingDialog) SettingsDialog(model,settings,onDismiss={ settingDialog=false },onUpdates={ updateDialog=true })
    if(updateDialog) UpdateDialog(model.app.updates,installationAllowed=!session.active,onInstall=onInstallUpdate,onDismiss={ updateDialog=false })
    if(runDialog && state.project!=null) RunConfiguration(state.project!!.config,state.files,onDismiss={ runDialog=false }) { entry,args,timeout,preload -> runDialog=false; model.configureRun(entry,args,timeout,preload) }
    if(search && editor!=null) SearchDialog(editor!!,readOnly=session.sourceLocked || state.preview!=null,onDismiss={ editor?.searcher?.stopSearch(); search=false })
}

@Composable
private fun IconAction(icon: ImageVector,label: String,enabled: Boolean=true,onClick: ()->Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).testTag(label)) { Icon(icon,label) }
}

@Composable
private fun ProjectHome(state: IdeState,status: String,progress: Float,error: String?,onCreate: ()->Unit,onImport: ()->Unit,onOpen: (Project)->Unit,modifier: Modifier) {
    var filter by remember { mutableStateOf<CodeLanguage?>(null) }
    Column(modifier.fillMaxSize().padding(horizontal=24.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("把想法，\n写成能运行的代码。",style=MaterialTheme.typography.headlineMedium,lineHeight=40.sp)
        Spacer(Modifier.height(12.dp)); Text("C / C++ / Python / Java\n全部在本机运行，离线也能继续。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=24.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=onCreate,modifier=Modifier.height(52.dp).testTag("new-project")) { Icon(Icons.Outlined.Add,null); Spacer(Modifier.width(6.dp)); Text("新建项目") }
            OutlinedButton(onClick=onImport,modifier=Modifier.height(52.dp)) { Text("导入 ZIP") }
        }
        if(progress<1f) {
            Spacer(Modifier.height(24.dp)); Text(error ?: status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(error==null) LinearProgressIndicator(progress={ progress },modifier=Modifier.fillMaxWidth().padding(top=10.dp))
        }
        Spacer(Modifier.height(28.dp)); HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top=16.dp),verticalAlignment=Alignment.CenterVertically) { Text("最近项目",style=MaterialTheme.typography.titleMedium); Spacer(Modifier.weight(1f)); Text("${state.projects.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=filter==null,onClick={ filter=null },label={ Text("全部") })
            CodeLanguage.entries.forEach { language -> FilterChip(selected=filter==language,onClick={ filter=language },label={ Text(language.title) }) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.projects.filter { filter==null || it.config.language==filter },key={ it.config.id }) { project ->
                ListItem(headlineContent={ Text(project.config.name) },supportingContent={ Text(project.config.language.title+" · 本地项目") },
                    leadingContent={ Icon(Icons.Outlined.Code,null,tint=MaterialTheme.colorScheme.primary) },trailingContent={ Icon(Icons.Outlined.ChevronRight,null) },
                    modifier=Modifier.clickable { onOpen(project) })
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            }
            if(state.projects.isEmpty()) item { Text("从一个小程序开始。项目会自动保存在手机上。",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FileBrowser(state: IdeState,onFile: (String)->Unit,onNew: ()->Unit,onHome: ()->Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) { Text("项目文件",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); IconAction(Icons.Outlined.Add,"新建文件",onClick=onNew) }
        Text(state.project?.config?.name.orEmpty(),Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)) { items(state.files,key={ it }) { path ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(if(path==state.activePath) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent).clickable { onFile(path) }.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp)); Text(path,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace,maxLines=2)
            }
        } }
        HorizontalDivider(); TextButton(onClick=onHome,modifier=Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Outlined.Home,null); Spacer(Modifier.width(8.dp)); Text("项目首页") }
    }
}

@Composable
private fun KeyboardBar(editor: MobileCodeEditor?,enabled: Boolean) {
    fun key(code: Int) { editor?.requestFocus(); editor?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code)); editor?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code)) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceContainer),verticalAlignment=Alignment.CenterVertically) {
        IconAction(Icons.Outlined.Undo,"撤销",enabled && editor?.canUndo()==true) { editor?.undo() }
        IconAction(Icons.Outlined.Redo,"重做",enabled && editor?.canRedo()==true) { editor?.redo() }
        TextButton(onClick={ key(KeyEvent.KEYCODE_TAB) },enabled=enabled,modifier=Modifier.height(48.dp)) { Text("Tab",fontFamily=FontFamily.Monospace) }
        TextButton(onClick={ key(KeyEvent.KEYCODE_DPAD_LEFT) },modifier=Modifier.size(48.dp)) { Text("←") }
        TextButton(onClick={ key(KeyEvent.KEYCODE_DPAD_RIGHT) },modifier=Modifier.size(48.dp)) { Text("→") }
        listOf("{","}","(",")","[","]",";",":","\"","/","\\").forEach { symbol -> TextButton(onClick={ editor?.insertText(symbol,1); editor?.requestFocus() },enabled=enabled,modifier=Modifier.size(48.dp)) { Text(symbol,fontFamily=FontFamily.Monospace) } }
    }
}

@Composable
private fun OutputPanel(model: IdeViewModel,state: IdeState,session: SessionState,diagnostics: List<Diagnostic>,onCopy: (String)->Unit) {
    var height by rememberSaveable { mutableFloatStateOf(240f) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var watchDialog by remember { mutableStateOf(false) }
    var channel by remember { mutableIntStateOf(0) }
    var channelMenu by remember { mutableStateOf(false) }
    val density=LocalDensity.current
    val keyboard=WindowInsets.ime.getBottom(density)>0
    val panelHeight=if(!expanded) 48.dp else (if(keyboard) height.coerceAtMost(180f) else height).dp
    LaunchedEffect(session.phase) { if(session.phase==SessionPhase.PAUSED) { expanded=true; tab=3 } else if(session.phase==SessionPhase.FAILED && diagnostics.isNotEmpty()) { expanded=true; tab=1 } }
    Column(Modifier.fillMaxWidth().height(panelHeight).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().height(48.dp).pointerInput(density) { detectVerticalDragGestures { change,amount -> change.consume(); height=(height-amount/density.density).coerceIn(144f,460f) } },verticalAlignment=Alignment.CenterVertically) {
            Text(when(session.phase) { SessionPhase.IDLE->"控制台"; SessionPhase.BUILDING->"编译中"; SessionPhase.PAUSED->"已暂停"; SessionPhase.RUNNING->"运行中"; SessionPhase.STARTING,SessionPhase.PREPARING->"准备中"; else->session.reason },Modifier.weight(1f).padding(start=12.dp),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(!session.active && session.id!=null) Text("${session.elapsedMs/1000.0}s · ${session.exitCode?.let { "退出 $it" } ?: "—"}",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            IconAction(if(expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,"折叠控制台") { expanded=!expanded }
        }
        if(expanded) {
            if(session.debugging && session.active) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.SpaceEvenly) {
                IconAction(Icons.Outlined.PlayArrow,"继续",session.phase==SessionPhase.PAUSED) { model.debugCommand("continue") }
                IconAction(Icons.Outlined.SkipNext,"单步跳过",session.phase==SessionPhase.PAUSED) { model.debugCommand("next") }
                IconAction(Icons.Outlined.CallMade,"单步进入",session.phase==SessionPhase.PAUSED) { model.debugCommand("stepIn") }
                IconAction(Icons.Outlined.CallReceived,"单步跳出",session.phase==SessionPhase.PAUSED) { model.debugCommand("stepOut") }
                IconAction(Icons.Outlined.Pause,"暂停",session.phase==SessionPhase.RUNNING) { model.debugCommand("pause") }
                IconAction(Icons.Outlined.Refresh,"重新调试") { model.restart() }
            }
            ScrollableTabRow(selectedTabIndex=tab,edgePadding=0.dp) { listOf("输出","问题 ${diagnostics.size}","调用栈","变量","观察").forEachIndexed { index,title -> Tab(selected=tab==index,onClick={ tab=index },text={ Text(title,fontSize=11.sp) }) } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when(tab) {
                    0 -> {
                        val scroll=rememberScrollState(); val full=when(channel) { 1->session.standardOutput; 2->session.standardError; 3->session.buildOutput; else->session.output }; val text=full.takeLast(32_000)
                        LaunchedEffect(text) { if(scroll.value>=scroll.maxValue-80) scroll.scrollTo(scroll.maxValue) }
                        SelectionContainer { Text(text.ifEmpty { "程序输出会显示在这里" },Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row(Modifier.align(Alignment.TopEnd)) {
                            Box { TextButton(onClick={ channelMenu=true }) { Text(listOf("全部","标准输出","标准错误","编译")[channel],fontSize=10.sp) }; DropdownMenu(channelMenu,{ channelMenu=false }) { listOf("全部","标准输出","标准错误","编译").forEachIndexed { index,title -> DropdownMenuItem(text={ Text(title) },onClick={ channel=index; channelMenu=false }) } } }
                            TextButton(onClick={ onCopy(full) }) { Text("复制",fontSize=10.sp) }
                        }
                    }
                    1 -> LazyColumn(Modifier.fillMaxSize()) { items(diagnostics) { item -> ListItem(headlineContent={ Text(item.message,fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis) },supportingContent={ Text("${item.path.substringAfterLast('/')}:${item.range.start.line+1}",fontSize=10.sp) },leadingContent={ Text(if(item.severity==1) "错误" else "警告",fontSize=10.sp,color=if(item.severity==1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },modifier=Modifier.clickable { model.diagnostic(item) }) } }
                    2 -> LazyColumn(Modifier.fillMaxSize()) { items(session.frames,key={ it.id }) { frame -> ListItem(headlineContent={ Text(frame.name,fontFamily=FontFamily.Monospace,fontSize=12.sp,maxLines=1) },supportingContent={ Text("${frame.path?.substringAfterLast('/')} : ${frame.line+1}",fontSize=10.sp) },modifier=Modifier.clickable { model.selectFrame(frame) }) } }
                    3 -> if(session.variables.isEmpty()) Text("暂停后查看当前栈帧的变量",Modifier.padding(12.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) else LazyColumn { items(session.variables) { variable -> VariableRow(model,variable) } }
                    4 -> Column {
                        TextButton(onClick={ watchDialog=true }) { Icon(Icons.Outlined.Add,null,Modifier.size(18.dp)); Text("添加观察项",fontSize=11.sp) }
                        LazyColumn { items(state.watchValues,key={ it.name }) { value -> Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.weight(1f)) { VariableRow(model,value) }; IconAction(Icons.Outlined.Close,"移除 ${value.name}") { model.removeWatch(value.name) } } } }
                    }
                }
            }
            if(tab==0) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                val interactive=state.project?.config?.preloadedInput==null
                OutlinedTextField(value=input,onValueChange={ input=it },placeholder={ Text(if(interactive) "标准输入" else "已使用预置输入",fontSize=12.sp) },singleLine=true,enabled=session.active && interactive,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={ model.input(input); input="" }),modifier=Modifier.weight(1f).testTag("stdin"),textStyle=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ model.input(input); input="" },enabled=session.active && interactive) { Text("发送",fontSize=11.sp) }
                TextButton(onClick={ model.eof() },enabled=session.active && interactive) { Text("EOF",fontSize=11.sp) }
            }
        }
    }
    if(watchDialog) TextEntryDialog("添加观察项","例如 count、person.name、items[2]",onDismiss={ watchDialog=false }) { model.addWatch(it); watchDialog=false }
}

@Composable
private fun VariableRow(model: IdeViewModel,variable: DebugVariable,depth: Int=0) {
    var open by remember(variable.reference,variable.name) { mutableStateOf(false) }
    var children by remember(variable.reference,variable.name) { mutableStateOf<List<DebugVariable>>(emptyList()) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=(8+depth*12).dp).clickable(enabled=variable.reference!=0 && depth<3) {
            open=!open; if(open) model.expandVariable(variable) { children=it }
        },verticalAlignment=Alignment.CenterVertically) {
            if(variable.reference!=0 && depth<3) Icon(if(open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,null,Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp))
            Text(variable.name,Modifier.widthIn(max=130.dp).padding(end=10.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(variable.value,Modifier.weight(1f),fontFamily=FontFamily.Monospace,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        if(open) children.forEach { VariableRow(model,it,depth+1) }
    }
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
    var entry by remember { mutableStateOf(config.entry) }; var args by remember { mutableStateOf(config.arguments.joinToString("\n")) }
    var timeout by remember { mutableStateOf(config.runTimeoutSeconds.toString()) }; var preset by remember { mutableStateOf(config.preloadedInput!=null) }
    var input by remember { mutableStateOf(config.preloadedInput.orEmpty()) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("运行配置") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(config.language==CodeLanguage.PYTHON || config.language==CodeLanguage.JAVA) OutlinedTextField(entry,{ entry=it },label={ Text(if(config.language==CodeLanguage.JAVA) "入口类完整名称" else "入口脚本路径") },singleLine=true)
        OutlinedTextField(args,{ args=it },label={ Text("程序参数，每行一个") },minLines=2,maxLines=4)
        OutlinedTextField(timeout,{ timeout=it.filter(Char::isDigit) },label={ Text("运行上限（1～600 秒）") },singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        Row(verticalAlignment=Alignment.CenterVertically) { Switch(preset,{ preset=it }); Spacer(Modifier.width(12.dp)); Text("使用预置标准输入",fontSize=13.sp) }
        if(preset) OutlinedTextField(input,{ input=it },label={ Text("启动后发送，然后结束输入") },minLines=3,maxLines=6)
    } },confirmButton={ TextButton(onClick={ onConfirm(entry,args.lines().filter { it.isNotEmpty() },timeout.toInt(),if(preset) input else null) },enabled=timeout.toIntOrNull() in 1..600 && entry.isNotBlank()) { Text("保存") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}

@Composable
private fun SettingsDialog(model: IdeViewModel,current: EditorSettings,onDismiss: ()->Unit,onUpdates: ()->Unit) {
    var draft by remember { mutableStateOf(current) }; var licenses by remember { mutableStateOf(false) }
    val tools by model.toolchains.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest=onDismiss,title={ Text("设置") },text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment=Alignment.CenterVertically) { Text("深色主题",Modifier.weight(1f)); Switch(draft.dark,{ draft=draft.copy(dark=it) }) }
        Row(verticalAlignment=Alignment.CenterVertically) { Text("自动换行",Modifier.weight(1f)); Switch(draft.wordWrap,{ draft=draft.copy(wordWrap=it) }) }
        Text("代码字号 · ${draft.fontSize.toInt()}",Modifier.padding(top=12.dp)); Slider(draft.fontSize,{ draft=draft.copy(fontSize=it) },valueRange=10f..30f,steps=19)
        Text("新项目默认运行上限 · ${draft.runTimeout}s"); Slider(draft.runTimeout.toFloat(),{ draft=draft.copy(runTimeout=it.toInt()) },valueRange=1f..600f)
        HorizontalDivider(Modifier.padding(vertical=12.dp)); Text(tools.error ?: tools.message,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={ model.retryTools() }) { Text("重新检查运行组件与补全") }
        TextButton(onClick={ licenses=true }) { Text("开源组件说明") }
        TextButton(onClick=onUpdates,modifier=Modifier.testTag("软件更新")) { Text("软件更新") }
        Text("Mobile Code ${dev.mobilecode.ide.BuildConfig.VERSION_NAME}\nAndroid 10+ · ${dev.mobilecode.ide.BuildConfig.RUNTIME_ABI}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } },confirmButton={ TextButton(onClick={ model.updateSettings(draft); onDismiss() }) { Text("保存") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
    if(licenses) {
        val context=LocalContext.current
        val text=remember { runCatching { context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() } }.getOrDefault("组件许可证随源码与交付材料提供。") }
        AlertDialog(onDismissRequest={ licenses=false },title={ Text("开源组件") },text={ SelectionContainer { Text(text,Modifier.verticalScroll(rememberScrollState()),fontSize=11.sp) } },confirmButton={ TextButton(onClick={ licenses=false }) { Text("关闭") } })
    }
}

@Composable
private fun SearchDialog(editor: MobileCodeEditor,readOnly: Boolean,onDismiss: ()->Unit) {
    var find by remember { mutableStateOf("") }; var replacement by remember { mutableStateOf("") }
    LaunchedEffect(find) { if(find.isNotEmpty()) editor.searcher.search(find,EditorSearcher.SearchOptions(false,false)) else editor.searcher.stopSearch() }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("查找与替换") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(find,{ find=it },label={ Text("查找") },singleLine=true)
        OutlinedTextField(replacement,{ replacement=it },label={ Text("替换为") },singleLine=true,enabled=!readOnly)
        Row { TextButton(onClick={ editor.searcher.gotoPrevious() },enabled=find.isNotEmpty()) { Text("上一个") }; TextButton(onClick={ editor.searcher.gotoNext() },enabled=find.isNotEmpty()) { Text("下一个") } }
        Row { TextButton(onClick={ editor.searcher.replaceCurrentMatch(replacement) },enabled=!readOnly && find.isNotEmpty()) { Text("替换") }; TextButton(onClick={ editor.searcher.replaceAll(replacement) },enabled=!readOnly && find.isNotEmpty()) { Text("全部替换") } }
    } },confirmButton={ TextButton(onClick=onDismiss) { Text("完成") } })
}
