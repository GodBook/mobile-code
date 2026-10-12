package dev.mobilecode.ide.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="history")
public class HistoryEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String projectId = "";
    @NonNull public String status = "";
    public long startedAt;
    public long durationMs;
    public Integer exitCode;
}
