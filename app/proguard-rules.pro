# Remove unused dependency code, especially the extended icon collection.
# Preserve stored JSON names, JNI entry points, editor reflection and test-visible APIs.
-dontobfuscate
-dontoptimize
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-keep class dev.mobilecode.** { *; }
# Sora 0.24.6 ships an unreferenced clone bridge to a removed Kotlin ABI.
# Keep the editor/reflection APIs; allow that unused synthetic bridge to be discarded.
-keep class !io.github.rosemoe.sora.util.ShareableData$DefaultImpls,io.github.rosemoe.sora.** { *; }
-keep class com.google.gson.** { *; }
-keep class !androidx.compose.material.icons.**,androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
# Android's Compose/Espresso bridge uses these public APIs from the tested APK.
-keep interface com.google.common.util.concurrent.ListenableFuture { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
