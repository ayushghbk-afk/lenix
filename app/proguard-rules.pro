# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep JSON / data classes
-keep class com.lenix.installer.** { *; }
-keep class com.lenix.vm.** { *; }
-keep class com.lenix.data.** { *; }
