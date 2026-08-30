package com.lenix.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lenix.vnc.RfbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Built-in RFB viewer for the Openbox session (Phase 7 / ADR-003).
 *
 * Connects to loopback only. Until Xvnc answers, a status card explains why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(
    vncPort: Int?,
    running: Boolean,
    onBack: () -> Unit,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Waiting for Openbox / Xvnc …") }
    var client by remember { mutableStateOf<RfbClient?>(null) }

    LaunchedEffect(vncPort, running) {
        bitmap = null
        client = null
        if (!running || vncPort == null) {
            status = if (!running) {
                "Start the instance with Auto-start desktop, or START then open Desktop."
            } else {
                "No VNC port — this session is a shell. Enable Auto-start desktop in Settings."
            }
            return@LaunchedEffect
        }
        var lastError = "Connecting to 127.0.0.1:$vncPort"
        status = lastError
        repeat(20) { attempt ->
            try {
                val rfb = RfbClient(port = vncPort)
                withContext(Dispatchers.IO) { rfb.handshake() }
                client = rfb
                status = "Openbox • ${rfb.server.width}×${rfb.server.height} • :$vncPort"
                val frame = Bitmap.createBitmap(rfb.server.width, rfb.server.height, Bitmap.Config.ARGB_8888)
                while (isActive) {
                    withContext(Dispatchers.IO) {
                        rfb.readUpdate()
                        frame.setPixels(rfb.pixels, 0, rfb.server.width, 0, 0, rfb.server.width, rfb.server.height)
                        rfb.requestUpdate(incremental = true)
                    }
                    bitmap = frame
                    delay(80)
                }
                return@LaunchedEffect
            } catch (e: Exception) {
                lastError = e.message ?: "VNC connection failed"
                status = "Retry ${attempt + 1}/20 — $lastError"
                delay(500)
            }
        }
        status = lastError
    }

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
        val frame = bitmap
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Linux desktop",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black)
                    .pointerInput(client) {
                        detectTapGestures { offset ->
                            val rfb = client ?: return@detectTapGestures
                            val x = offset.x.toInt().coerceIn(0, rfb.server.width - 1)
                            val y = offset.y.toInt().coerceIn(0, rfb.server.height - 1)
                            rfb.pointer(1, x, y)
                            rfb.pointer(0, x, y)
                        }
                    },
            )
        } else {
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
                        .background(Color(0xFF242A36), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                ) {
                    Icon(
                        Icons.Default.Computer,
                        contentDescription = null,
                        tint = Color(0xFF00C853),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Text(
                        text = "Openbox desktop",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = status,
                        color = Color(0xFFB0B4BC),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onBack, modifier = Modifier.padding(top = 20.dp)) {
                        Text("Return to Home")
                    }
                }
            }
        }
    }
}
