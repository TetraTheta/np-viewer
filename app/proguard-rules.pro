# WebView JavaScript Interface
-keepclassmembers class io.github.tetratheta.novelpiaviewer.MainActivity$ScrollRestoreInterface {
  @android.webkit.JavascriptInterface <methods>;
}
# Preferences
-keep class androidx.preference.** { *; }
# Keep R8 from breaking coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
