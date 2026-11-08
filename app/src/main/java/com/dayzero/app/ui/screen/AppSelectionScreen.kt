package com.dayzero.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.domain.usage.UsageFormat
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzCorner
import com.dayzero.app.ui.theme.DzPill

@Composable
fun AppSelectionScreen(onBack: () -> Unit, vm: AppSelectionViewModel = hiltViewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dz.Black) }
            Text("Choose apps", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it }, singleLine = true,
            placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        val apps = list
        when {
            apps == null -> Text("Loading…", color = Dz.Muted, style = MaterialTheme.typography.bodyLarge)
            apps.isEmpty() -> Text("No apps found.", color = Dz.Muted, style = MaterialTheme.typography.bodyLarge)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps, key = { it.app.pkg }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(DzCorner).background(Dz.Surface).clickable { vm.toggle(a) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(a.app.pkg, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            val sub = when {
                                a.selected -> "Limit ${UsageFormat.minutesOf(a.limitMin)}"
                                a.app.suggested -> "Suggested"
                                else -> null
                            }
                            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
                        }
                        DzPill(if (a.selected) "Added" else "Add", a.selected, { vm.toggle(a) })
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
