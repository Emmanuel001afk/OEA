package com.oea.launcher.ui
import android.view.View
import com.oea.launcher.engine.OeaLauncherEngine
class OeaHomeSurfaceController{fun attach(engine:OeaLauncherEngine):View=engine.workspace;fun refresh(engine:OeaLauncherEngine){engine.start()}}
