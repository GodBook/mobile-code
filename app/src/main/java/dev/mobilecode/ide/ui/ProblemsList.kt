package dev.mobilecode.ide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobilecode.ide.*
import dev.mobilecode.ide.core.*

@Composable
internal fun ProblemsList(model: IdeViewModel,diagnostics: List<Diagnostic>,state: IdeState,session: SessionState,onNavigate: ()->Unit) {
    var severity by remember { mutableIntStateOf(0) }; var source by remember { mutableIntStateOf(0) }; var current by remember { mutableStateOf(false) }
    fun isRun(item: Diagnostic)=item.origin==DiagnosticOrigin.BUILD
    fun isCurrent(item: Diagnostic)=item.projectId==state.project?.config?.id && item.relativePath==state.activePath
    val visible=diagnostics.filter { item -> (severity==0 || (severity==1 && item.severity==1) || (severity==2 && item.severity!=1)) &&
        (source==0 || (source==1 && !isRun(item)) || (source==2 && isRun(item))) && (!current || isCurrent(item)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            FilterChip(selected=severity==0,onClick={ severity=0 },label={ Text("全部") })
            FilterChip(selected=severity==1,onClick={ severity=1 },label={ Text("错误") })
            FilterChip(selected=severity==2,onClick={ severity=2 },label={ Text("警告/提示") })
            FilterChip(selected=current,onClick={ current=!current },label={ Text("当前文件") })
            FilterChip(selected=source==1,onClick={ source=if(source==1) 0 else 1 },label={ Text("实时分析") })
            FilterChip(selected=source==2,onClick={ source=if(source==2) 0 else 2 },label={ Text("运行快照") })
        }
        LazyColumn(Modifier.weight(1f)) { items(visible) { item -> ListItem(
            headlineContent={ Text(item.message,fontSize=13.sp,maxLines=4,overflow=TextOverflow.Ellipsis) },
            supportingContent={ Text("${item.path.substringAfterLast('/')}:${item.range.start.line+1} · ${if(isRun(item)) "运行快照" else if(item.version==null) "实时分析（未提供版本）" else "实时分析"} · ${item.source}",fontSize=12.sp) },
            leadingContent={ Text(if(item.severity==1) "错误" else "提示",fontSize=12.sp,color=if(item.severity==1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
            modifier=Modifier.clickable { model.diagnostic(item); onNavigate() }) } }
    }
}
