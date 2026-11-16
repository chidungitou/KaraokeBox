package com.karaokebox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, MusicFolderEntity::class, QueueItemEntity::class, PlayHistoryEntity::class, FavoriteEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun folderDao(): FolderDao
    abstract fun queueDao(): QueueDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        /** v1 → v2: thêm bảng bài yêu thích, GIỮ NGUYÊN toàn bộ dữ liệu cũ (thư viện, danh sách chờ). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favorites` (`filePath` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`filePath`))")
            }
        }

        /**
         * v2 → v3: bảng yêu thích thêm cột clientId (khóa kép clientId + filePath).
         * Yêu thích cũ được GIỮ NGUYÊN với clientId = "" và sẽ được điện thoại kết nối đầu tiên nhận lại.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favorites_new` (`clientId` TEXT NOT NULL, `filePath` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`clientId`, `filePath`))")
                db.execSQL("INSERT INTO `favorites_new` (`clientId`, `filePath`, `addedAt`) SELECT '', `filePath`, `addedAt` FROM `favorites`")
                db.execSQL("DROP TABLE `favorites`")
                db.execSQL("ALTER TABLE `favorites_new` RENAME TO `favorites`")
            }
        }

        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "karaoke.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
