package com.lenix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenix.vm.launch.GuestSession
import com.lenix.vm.pty.PtySession
import kotlinx.coroutines.launch

/**
 * PTY-backed terminal. When a [GuestSession] is live, keystrokes go to the guest
 * shell and stdout is rendered as a scrolling buffer (ADR-009). Without a session
 * the screen explains that START must succeed first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    session: GuestSession?,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pty = remember(session) { session?.let { PtySession(it, scope) } }
    DisposableEffect(pty) {
        onDispose { pty?.close() }
    }
    val liveOutput = pty?.output?.collectAsState()
    val output = liveOutput?.value
        ?: "No guest shell is attached.\nPress START on Home so PRoot can spawn /bin/bash, then reopen Terminal."
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val scroll = rememberScrollState()
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (session?.isAlive() == true) "Terminal • pid ${session.pid}" else "Terminal")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0E1117))
                .padding(12.dp),
        ) {
            Text(
                text = output,
                color = Color(0xFFB8E994),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                enabled = pty != null,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val line = input.text
                        input = TextFieldValue("")
                        scope.launch { pty?.send(line + "\n") }
                    },
                ),
                decorationBox = { inner ->
                    Row {
                        Text("$ ", color = Color(0xFF00C853), fontFamily = FontFamily.Monospace)
                        inner()
                    }
                },
            )
        }
    }
}
