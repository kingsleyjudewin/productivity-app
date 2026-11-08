package com.dayzero.app.di

import com.dayzero.app.domain.money.BudgetNotifier
import com.dayzero.app.payments.AndroidBudgetNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MoneyModule {
    @Binds @Singleton abstract fun budgetNotifier(impl: AndroidBudgetNotifier): BudgetNotifier
}
