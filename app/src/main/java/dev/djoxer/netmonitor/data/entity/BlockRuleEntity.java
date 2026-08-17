package dev.djoxer.netmonitor.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "block_rules")
public class BlockRuleEntity {

    @PrimaryKey
    @NonNull
    public String packageName;

    public int uid;
    public String appName;
    public boolean permanentlyBlocked;

    public BlockRuleEntity(@NonNull String packageName, int uid, String appName, boolean permanentlyBlocked) {
        this.packageName = packageName;
        this.uid = uid;
        this.appName = appName;
        this.permanentlyBlocked = permanentlyBlocked;
    }
}
