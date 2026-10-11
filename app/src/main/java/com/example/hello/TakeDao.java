package com.example.hello;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TakeDao {
    @Query("SELECT * FROM takes ORDER BY timestamp DESC")
    List<TakeEntity> allOrdered();

    @Query("SELECT * FROM takes WHERE id = :id LIMIT 1")
    TakeEntity byId(long id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(TakeEntity e);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<TakeEntity> es);

    @Query("DELETE FROM takes WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM takes WHERE id IN (:ids)")
    void deleteByIds(List<Long> ids);

    @Query("UPDATE takes SET lastPositionMs = :pos WHERE id = :id")
    void updatePosition(long id, long pos);

    @Query("SELECT COUNT(*) FROM takes")
    int count();
}
