# Apache POI 相关
-dontwarn org.apache.poi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.commons.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.openxmlformats.**
-dontwarn org.etsi.**
-dontwarn javax.xml.stream.**
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.openxmlformats.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# POI(xmlbeans) 可选依赖缺失类，运行时不使用这些功能，仅抑制 R8 告警
-dontwarn com.github.javaparser.**
-dontwarn com.sun.org.apache.xml.**
-dontwarn java.awt.**
-dontwarn net.sf.saxon.**
-dontwarn org.apache.maven.**
-dontwarn org.apache.tools.ant.**
-dontwarn com.graphbuilder.**
