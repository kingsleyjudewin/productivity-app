package com.dayzero.app.payments

import com.dayzero.app.data.repo.PaymentAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory mirror of the selected payment apps so the listener can reject other apps instantly. */
@Singleton
class PaymentSelectionCache @Inject constructor(repo: PaymentAppRepository) {
    @Volatile private var selected: Set<String> = emptySet()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repo.observeSelected().collect { l -> selected = l.map { it.packageName }.toSet() }
        }
    }

    fun isSelected(pkg: String) = pkg in selected
}
