package dev.mobilecode.ide.data;
import androidx.room.Database;
import androidx.room.RoomDatabase;
@Database(entities={ProjectEntity.class,BreakpointEntity.class,HistoryEntity.class},version=1,exportSchema=false)
public abstract class MetaDatabase extends RoomDatabase { public abstract MetaDao dao(); }
