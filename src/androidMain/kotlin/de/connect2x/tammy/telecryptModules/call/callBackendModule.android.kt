package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callBackend.UrlLauncher
import org.koin.core.module.Module
import org.koin.dsl.module
import android.content.Context
import android.content.Intent
import android.net.Uri

// Simple Android implementation
class AndroidUrlLauncher(private val context: Context) : UrlLauncher {
    override fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

actual val platformCallBackendModule: Module = module {
    single<UrlLauncher> { AndroidUrlLauncher(get()) }
}
