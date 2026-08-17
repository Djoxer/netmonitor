package dev.djoxer.netmonitor.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.djoxer.netmonitor.data.entity.BlockRuleEntity;

@Dao
public interface BlockRuleDao {

    @Query("SELECT * FROM block_rules")
    List<BlockRuleEntity> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(BlockRuleEntity rule);

    @Query("DELETE FROM block_rules WHERE packageName = :pkg")
    void delete(String pkg);
}
