package com.resqmesh.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.resqmesh.app.graph
import com.resqmesh.app.ui.theme.ResQMeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ResQMeshTheme {
                AppRoot(graph)
            }
        }
    }
}
