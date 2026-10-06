package com.oea.launcher.engine

import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.model.OeaAppInfo

class OeaModelBridge(private val store: OeaDataStore){ fun bind(apps:List<OeaAppInfo>){store.setApplicationCount(apps.size);store.markModelBound()} }
