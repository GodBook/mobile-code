package dev.mobilecode.ide.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="projects")
public class ProjectEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String name = "";
    @NonNull public String language = "";
    public long updatedAt;
}
