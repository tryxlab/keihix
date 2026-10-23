package com.keihix.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.keihix.app.ui.navigation.KeihiXNavHost
import com.keihix.app.ui.theme.KeihiXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as KeihiXApplication

        // 共有インテント (ACTION_SEND) の受信確認
        handleIncomingIntent(intent)

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
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val uri = (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                ?: intent.clipData?.getItemAt(0)?.uri
            if (uri != null) {
                // Phase 3で実装する画像・PDF解析パイプラインへの受け渡し先
            }
        }
    }
}
