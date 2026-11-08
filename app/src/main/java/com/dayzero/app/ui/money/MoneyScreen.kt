package com.dayzero.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.domain.MoneyFormat
import com.dayzero.app.domain.TransactionType
import com.dayzero.app.domain.money.BudgetState
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzLabel
import com.dayzero.app.ui.theme.DzPill
import com.dayzero.app.ui.theme.DzStat

@Composable
fun MoneyScreen(onHistory: () -> Unit, onSetup: () -> Unit, vm: MoneyViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var budgetDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    val b = st.status
    val c = st.currency

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Money", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))
        DzLabel("Spent today")
        Text(MoneyFormat.format(b.spentMinor, c), style = MaterialTheme.typography.displayLarge)
        Text(
            b.label(c),
            style = MaterialTheme.typography.titleMedium,
            color = if (b.state == BudgetState.OVER) Dz.Black else Dz.Muted,
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DzStat("Budget", if (b.limitMinor > 0) MoneyFormat.format(b.limitMinor, c) else "—", Modifier.weight(1f))
            DzStat("Remaining", if (b.state == BudgetState.NO_BUDGET) "—" else MoneyFormat.format(b.remainingMinor, c), Modifier.weight(1f))
            DzStat("Payments", "${st.count}", Modifier.weight(1f))
        }
        if (st.unknown > 0) {
            Spacer(Modifier.height(12.dp))
            Text("${st.unknown} payment(s) need your check — open History.", style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
        }
        Spacer(Modifier.height(24.dp))
        DzButton("Add expense", onClick = { addDialog = true })
        Spacer(Modifier.height(8.dp))
        DzButton("Set daily budget", onClick = { budgetDialog = true }, secondary = true)
        Spacer(Modifier.height(8.dp))
        DzButton("History", onClick = onHistory, secondary = true)
        Spacer(Modifier.height(8.dp))
        DzButton("Payment apps", onClick = onSetup, secondary = true)
    }

    if (budgetDialog) BudgetDialog(c, st.status.limitMinor, onDismiss = { budgetDialog = false }) { minor, tomorrow ->
        vm.setBudget(minor, tomorrow); budgetDialog = false
    }
    if (addDialog) TransactionDialog(null, c, onDismiss = { addDialog = false }, onSave = { d, done -> vm.save(null, d) { err -> done(err); if (err == null) addDialog = false } })
}

@Composable
fun BudgetDialog(currency: String, current: Long, onDismiss: () -> Unit, onSave: (Long, Boolean) -> Unit) {
    var text by remember { mutableStateOf(if (current > 0) MoneyFormat.format(current, currency).filter { it.isDigit() || it == '.' } else "") }
    var tomorrow by remember { mutableStateOf(false) }
    val parsed = MoneyFormat.parseMinor(text.ifBlank { "0" })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily budget") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 200, 300, 500).forEach { v -> DzPill("${MoneyFormat.symbol(currency)}$v", text == "$v", { text = "$v" }) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    singleLine = true, label = { Text("Custom") }, isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    prefix = { Text(MoneyFormat.symbol(currency)) },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DzPill("From today", !tomorrow, { tomorrow = false })
                    DzPill("From tomorrow", tomorrow, { tomorrow = true })
                }
                Spacer(Modifier.height(8.dp))
                Text("Past days keep the budget they had. Empty = no budget.", style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { onSave(it, tomorrow) } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun typeWord(t: TransactionType) = when (t) {
    TransactionType.EXPENSE -> "Spent"
    TransactionType.INCOME -> "Received"
    TransactionType.REFUND -> "Refund"
    TransactionType.FAILED -> "Failed"
    TransactionType.PENDING -> "Pending"
    TransactionType.UNKNOWN -> "Unverified"
}
