package com.banyawa.sitescanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.banyawa.sitescanner.ui.AppNavHost
import com.banyawa.sitescanner.ui.theme.SiteScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SiteScannerTheme {
                AppNavHost()
            }
        }
    }
}
