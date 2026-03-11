# WebView JavaScript Interface
-keepclassmembers class io.github.tetratheta.npviewer.MainActivity$ScrollRestoreInterface {
  @android.webkit.JavascriptInterface <methods>;
}
# Preferences
-keep class androidx.preference.** { *; }
# Keep R8 from breaking coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
