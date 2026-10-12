package dev.mobilecode.ide.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
@Entity(tableName="breakpoints", primaryKeys={"projectId","path","line"})
public class BreakpointEntity {
    @NonNull public String projectId = "";
    @NonNull public String path = "";
    public int line;
}
