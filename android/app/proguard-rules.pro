# gomobile bindings: Go calls back into these classes by name via JNI.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
# Our implementations of libbox interfaces are called from Go through JNI.
-keep class com.vpndeck.vpn.** { *; }
