package com.lenix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lenix.vm.VmInstance
import com.lenix.vm.VmState
import com.lenix.vm.VmManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceScreen(
    vmManager: VmManager,
    onBack: () -> Unit,
) {
    val instances by vmManager.instances.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instances") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (instances.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No instances yet", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(instances.values.toList(), key = { it.id }) { instance ->
                    InstanceRow(instance)
                }
            }
        }
    }
}

@Composable
private fun InstanceRow(instance: VmInstance) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${instance.distro} ${instance.version} • ${instance.state.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (instance.state) {
                VmState.RUNNING -> Text("Running", color = MaterialTheme.colorScheme.primary)
                VmState.READY -> Text("Ready", color = MaterialTheme.colorScheme.primary)
                VmState.ERROR -> Text("Error", color = MaterialTheme.colorScheme.error)
                else -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
