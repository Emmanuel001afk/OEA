# OEA Launcher R8 rules.
#
# The launcher is the biggest module in the project by a wide margin, so this
# is where R8 earns its keep. The rules are deliberately conservative: names
# are pinned, but shrinking and optimisation are left on.

# Components are kept from the manifest automatically; listed here so a manifest
# edit can never quietly drop one (a missing Service is invisible until a
# feature stops working).
-keep class com.oea.** extends android.app.Activity
-keep class com.oea.** extends android.app.Service
-keep class com.oea.** extends android.content.BroadcastReceiver
-keep class com.oea.** extends android.content.ContentProvider

# OEA puts class and preference names on the wire and on disk:
#  - Game Boost records the foreground activity's class name and compares it
#    against the game screens (see OeaGameBoostService.isOeaGameScreen);
#  - mini-app ids, game ids, themes, folder and widget state are stored as
#    strings and matched against class or id names;
#  - the fault log names the screen that failed.
# So anything that survives keeps its original name. Shrinking still applies.
-keepnames class com.oea.** { *; }
-keepclassmembernames class com.oea.** { *; }

# Reflection on platform classes: the assistant reaches StatusBarManager by
# name to expand notifications and toggle settings.
-keep class com.oea.launcher.assistant.OeaAssistant { *; }

# The ADB wireless-pairing stack is constant-time crypto (Ed25519, SPAKE2,
# HKDF, a TLS 1.3 client). Optimisation passes that restructure it are not
# worth the risk for the bytes they would save.
-keep class com.oea.launcher.privilege.adb.** { *; }

-keepclassmembers,allowoptimization enum com.oea.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class com.oea.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# The engine and the games are shared with the standalone apps and drive a
# positional network protocol; keep their shapes.
-keep class com.oea.engine.core.GameRegistry { *; }
-keep class com.oea.games.OeaGames { *; }

-keep class org.webrtc.** { *; }
-keep class io.github.webrtc.** { *; }
-dontwarn com.google.zxing.**
