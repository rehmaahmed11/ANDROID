# Add project specific ProGuard rules here.
# Minification is disabled in app/build.gradle; this file is kept for when it is enabled.
-keep class com.rehma.personas.data.Persona { *; }
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
