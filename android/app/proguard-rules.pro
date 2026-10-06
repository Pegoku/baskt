# WorkManager 2.7.1 uses Room 2.2.5, which instantiates its generated database by reflection.
# Its consumer rule keeps the class name but R8 full mode also needs the no-argument constructor.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}

# WorkManager also recreates input mergers by the class names stored in its job database.
-keepclassmembers class * extends androidx.work.InputMerger {
    public <init>();
}
