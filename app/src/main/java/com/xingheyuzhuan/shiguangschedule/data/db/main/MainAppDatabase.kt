package com.xingheyuzhuan.shiguangschedule.data.db.main

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

@Database(
    entities = [
        CourseTable::class,
        Course::class,
        CourseWeek::class,
        TimeSlot::class,
        CourseTableConfig::class,
        GradeEntity::class,
        ExamEntity::class
    ],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = MainAppDatabase.RemoveAppSettingsSpec::class),
        AutoMigration(from = 5, to = 6)
    ],
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MainAppDatabase : RoomDatabase() {

    @androidx.room.DeleteTable(tableName = "app_settings")
    class RemoveAppSettingsSpec : AutoMigrationSpec

    abstract fun courseTableDao(): CourseTableDao
    abstract fun courseDao(): CourseDao
    abstract fun courseWeekDao(): CourseWeekDao
    abstract fun timeSlotDao(): TimeSlotDao
    abstract fun courseTableConfigDao(): CourseTableConfigDao
    abstract fun gradeDao(): GradeDao
    abstract fun examDao(): ExamDao

    companion object {
        @Volatile
        private var INSTANCE: MainAppDatabase? = null

        private val _isInitialized = MutableStateFlow(false)
        val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

        fun getDatabase(context: Context): MainAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MainAppDatabase::class.java,
                    "main_app_database"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // INSTANCE 此时为 null（build() 尚未返回），
                            // 默认数据插入推迟到 INSTANCE = instance 之后执行
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            _isInitialized.value = true
                        }
                    })
                    .build()
                INSTANCE = instance

                // 首次创建：同步插入默认数据（此时 INSTANCE 已可用）
                runBlocking(Dispatchers.IO) {
                    if (instance.courseTableDao().getFirstTableOnce() == null) {
                        val tableId = java.util.UUID.randomUUID().toString()

                        instance.courseTableDao().insert(
                            CourseTable(id = tableId, name = "我的课表", createdAt = System.currentTimeMillis())
                        )
                        instance.courseTableConfigDao().insertOrUpdate(
                            CourseTableConfig(
                                courseTableId = tableId, showWeekends = false,
                                semesterTotalWeeks = 20, defaultClassDuration = 45,
                                defaultBreakDuration = 10, firstDayOfWeek = 1
                            )
                        )
                        instance.timeSlotDao().insertAll(listOf(
                            TimeSlot(number = 1, startTime = "08:30", endTime = "09:10", courseTableId = tableId),
                            TimeSlot(number = 2, startTime = "09:20", endTime = "10:00", courseTableId = tableId),
                            TimeSlot(number = 3, startTime = "10:20", endTime = "11:00", courseTableId = tableId),
                            TimeSlot(number = 4, startTime = "11:10", endTime = "11:50", courseTableId = tableId),
                            TimeSlot(number = 5, startTime = "14:30", endTime = "15:10", courseTableId = tableId),
                            TimeSlot(number = 6, startTime = "15:20", endTime = "16:00", courseTableId = tableId),
                            TimeSlot(number = 7, startTime = "16:10", endTime = "16:50", courseTableId = tableId),
                            TimeSlot(number = 8, startTime = "17:00", endTime = "17:40", courseTableId = tableId),
                            TimeSlot(number = 9, startTime = "19:00", endTime = "19:40", courseTableId = tableId),
                            TimeSlot(number = 10, startTime = "19:50", endTime = "20:30", courseTableId = tableId),
                            TimeSlot(number = 11, startTime = "20:40", endTime = "21:20", courseTableId = tableId)
                        ))
                        println("数据库初始化数据已完成写入")
                    }
                }

                instance
            }
        }
    }
}
