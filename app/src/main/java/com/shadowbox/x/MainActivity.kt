package com.shadowbox.x

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.shadowbox.x.ui.ShadowApp
import com.shadowbox.x.ui.components.LocalContainer
import com.shadowbox.x.ui.theme.Accent
import com.shadowbox.x.ui.theme.ShadowTheme

class MainActivity : FragmentActivity() {
    private lateinit var container: com.shadowbox.x.di.AppContainer
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as ShadowBoxApp).container
        // FLAG_SECURE: blocks screenshots, screen recording and the recent-apps thumbnail for the whole app.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            val s by container.settings.settings.collectAsState()
            val accent = runCatching { Accent.valueOf(s.accent) }.getOrDefault(Accent.ELECTRIC)
            CompositionLocalProvider(LocalContainer provides container) {
                ShadowTheme(accent) { ShadowApp(container) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        backgroundedAt = SystemClock.elapsedRealtime()
        val v = container.vault
        if (!v.isUnlocked || v.externalFlow) return // system picker / biometric prompt is not "leaving the app"
        val s = container.settings.settings.value
        if (s.lockOnBackground && s.autoLockSeconds == 0) v.lock()
    }

    override fun onStart() {
        super.onStart()
        val v = container.vault
        if (v.externalFlow) { v.externalFlow = false; return }
        if (!v.isUnlocked || backgroundedAt == 0L) return
        val s = container.settings.settings.value
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        if (s.lockOnBackground && away >= s.autoLockSeconds * 1000L) v.lock()
    }
}
