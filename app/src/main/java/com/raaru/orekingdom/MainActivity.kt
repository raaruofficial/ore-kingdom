package com.raaru.orekingdom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.forum.ForumScreen
import com.raaru.orekingdom.ui.theme.OreKingdomTheme

/**
 * Single-activity app. Ore Kingdom is the forum: one WebView screen, native
 * only around the edges (assets, backend bridge, wallet intents, errors).
 * There is no native menu or splash funnel — the first frame is the forum.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OreKingdomTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ForumScreen()
                }
            }
        }
    }
}
