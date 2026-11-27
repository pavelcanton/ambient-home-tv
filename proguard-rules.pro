# R8 shrinking & optimization rules for Ambient Home 3.0.0 (Pure Kotlin)
-keep public class com.will.ambienthome.MainActivity { *; }
-keep public class com.will.ambienthome.SettingsActivity { *; }
-keep public class com.will.ambienthome.LanWebRemoteService { *; }
-keep public class com.will.ambienthome.data.AmbientNotificationListener { *; }
-keep public class com.will.ambienthome.data.SleepTimerReceiver { *; }
-keep class com.will.ambienthome.R$* { *; }

-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontobfuscate
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-dontwarn android.**
-dontwarn java.lang.invoke.**
