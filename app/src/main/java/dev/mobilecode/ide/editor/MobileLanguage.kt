package dev.mobilecode.ide.editor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.mobilecode.ide.core.*
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.BracketsProvider
import io.github.rosemoe.sora.lang.brackets.SimpleBracketsCollector
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHintsContainer
import io.github.rosemoe.sora.lang.completion.*
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.lang.styling.*
import io.github.rosemoe.sora.text.*
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.runBlocking
import java.util.BitSet
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

object NativeSyntax {
    init { System.loadLibrary("mc_syntax") }
    external fun create(language: Int): Long
    external fun parse(handle: Long, text: String): IntArray
    external fun destroy(handle: Long)
}

class SyntaxAnalyzer(private val language: CodeLanguage, private val onBlocks: (List<Pair<Int,Int>>)->Unit): SimpleAnalyzeManager<Unit>() {
    private data class Stamp(val version: Long,val lines: Int,val brackets: BracketsProvider,val folds: List<Pair<Int,Int>>)
    private val version=AtomicLong()
    private val stamps=WeakHashMap<Styles,Stamp>()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var content: ContentReference?=null
    private val lock=Any()
    private var handle=NativeSyntax.create(language.ordinal)
    override fun reset(content: ContentReference,extraArguments: Bundle) { version.incrementAndGet(); this.content=content; super.reset(content,extraArguments) }
    override fun insert(start: CharPosition,end: CharPosition,value: CharSequence) { version.incrementAndGet(); super.insert(start,end,value) }
    override fun delete(start: CharPosition,end: CharPosition,value: CharSequence) { version.incrementAndGet(); super.delete(start,end,value) }
    override fun setReceiver(receiver: StyleReceiver?) {
        if(receiver==null) { super.setReceiver(null); return }
        super.setReceiver(object: StyleReceiver {
            override fun setStyles(manager: AnalyzeManager,styles: Styles?) = setStyles(manager,styles,null)
            override fun setStyles(manager: AnalyzeManager,styles: Styles?,action: Runnable?) {
                if(styles==null) return
                val stamp=synchronized(stamps) { stamps.remove(styles) } ?: return
                main.post {
                    // Sora's simple analyzer can enqueue a result before reset().
                    // Validate again on the UI thread, immediately before installation.
                    if(content!=null && stamp.version==version.get() && stamp.lines==content!!.lineCount) {
                        receiver.setStyles(manager,styles,action)
                        receiver.updateBracketProvider(manager,stamp.brackets)
                        onBlocks(stamp.folds)
                    }
                }
            }
            override fun setDiagnostics(manager: AnalyzeManager,value: DiagnosticsContainer?) { receiver.setDiagnostics(manager,value) }
            override fun setInlayHints(manager: AnalyzeManager,value: InlayHintsContainer?) { receiver.setInlayHints(manager,value) }
            override fun updateBracketProvider(manager: AnalyzeManager,value: BracketsProvider?) { receiver.updateBracketProvider(manager,value) }
        })
    }
    override fun analyze(text: StringBuilder, delegate: SimpleAnalyzeManager<Unit>.Delegate<Unit>): Styles {
        val analyzedVersion=version.get()
        val source=text.toString()
        fun cancelled() = Styles(MappedSpans.Builder(1).apply { addNormalIfNull() }.build())
        if(delegate.isCancelled) return cancelled()
        val spans=synchronized(lock) { if(handle==0L) IntArray(0) else NativeSyntax.parse(handle,source) }
        if(delegate.isCancelled) return cancelled()
        val starts=IntArray(source.count { it=='\n' }+1)
        var nextLine=1
        source.forEachIndexed { i,c -> if(c=='\n') starts[nextLine++]=i+1 }
        fun position(i: Int): Position {
            val found=starts.binarySearch(i); val line=if(found>=0) found else (-found-2).coerceAtLeast(0)
            return Position(line,i-starts[line])
        }
        // Native tokens are ordered and disjoint. Convert only their endpoints;
        // a byte-to-character array used to cost over 4MiB per 1MiB ASCII file.
        var byteOffset=0; var charOffset=0
        fun characterAt(byte: Int): Int {
            while(byteOffset<byte && charOffset<source.length) {
                val cp=Character.codePointAt(source,charOffset)
                byteOffset+=when { cp<128->1; cp<2048->2; cp<65536->3; else->4 }
                charOffset+=Character.charCount(cp)
            }
            return charOffset
        }
        val builder=MappedSpans.Builder(starts.size)
        builder.addIfNeeded(0,0,EditorColorScheme.TEXT_NORMAL.toLong())
        val excluded=BitSet(source.length)
        for(i in spans.indices step 3) {
            if(delegate.isCancelled) return cancelled()
            val begin=characterAt(spans[i]); val end=characterAt(spans[i+1])
            val a=position(begin); val b=position(end)
            builder.addIfNeeded(a.line,a.character,TextStyle.makeStyle(SyntaxStyle.color(spans[i+2]),spans[i+2] in 2..3))
            builder.addIfNeeded(b.line,b.character,EditorColorScheme.TEXT_NORMAL.toLong())
            if(spans[i+2] in 2..3) excluded.set(begin,end)
        }
        builder.determine(starts.lastIndex)
        val styles=Styles(builder.build())
        val folds=mutableListOf<Pair<Int,Int>>()
        if(language==CodeLanguage.PYTHON) {
            val pending=ArrayDeque<Pair<Int,Int>>()
            starts.forEachIndexed { line,start ->
                val value=source.substring(start,if(line<starts.lastIndex) starts[line+1]-1 else source.length)
                if(value.isNotBlank() && !value.trimStart().startsWith('#')) {
                    val indentation=value.takeWhile { it==' ' || it=='\t' }.sumOf { if(it=='\t') 4 else 1 }
                    while(pending.isNotEmpty() && indentation<=pending.last().second) {
                        val open=pending.removeLast(); if(line-open.first>1) folds+=open.first to line-1
                    }
                    if(value.trimEnd().endsWith(':')) pending.addLast(line to indentation)
                }
            }
            while(pending.isNotEmpty()) { val open=pending.removeLast(); if(starts.lastIndex>open.first) folds+=open.first to starts.lastIndex }
        } else {
            val pending=ArrayDeque<Int>()
            source.forEachIndexed { i,c ->
                if(!excluded[i]) when(c) {
                    '{'->pending.addLast(i)
                    '}'->if(pending.isNotEmpty()) {
                        val a=position(pending.removeLast()); val b=position(i)
                        if(b.line>a.line) { styles.addCodeBlock(CodeBlock().also { it.startLine=a.line; it.startColumn=a.character; it.endLine=b.line; it.endColumn=b.character }); if(b.line>a.line+1) folds+=a.line to b.line-1 }
                    }
                }
            }
        }
        val brackets=SimpleBracketsCollector()
        val opened=ArrayDeque<Pair<Char,Int>>()
        source.forEachIndexed { i,c -> if(!excluded[i]) {
            if(c in "([{") opened.addLast(c to i)
            else if(c in ")]}" && opened.isNotEmpty()) {
                val expected=when(c) { ')'->'('; ']'->'['; else->'{' }
                if(opened.last().first==expected) brackets.add(opened.removeLast().second,i)
            }
        } }
        styles.finishBuilding()
        if(delegate.isCancelled) return cancelled()
        val currentBrackets=BracketsProvider { actual,position ->
            if(version.get()==analyzedVersion) brackets.getPairedBracketAt(actual,position) else null
        }
        synchronized(stamps) { stamps[styles]=Stamp(analyzedVersion,starts.size,currentBrackets,folds) }
        return styles
    }
    override fun destroy() { content=null; version.incrementAndGet(); main.removeCallbacksAndMessages(null); super.destroy(); synchronized(stamps) { stamps.clear() }; synchronized(lock) { if(handle!=0L) NativeSyntax.destroy(handle); handle=0L } }
}

class MobileLanguage(private val language: CodeLanguage, private val document: ()->Document?,
                     private val completion: suspend (Document,Position)->List<Completion>,
                     blocks: (List<Pair<Int,Int>>)->Unit): EmptyLanguage() {
    private val analyzer=SyntaxAnalyzer(language,blocks)
    override fun getAnalyzeManager(): AnalyzeManager = analyzer
    override fun getSymbolPairs(): SymbolPairMatch = SymbolPairMatch.DefaultSymbolPairs()
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        val prefix=content.getLine(line).substring(0,column).trimEnd()
        return if(prefix.endsWith('{') || (language==CodeLanguage.PYTHON && prefix.endsWith(':'))) 4 else 0
    }
    override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extraArguments: Bundle) {
        val original=document() ?: return
        val line=content.getLine(position.line)
        val prefix=line.substring(0,position.column).takeLastWhile { it.isLetterOrDigit() || it=='_' }
        val semantic=runCatching { runBlocking { completion(original,Position(position.line,position.column)) } }.getOrDefault(emptyList())
        val current=document() ?: return
        if(current.version!=original.version || current.path!=original.path) return
        if(semantic.isNotEmpty()) semantic.forEach { publisher.addItem(SemanticItem(it,prefix.length,document)) }
        else {
            val words=keywords(language).asSequence()+Regex("[\\p{L}_][\\p{L}\\p{N}_]*").findAll(original.text).map { it.value }
            words.filter { it.startsWith(prefix) && it!=prefix }.distinct().take(80).forEach { publisher.addItem(SimpleCompletionItem(it,"基础补全",prefix.length,it)) }
            snippets(language).filter { it.first.startsWith(prefix) }.forEach { (label,text) -> publisher.addItem(SemanticItem(Completion(label,text,"代码片段",snippet=true,version=original.version),prefix.length,document)) }
        }
        publisher.updateList()
    }
    override fun destroy() = analyzer.destroy()
    private class SemanticItem(private val completion: Completion, prefix: Int, private val current: ()->Document?): CompletionItem(completion.label,completion.detail) {
        init { prefixLength=prefix }
        override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
            val document=current() ?: return
            if(document.version!=completion.version) return
            val range=completion.range ?: SourceRange(Position(line,(column-prefixLength).coerceAtLeast(0)),Position(line,column))
            val edits=(completion.additionalEdits+listOf(range to if(completion.snippet) "" else completion.insertText)).sortedByDescending { Positions.index(document.text,it.first.start) }
            var insertion=Positions.index(document.text,range.start)
            for((area,value) in edits) {
                val a=Positions.index(document.text,area.start); val b=Positions.index(document.text,area.end)
                text.replace(area.start.line,area.start.character,area.end.line,area.end.character,value)
                if(a<Positions.index(document.text,range.start)) insertion+=value.length-(b-a)
            }
            if(completion.snippet) editor.snippetController.startSnippet(insertion,CodeSnippetParser.parse(completion.insertText))
        }
    }
    companion object {
        fun keywords(language: CodeLanguage): List<String> = when(language) {
            CodeLanguage.PYTHON -> "def class return if elif else for while in import from as try except finally with lambda yield async await True False None print input range len list dict str int".split(' ')
            CodeLanguage.JAVA -> "public private protected class interface extends implements static final void int long double boolean String return if else for while new try catch throw throws import package System".split(' ')
            else -> "int float double char bool void const static struct enum typedef return if else for while switch case break continue sizeof include printf scanf namespace using std class public private template auto nullptr".split(' ')
        }
        fun snippets(language: CodeLanguage): List<Pair<String,String>> = when(language) {
            CodeLanguage.PYTHON -> listOf("def" to "def ${'$'}{1:name}(${'$'}{2:args}):\n    ${'$'}0", "for" to "for ${'$'}{1:item} in ${'$'}{2:items}:\n    ${'$'}0")
            CodeLanguage.JAVA -> listOf("main" to "public static void main(String[] args) {\n    ${'$'}0\n}","for" to "for (int ${'$'}{1:i} = 0; ${'$'}1 < ${'$'}{2:n}; ${'$'}1++) {\n    ${'$'}0\n}")
            else -> listOf("for" to "for (int ${'$'}{1:i} = 0; ${'$'}1 < ${'$'}{2:n}; ++${'$'}1) {\n    ${'$'}0\n}","if" to "if (${'$'}{1:condition}) {\n    ${'$'}0\n}")
        }
    }
}
