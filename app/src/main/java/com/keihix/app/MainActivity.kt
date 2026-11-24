package com.keihix.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.navigation.compose.rememberNavController
import com.keihix.app.ui.navigation.KeihiXNavHost
import com.keihix.app.ui.theme.KeihiXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as KeihiXApplication

        // 共有インテント (ACTION_SEND) の受信確認
        handleIncomingIntent(intent, app)

        setContent {
            KeihiXTheme {
                val navController = rememberNavController()
                KeihiXNavHost(
                    navController = navController,
                    app = app
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, application as KeihiXApplication)
    }

    private fun handleIncomingIntent(intent: Intent?, app: KeihiXApplication) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri != null) {
                app.submitSharedUri(uri)
                // 再構成時の重複処理を防ぐため action をクリア
                intent.action = null
            }
        }
    }
}
