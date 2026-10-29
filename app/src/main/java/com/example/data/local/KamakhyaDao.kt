package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KamakhyaDao {

    @Query("SELECT * FROM saved_assets ORDER BY createdAt DESC")
    fun observeAllAssets(): Flow<List<SavedAssetEntity>>

    @Query("SELECT * FROM saved_assets WHERE category = :category ORDER BY createdAt DESC")
    fun observeAssetsByCategory(category: String): Flow<List<SavedAssetEntity>>

    @Query("SELECT * FROM saved_assets WHERE id = :id LIMIT 1")
    suspend fun getAssetById(id: Long): SavedAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: SavedAssetEntity): Long

    @Update
    suspend fun updateAsset(asset: SavedAssetEntity)

    @Query("DELETE FROM saved_assets WHERE id = :id")
    suspend fun deleteAssetById(id: Long)

    @Query("DELETE FROM saved_assets")
    suspend fun deleteAllAssets()

    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeActionLogs(): Flow<List<ActionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionLog(log: ActionLogEntity): Long

    @Query("DELETE FROM action_logs")
    suspend fun clearActionLogs()
}
