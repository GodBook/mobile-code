package io.github.rosemoe.sora.widget;

import android.content.Context;
import android.graphics.*;
import android.util.SparseArray;
import androidx.annotation.NonNull;
import io.github.rosemoe.sora.event.ClickEvent;
import io.github.rosemoe.sora.event.EditorMotionEvent;
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange;
import io.github.rosemoe.sora.text.*;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.layout.*;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.util.*;

/** Source-preserving folding layout and debugger gutter for the pinned Sora API. */
public final class MobileCodeEditor extends CodeEditor {
    public interface BreakpointClick { void click(int line); }
    private final TreeMap<Integer,Integer> folds=new TreeMap<>();
    private final Set<Integer> collapsed=new HashSet<>();
    private final Paint gutter=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] breakpointLines=new int[0];
    private int[] breakpointStates=new int[0];
    private int executionLine=-1;
    public BreakpointClick breakpointClick;

    public MobileCodeEditor(Context context) {
        super(context);
        setLineNumberMarginLeft(dp(36));
        if(!(layout instanceof FoldLayout)) layout=new FoldLayout(layout);
        subscribeEvent(ClickEvent.class,(event,subscription)-> {
            if(event.getMotionRegion()==EditorMotionEvent.REGION_LINE_NUMBER) {
                int line=event.getCharPosition().line;
                if(event.getCausingEvent().getX()>=dp(18) && event.getCausingEvent().getX()<dp(34) && folds.containsKey(line)) toggleFold(line);
                else if(breakpointClick!=null) breakpointClick.click(line);
                event.intercept();
            }
        });
    }
    private float dp(float value) { return value*getResources().getDisplayMetrics().density; }
    @Override protected void createLayout(boolean clear) {
        if(layout instanceof FoldLayout) layout=((FoldLayout)layout).base;
        super.createLayout(clear);
        if(folds!=null) layout=new FoldLayout(layout);
    }
    public void setFoldRanges(int[] starts,int[] ends) {
        if(starts.length==ends.length && folds.size()==starts.length) {
            boolean same=true;
            for(int i=0;i<starts.length;i++) if(!Objects.equals(folds.get(starts[i]),ends[i])) { same=false; break; }
            if(same) return;
        }
        folds.clear(); for(int i=0;i<Math.min(starts.length,ends.length);i++) if(ends[i]>starts[i]) folds.put(starts[i],ends[i]);
        collapsed.retainAll(folds.keySet()); changed();
    }
    public void toggleFold(int line) {
        if(!folds.containsKey(line)) return;
        if(!collapsed.remove(line)) {
            int cursor=getCursor().getLeftLine();
            if(cursor>line && cursor<=folds.get(line)) super.setSelection(line,getText().getColumnCount(line),true,0);
            collapsed.add(line);
        }
        changed();
    }
    public void unfoldAll() { collapsed.clear(); changed(); }
    public boolean isLineHidden(int line) {
        for(int start:collapsed) if(line>start && line<=folds.getOrDefault(start,start)) return true;
        return false;
    }
    private void changed() { if(layout instanceof FoldLayout) ((FoldLayout)layout).invalidate(); if(getEventHandler()!=null) getEventHandler().scrollBy(0,0); invalidate(); }
    @Override public void setSelection(int line,int column,boolean visible,int cause) {
        if(collapsed!=null && collapsed.removeIf(start->line>start && line<=folds.getOrDefault(start,start))) changed();
        super.setSelection(line,column,visible,cause);
    }
    public void setBreakpointStates(int[] lines,int[] states) { if(Arrays.equals(lines,breakpointLines) && Arrays.equals(states,breakpointStates)) return; breakpointLines=lines; breakpointStates=states; invalidate(); }
    public void setExecutionLine(int line) { if(executionLine==line) return; executionLine=line; invalidate(); }
    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if(layout==null) return;
        gutter.setStrokeWidth(dp(1.7f));
        for(int i=0;i<breakpointLines.length;i++) {
            int line=breakpointLines[i]; if(line<0 || line>=getLineCount() || isLineHidden(line)) continue;
            float y=layout.getCharLayoutOffset(line,0)[0]-getOffsetY()-getRowHeight()/2f;
            if(y < -getRowHeight() || y>getHeight()+getRowHeight()) continue;
            int state=i<breakpointStates.length?breakpointStates[i]:0;
            gutter.setColor(getColorScheme().getColor(EditorColorScheme.PROBLEM_ERROR)); gutter.setStyle(state>0?Paint.Style.FILL:Paint.Style.STROKE);
            canvas.drawCircle(dp(8),y,dp(4.5f),gutter);
            if(state<0) canvas.drawLine(dp(4),y-dp(4),dp(12),y+dp(4),gutter);
        }
        gutter.setStyle(Paint.Style.FILL); gutter.setColor(getColorScheme().getColor(EditorColorScheme.SELECTION_INSERT));
        if(executionLine>=0 && executionLine<getLineCount() && !isLineHidden(executionLine)) {
            float y=layout.getCharLayoutOffset(executionLine,0)[0]-getOffsetY()-getRowHeight()/2f;
            Path arrow=new Path(); arrow.moveTo(dp(16),y-dp(5)); arrow.lineTo(dp(23),y); arrow.lineTo(dp(16),y+dp(5)); arrow.close(); canvas.drawPath(arrow,gutter);
        }
        gutter.setColor(getColorScheme().getColor(EditorColorScheme.LINE_NUMBER));
        for(int start:folds.keySet()) {
            if(start>=getLineCount() || isLineHidden(start)) continue;
            float y=layout.getCharLayoutOffset(start,0)[0]-getOffsetY()-getRowHeight()/2f;
            if(y<0 || y>getHeight()) continue;
            Path shape=new Path();
            if(collapsed.contains(start)) { shape.moveTo(dp(26),y-dp(4)); shape.lineTo(dp(31),y); shape.lineTo(dp(26),y+dp(4)); }
            else { shape.moveTo(dp(24),y-dp(2)); shape.lineTo(dp(32),y-dp(2)); shape.lineTo(dp(28),y+dp(3)); }
            shape.close(); canvas.drawPath(shape,gutter);
        }
    }
    private void shift(int first,int last,int delta) {
        if(delta==0) return;
        TreeMap<Integer,Integer> next=new TreeMap<>(); Set<Integer> closed=new HashSet<>();
        for(var entry:folds.entrySet()) {
            int start=entry.getKey(),end=entry.getValue();
            if(delta<0 && start>first && start<=last) continue;
            int a=start>last?start+delta:start; int b=end>last?end+delta:Math.max(first,end);
            if(delta>0) { a=start>first?start+delta:start; b=end>=first?end+delta:end; }
            if(b>a) { next.put(a,b); if(collapsed.contains(start)) closed.add(a); }
        }
        folds.clear(); folds.putAll(next); collapsed.clear(); collapsed.addAll(closed);
    }
    private final class FoldLayout implements Layout {
        final Layout base;
        private int[] rows=new int[0];
        private int baseCount=-1;
        FoldLayout(Layout base) { this.base=base; }
        void invalidate() { baseCount=-1; }
        private boolean active() { return !collapsed.isEmpty(); }
        private void ensure() {
            if(!active() || baseCount==base.getRowCount()) return;
            baseCount=base.getRowCount(); int[] temporary=new int[baseCount]; int count=0;
            for(int i=0;i<baseCount;i++) if(!isLineHidden(base.getLineNumberForRow(i))) temporary[count++]=i;
            rows=Arrays.copyOf(temporary,count);
        }
        private int real(int visual) { if(!active()) return visual; ensure(); return rows[Math.max(0,Math.min(visual,rows.length-1))]; }
        private int visual(int original) { if(!active()) return original; ensure(); int value=Arrays.binarySearch(rows,original); return value>=0?value:Math.max(0,-value-2); }
        @Override public void destroyLayout() { base.destroyLayout(); }
        @Override public int getLineNumberForRow(int row) { return base.getLineNumberForRow(real(row)); }
        @Override public RowIterator obtainRowIterator(int start,SparseArray<ContentLine> loaded) {
            return new RowIterator() { int index=start; public Row next() { if(!hasNext()) throw new NoSuchElementException(); return getRowAt(index++); } public boolean hasNext() { return index<getRowCount(); } public void reset() { index=start; } };
        }
        @Override public Row getRowAt(int row) { return base.getRowAt(real(row)); }
        @Override public int getLayoutWidth() { return base.getLayoutWidth(); }
        @Override public int getLayoutHeight() { return active()?getRowCount()*getRowHeight():base.getLayoutHeight(); }
        @Override public int getRowCount() { if(!active()) return base.getRowCount(); ensure(); return rows.length; }
        @Override public VisualLocation getVisualPositionForLayoutOffset(float x,float y) {
            if(!active()) return base.getVisualPositionForLayoutOffset(x,y);
            int row=Math.max(0,(int)(y/getRowHeight())); float fraction=y-row*getRowHeight();
            return base.getVisualPositionForLayoutOffset(x,real(row)*getRowHeight()+fraction);
        }
        @Override public float[] getCharLayoutOffset(int line,int column,float[] array) {
            float[] result=base.getCharLayoutOffset(line,column,array);
            if(active()) { int row=Math.max(0,Math.round(result[0]/getRowHeight())-1); result[0]=(visual(row)+1)*getRowHeight(); }
            return result;
        }
        @Override public int getRowCountForLine(int line) { return isLineHidden(line)?0:base.getRowCountForLine(line); }
        private long move(int line,int column,int delta) {
            if(!active()) return delta<0?base.getUpPosition(line,column):base.getDownPosition(line,column);
            int index=getText().getCharIndex(line,column); int row=visual(base.getRowIndexForPosition(index));
            float x=base.getCharLayoutOffset(line,column)[1];
            VisualLocation target=base.getVisualPositionForLayoutOffset(x,(real(row+delta)+.5f)*getRowHeight());
            return IntPair.pack(target.line,target.column);
        }
        @Override public long getUpPosition(int line,int column) { return move(line,column,-1); }
        @Override public long getDownPosition(int line,int column) { return move(line,column,1); }
        @Override public int getRowIndexForPosition(int index) { return visual(base.getRowIndexForPosition(index)); }
        @Override public void invalidateLines(StyleUpdateRange range) { base.invalidateLines(range); invalidate(); }
        @Override public void beforeReplace(Content content) { base.beforeReplace(content); }
        @Override public void afterInsert(Content content,int a,int ac,int b,int bc,CharSequence value) { base.afterInsert(content,a,ac,b,bc,value); shift(a,a,b-a); invalidate(); }
        @Override public void afterDelete(Content content,int a,int ac,int b,int bc,CharSequence value) { base.afterDelete(content,a,ac,b,bc,value); shift(a,b,a-b); invalidate(); }
    }
}
