# Shared R8 rules for the separately installed OEA game apps
# (com.oea.runner, com.oea.arena, com.oea.chess).
#
# These apps are small on the surface — one activity each — but they sit on the
# whole OEA Engine and the games library. R8 removes what they never reach;
# these rules say what must survive and what must keep its name.

# Components come from the manifest; AGP keeps them. Kept explicitly so a
# future manifest change cannot silently drop one.
-keep class com.oea.** extends android.app.Activity
-keep class com.oea.** extends android.app.Service
-keep class com.oea.** extends android.content.BroadcastReceiver
-keep class com.oea.** extends android.content.ContentProvider

# OEA names travel: Game Boost reports activity class names, the engine writes
# game and option ids into preferences, and the network protocol is positional.
# Whatever survives shrinking keeps the name it was compiled with.
-keepnames class com.oea.** { *; }
-keepclassmembernames class com.oea.** { *; }

# Game descriptors are registered by class reference; the registry and the
# match/lobby screens would lose their games if these were renamed away.
-keep class com.oea.games.OeaGames { *; }
-keep class com.oea.engine.core.GameRegistry { *; }

# Enums are read back by name (PlayMode.valueOf) and written into save data.
-keepclassmembers,allowoptimization enum com.oea.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Views built in code only need their constructors.
-keep class com.oea.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# WebRTC is only ever loaded through its own Java API; its JNI entry points and
# reflected observers must stay exactly as they are.
-keep class org.webrtc.** { *; }
-keep class io.github.webrtc.** { *; }

# ZXing is used for QR codes only.
-dontwarn com.google.zxing.**
