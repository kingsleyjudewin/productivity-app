package com.dayzero.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.data.local.TransactionEntity
import com.dayzero.app.data.repo.TransactionDraft
import com.dayzero.app.domain.MoneyFormat
import com.dayzero.app.domain.TransactionSource
import com.dayzero.app.domain.TransactionType
import com.dayzero.app.domain.money.TxCategory
import com.dayzero.app.domain.money.TxFilter
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzPill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
fun MoneyHistoryScreen(onBack: () -> Unit, vm: MoneyViewModel = hiltViewModel()) {
    val list by vm.history.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val currency by vm.currency.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dz.Black) }
            Text("History", style = MaterialTheme.typography.headlineMedium)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TxFilter.entries.forEach { f -> DzPill(f.label, f == filter, { vm.filter.value = f }) }
        }
        if (list.isEmpty()) Text("No transactions here.", color = Dz.Muted, modifier = Modifier.padding(top = 24.dp))
        LazyColumn {
            items(list, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.merchant ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                        val src = if (t.source == TransactionSource.AUTO) "auto" else "manual"
                        Text(
                            "${typeWord(t.type)} · $src · ${TxCategory.fromName(t.category)?.label ?: "Uncategorised"} · " +
                                fmt.format(Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.bodyMedium, color = Dz.Muted,
                        )
                    }
                    val sign = when (t.type) { TransactionType.EXPENSE -> "−"; TransactionType.INCOME, TransactionType.REFUND -> "+"; else -> "" }
                    Text(if (t.amountMinor > 0) sign + MoneyFormat.format(t.amountMinor, currency) else "?", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    editing?.let { t ->
        TransactionDialog(t, currency, onDismiss = { editing = null },
            onSave = { d, done -> vm.save(t.id, d) { err -> done(err); if (err == null) editing = null } },
            onDelete = { vm.delete(t.id); editing = null })
    }
}

/** Add (t == null) or edit. Auto-detected transactions can be corrected and categorised here too. */
@Composable
fun TransactionDialog(
    t: TransactionEntity?, currency: String, onDismiss: () -> Unit,
    onSave: (TransactionDraft, (String?) -> Unit) -> Unit, onDelete: (() -> Unit)? = null,
) {
    var amount by remember { mutableStateOf(t?.amountMinor?.takeIf { it > 0 }?.let { MoneyFormat.format(it, currency).filter { c -> c.isDigit() || c == '.' } } ?: "") }
    var merchant by remember { mutableStateOf(t?.merchant ?: "") }
    var cat by remember { mutableStateOf(TxCategory.fromName(t?.category)) }
    var type by remember { mutableStateOf(t?.type ?: TransactionType.EXPENSE) }
    var error by remember { mutableStateOf<String?>(null) }
    val ts = t?.timestamp ?: System.currentTimeMillis()
    val minor = MoneyFormat.parseMinor(amount.ifBlank { "0" })
    val types = listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.REFUND, TransactionType.FAILED, TransactionType.UNKNOWN)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (t == null) "Add transaction" else "Edit transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, label = { Text("Amount") },
                    prefix = { Text(MoneyFormat.symbol(currency)) }, isError = minor == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(merchant, { merchant = it.take(60) }, singleLine = true, label = { Text("Merchant / description") })
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.filter { t != null || it == TransactionType.EXPENSE || it == TransactionType.INCOME }
                        .forEach { DzPill(typeWord(it), type == it, { type = it }) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TxCategory.entries.forEach { DzPill(it.label, cat == it, { cat = if (cat == it) null else it }) }
                }
                if (t?.originalAmountMinor != null) Text("Detected: ${MoneyFormat.format(t.originalAmountMinor, currency)}", color = Dz.Muted, style = MaterialTheme.typography.bodyMedium)
                error?.let { Text(it, color = Dz.Black) }
            }
        },
        confirmButton = {
            TextButton(enabled = minor != null && minor > 0, onClick = {
                onSave(TransactionDraft(minor!!, merchant, cat, ts, type)) { error = it }
            }) { Text("Save") }
        },
        dismissButton = {
            Row { onDelete?.let { TextButton(onClick = it) { Text("Delete") } }; TextButton(onClick = onDismiss) { Text("Cancel") } }
        },
    )
}
