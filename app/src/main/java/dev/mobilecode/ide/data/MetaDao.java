package dev.mobilecode.ide.data;
import androidx.room.*;
import java.util.List;
@Dao public abstract class MetaDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") public abstract List<ProjectEntity> projects();
    @Insert(onConflict=OnConflictStrategy.REPLACE) public abstract void putProject(ProjectEntity project);
    @Query("SELECT * FROM breakpoints WHERE projectId=:id ORDER BY path,line") public abstract List<BreakpointEntity> breakpoints(String id);
    @Query("DELETE FROM breakpoints WHERE projectId=:id") public abstract void clearBreakpoints(String id);
    @Insert(onConflict=OnConflictStrategy.REPLACE) public abstract void putBreakpoints(List<BreakpointEntity> points);
    @Transaction public void replaceBreakpoints(String id,List<BreakpointEntity> points) { clearBreakpoints(id); putBreakpoints(points); }
    @Insert(onConflict=OnConflictStrategy.REPLACE) public abstract void putHistory(HistoryEntity history);
    @Query("UPDATE history SET status='interrupted' WHERE status='running'") public abstract void interruptPreviousSessions();
    @Query("SELECT * FROM history ORDER BY startedAt DESC LIMIT 20") public abstract List<HistoryEntity> history();
    @Query("SELECT id FROM history") public abstract List<String> historyIds();
}
