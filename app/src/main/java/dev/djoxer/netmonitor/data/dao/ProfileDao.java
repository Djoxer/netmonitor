package dev.djoxer.netmonitor.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import dev.djoxer.netmonitor.data.entity.ProfileEntity;
import dev.djoxer.netmonitor.data.entity.ProfileRuleEntity;

@Dao
public interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY name ASC")
    List<ProfileEntity> getAllProfiles();

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    ProfileEntity getProfile(long id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertProfile(ProfileEntity profile);

    @Update
    void updateProfile(ProfileEntity profile);

    @Query("DELETE FROM profiles WHERE id = :id")
    void deleteProfile(long id);

    @Query("SELECT * FROM profile_rules WHERE profileId = :profileId")
    List<ProfileRuleEntity> getRulesForProfile(long profileId);

    @Query("SELECT * FROM profile_rules WHERE id = :id LIMIT 1")
    ProfileRuleEntity getRule(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertRule(ProfileRuleEntity rule);

    @Query("DELETE FROM profile_rules WHERE id = :id")
    void deleteRule(String id);

    @Query("DELETE FROM profile_rules WHERE profileId = :profileId")
    void deleteRulesForProfile(long profileId);
}
