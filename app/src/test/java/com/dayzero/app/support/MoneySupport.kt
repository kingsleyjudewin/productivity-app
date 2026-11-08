package com.dayzero.app.support

import com.dayzero.app.data.repo.BudgetRepository
import com.dayzero.app.data.repo.PaymentAppRepository
import com.dayzero.app.data.repo.PaymentIngestor
import com.dayzero.app.data.repo.TransactionRepository
import com.dayzero.app.domain.money.BudgetNotifier
import com.dayzero.app.domain.money.PaymentNotification

class FakeBudgetNotifier : BudgetNotifier {
    val shown = mutableListOf<Pair<Long, String>>()
    var dismissed = 0
    override fun showExceeded(overMinor: Long, currency: String) { shown += overMinor to currency }
    override fun dismissExceeded() { dismissed++ }
}

class MoneyEnv {
    val base = TestEnv()
    val db = base.db
    val clock = base.clock
    val alerts = FakeBudgetNotifier()
    val budgets = BudgetRepository(db, db.budgetDao(), db.transactionDao(), base.settings, alerts, clock)
    val tx = TransactionRepository(db, db.transactionDao(), base.settings, budgets, clock)
    val apps = PaymentAppRepository(db.notificationRuleDao())
    fun ingestor() = PaymentIngestor(db.notificationRuleDao(), db.notificationSeenDao(), tx, clock)
    val ingest = ingestor()

    fun note(text: String, pkg: String = "com.google.android.apps.nbu.paisa.user", key: String = "k1", at: Long = clock.now(), title: String? = null) =
        PaymentNotification(pkg, key, at, title, text)

    fun close() = base.close()
}

const val GPAY = "com.google.android.apps.nbu.paisa.user"
