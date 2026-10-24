# ==============================================================================
# Maximum Security & Obfuscation ProGuard / R8 Rules
# Protects against reverse engineering, decompilers (JADX, APKTool, Ghidra),
# strips symbols, line numbers, and flattens package structure.
# ==============================================================================

# Flatten and repackage all internal classes into root to destroy package hierarchy
-repackageclasses ''
-allowaccessmodification
-overloadaggressively

# Remove source file names and line number tables
-renamesourcefileattribute ''
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# Strip all debug logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# Android Framework Entry Points (Mandatory to keep)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep Services explicitly
-keep class com.example.relay.service.RelayProxyService { *; }
-keep class com.example.relay.service.RelayVpnService { *; }
-keep class com.example.relay.service.VpnProtector { *; }
-keep class com.example.relay.service.CoverTrafficManager { *; }
-keep class com.example.relay.crypto.V6Packet { *; }

# Keep ViewModel constructors
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Jetpack Compose essentials
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Keep Data Models and JSON fields intact for runtime serialization
-keepclassmembers class com.example.relay.data.DecodedConfig { *; }
-keepclassmembers class com.example.relay.data.ProxyConfig { *; }
-keepclassmembers class com.example.relay.data.ProxyStats { *; }
-keepclassmembers class com.example.relay.data.LogEntry { *; }

# Coroutines and standard libraries
-dontwarn kotlinx.coroutines.**
-dontwarn java.lang.invoke.**

# QR scanner classes referenced from XML layout and manifest
-keep class com.example.qr.** { *; }
