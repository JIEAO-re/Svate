# Project-specific R8/ProGuard rules for the release build
# (debug builds are not minified, see app/build.gradle.kts).
#
# Rules NOT needed here, and why:
# - Manifest components (MainActivity, AgentAccessibilityService, the
#   mediaProjection foreground services, and the overlay services): aapt2
#   emits keep rules for every class referenced from AndroidManifest.xml,
#   so R8 keeps them automatically.
# - org.json (JSONObject/JSONArray): part of the Android framework boot
#   classpath; R8 never processes or renames framework classes.
# - Room: the only reflective entry point is Room.databaseBuilder loading
#   the KSP-generated AppDatabase_Impl; room-runtime ships consumer rules
#   that keep RoomDatabase subclasses, and the generated DAO/entity code is
#   reached through direct references.
# - kotlinx-coroutines: the artifact ships consumer rules for its
#   ServiceLoader/MainDispatcher internals.
# - Jetpack Compose: covered by the Compose artifacts' consumer rules plus
#   the AGP default rules; all composables are reached via direct calls.
# - ViewModel reflection (ViewModelProvider / viewModel() instantiating
#   MainViewModel): lifecycle-viewmodel ships consumer rules keeping the
#   <init>(android.app.Application) constructor of AndroidViewModel
#   subclasses.
# - Enum values()/valueOf() in general: the default
#   proguard-android-optimize.txt already keeps them for all enums.

# Keep file names and line numbers so release crash stack traces remain
# mappable; rename the source file attribute to avoid leaking original paths.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Agent enums are resolved from server-provided strings via Enum.valueOf
# (ActionIntent/RiskLevel in CloudDecisionClient, TaskMode/HomeworkPolicy in
# AgentState, SCROLL_* in AccessibilityMotor). Keeping the constant fields
# pins their runtime names so dynamic name lookups can never break, even if
# R8's enum optimizations change; this also intentionally disables enum
# unboxing for these classes.
-keepclassmembers enum com.immersive.ui.agent.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Shizuku privileged UserService is instantiated reflectively by Shizuku in a separate
# process; its AIDL Stub and both constructors (no-arg + Context) must survive R8. A
# stable UserServiceArgs .tag() is set in code so renaming cannot change service identity.
-keep class com.immersive.shizuku.IShizukuUserService { *; }
-keep class com.immersive.shizuku.IShizukuUserService$Stub { *; }
-keep class com.immersive.ui.agent.shizuku.ShizukuUserService { *; }
-keepclassmembers class com.immersive.ui.agent.shizuku.ShizukuUserService {
    <init>(...);
}
# The Shizuku api/provider artifacts ship no consumer rules; keep their surface.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
