package com.lenix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Desktop screen placeholder. The built-in RFB viewer / SurfaceView lands here in
 * Phase 3; for now the Home flow can navigate to a fake desktop to validate gesture
 * and navigation wiring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Desktop")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1A1D23)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color(0xFF242A36),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(24.dp),
            ) {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    tint = Color(0xFF00C853),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(
                    text = "Openbox desktop preview",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "VNC viewer will render here in Phase 3.",
                    color = Color(0xFFB0B4BC),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onBack,
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    Text("Return to Home")
                }
            }
        }
    }
}
