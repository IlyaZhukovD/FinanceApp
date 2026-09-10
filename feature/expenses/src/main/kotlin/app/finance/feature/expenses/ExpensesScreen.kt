package app.finance.feature.expenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.finance.core.model.LedgerSection
import app.finance.feature.ledger.LedgerController
import app.finance.feature.ledger.LedgerScreen

/**
 * Раздел «Расходы» — основной экран приложения (SPEC.md F1): при запуске открывается
 * именно он, период — текущий месяц (F1.1).
 *
 * Содержательной логики здесь нет: экран общий с «Доходами» и различается только
 * значением [LedgerSection] (F2.1, F2.2 — копипаста экранов недопустима).
 */
object ExpensesSection {
    /** Идентификатор для боковой навигации. */
    const val NAVIGATION_ID = "expenses"

    val section: LedgerSection = LedgerSection.EXPENSE
}

@Composable
fun ExpensesScreen(controller: LedgerController, modifier: Modifier = Modifier) {
    LedgerScreen(controller = controller, modifier = modifier)
}
