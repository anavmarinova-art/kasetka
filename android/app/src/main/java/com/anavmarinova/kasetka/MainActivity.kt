package com.anavmarinova.kasetka

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** True in tent / laptop posture (half-folded). */
    val halfOpened = MutableStateFlow(false)
    val visible = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        MediaHub.connect(this)
        handle(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        halfOpened.value = info.displayFeatures.filterIsInstance<FoldingFeature>()
                            .any { it.state == FoldingFeature.State.HALF_OPENED }
                    }
            }
        }
        setContent { KasetkaApp(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        visible.value = true
        MediaHub.connect(this)
        Ytm.connect(this)
    }

    override fun onStop() {
        super.onStop()
        visible.value = false
        Sfx.setHiss(false)
    }

    private fun handle(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { Store.pendingShare.value = it }
            ACTION_PLAY_TAPE -> i.data?.lastPathSegment?.let { Store.pendingPlay.value = it }
        }
    }

    companion object {
        const val ACTION_PLAY_TAPE = "com.anavmarinova.kasetka.PLAY_TAPE"
    }
}
