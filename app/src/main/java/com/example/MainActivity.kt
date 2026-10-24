package com.example

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.relay.data.ConfigRepository
import com.example.relay.security.IntegrityGuard
import com.example.ui.ProxyScreen
import com.example.ui.ProxyViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.LocaleHelper

class MainActivity : ComponentActivity() {
    private val viewModel: ProxyViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val language = ConfigRepository(newBase).loadConfig().language
        super.attachBaseContext(LocaleHelper.wrap(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        if (!IntegrityGuard.isEnvironmentTrusted(this, BuildConfig.DEBUG)) {
            Toast.makeText(this, R.string.integrity_failed, Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                finishAffinity()
                Process.killProcess(Process.myPid())
            }, 1200)
            return
        }

        enableEdgeToEdge()
        setContent {
            val config by viewModel.config.collectAsState()
            MyApplicationTheme(themeMode = config.themeMode) {
                ProxyScreen(viewModel = viewModel)
            }
        }
    }
}
