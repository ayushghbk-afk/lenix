package com.lenix

import android.app.Application
import android.os.Build
import com.lenix.nativebridge.EngineInstaller
import com.lenix.nativebridge.NativeBridge
import com.lenix.nativebridge.NativeSetup

class LenixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeBridge.tryLoad()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: NativeSetup.DEFAULT_ABI
        EngineInstaller.ensureOrInstallEngine(
            filesDir = filesDir,
            abi = abi,
            openAsset = { path ->
                try {
                    assets.open(path)
                } catch (_: Exception) {
                    null
                }
            },
        )
    }
}
