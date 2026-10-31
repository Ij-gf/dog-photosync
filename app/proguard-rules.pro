# 保留 Room / CameraX / OkHttp 必要信息
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room 生成类
-keep class * extends androidx.room.RoomDatabase { *; }

# Room 生成实现类按名反射加载
-keep class com.autoshot.app.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
