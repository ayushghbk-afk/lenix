package com.lenix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenix.vm.pty.TerminalSnapshot

private val TerminalBackground = Color(0xFF0E1117)
private val TerminalForeground = Color(0xFFB8E994)
private val TerminalPrompt = Color(0xFF00C853)
private val TerminalDim = Color(0xFF7A8699)

/**
 * The terminal window (ADR-009, ADR-024).
 *
 * A pure view of [snapshot]: the transcript lives in the guest session's `PtySession`,
 * so opening and closing this screen never loses output and never attaches a second
 * reader to the guest's stdout. Scrolling follows the tail until the user scrolls up to
 * read something, then stays put instead of being yanked back on every chunk.
 *
 * The shell runs on a pipe, not a PTY, so the window says so rather than pretending to
 * be an interactive terminal: no cursor keys, and no `^C` (there is no line discipline
 * to turn it into SIGINT) — `END SHELL` closes stdin, which is the EOF bash honors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    snapshot: TerminalSnapshot,
    onSend: (String) -> Unit,
    onEndOfInput: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val history = remember { mutableListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val tailThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    var followTail by remember { mutableStateOf(true) }

    // Follow the tail only while the window is already at the bottom, so reading
    // scrollback survives a busy guest.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue - scroll.value }
            .collect { distanceFromBottom -> followTail = distanceFromBottom <= tailThresholdPx }
    }
    LaunchedEffect(snapshot.revision) {
        if (!followTail) return@LaunchedEffect
        // One frame, so maxValue reflects the text that was just appended; reading it
        // immediately returns the previous height and stops one chunk short.
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(snapshot.alive) {
        if (!snapshot.alive) return@LaunchedEffect
        focusRequester.requestFocus()
        keyboard?.show()
    }

    fun recallHistory(delta: Int) {
        if (history.isEmpty()) return
        if (historyIndex < 0 && delta > 0) return
        val next = if (historyIndex < 0) history.lastIndex else historyIndex + delta
        when {
            next < 0 -> historyIndex = 0
            next > history.lastIndex -> {
                historyIndex = -1
                input = TextFieldValue("")
            }

            else -> {
                historyIndex = next
                val line = history[next]
                input = TextFieldValue(text = line, selection = TextRange(line.length))
            }
        }
    }

    fun submit() {
        val line = input.text
        input = TextFieldValue("")
        historyIndex = -1
        if (line.isNotBlank()) {
            history.remove(line)
            history.add(line)
        }
        onSend(line)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Terminal")
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                !snapshot.alive -> "no shell attached"
                                snapshot.pid > 0L -> "running • pid ${snapshot.pid}"
                                else -> "running"
                            },
                            fontSize = 12.sp,
                            color = TerminalDim,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onClear, enabled = snapshot.text.isNotEmpty()) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear scrollback")
                    }
                    TextButton(onClick = onHome) { Text("Home") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TerminalBackground),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(12.dp),
            ) {
                Text(
                    text = snapshot.text,
                    color = TerminalForeground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (snapshot.text.isEmpty()) {
                    Text(
                        text = snapshot.notice ?: EMPTY_TRANSCRIPT,
                        color = TerminalDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            snapshot.notice?.takeIf { snapshot.text.isNotEmpty() }?.let { notice ->
                Text(
                    text = notice,
                    color = TerminalDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { recallHistory(-1) }, enabled = history.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous command")
                }
                IconButton(onClick = { recallHistory(1) }, enabled = history.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next command")
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = snapshot.alive,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$ ",
                                color = TerminalPrompt,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            )
                            inner()
                        }
                    },
                )
                IconButton(onClick = { submit() }, enabled = snapshot.alive) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (snapshot.alive) LINE_MODE_HINT else "",
                    color = TerminalDim,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEndOfInput, enabled = snapshot.alive) {
                    Text("END SHELL")
                }
            }
        }
    }
}

/**
 * One honest line about what a pipe-backed shell can and cannot do, so the window does
 * not invite keystrokes (`^C`, cursor keys) that reach the guest as ordinary bytes.
 */
private const val LINE_MODE_HINT =
    "Line mode: no PTY, so commands run on Send and ^C cannot interrupt — use STOP on Home."

/** Shown in an empty window that *is* attached to a live shell. */
private const val EMPTY_TRANSCRIPT = "Connected to the guest shell.\nType a command and press Send."
