# parental-android — Guard rules (build release không minify; giữ sẵn để sau bật R8)
# Jackson: giữ annotations + reflection
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
# Protocol models serialize qua reflection (Jackson Kotlin module)
-keep class com.parental.android.protocol.** { *; }
