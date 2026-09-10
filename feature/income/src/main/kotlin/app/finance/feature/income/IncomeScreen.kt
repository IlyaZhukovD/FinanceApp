package app.finance.feature.income

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.finance.core.model.LedgerSection
import app.finance.feature.ledger.LedgerController
import app.finance.feature.ledger.LedgerScreen

/**
 * Раздел «Доходы» (SPEC.md F2).
 *
 * Поведение идентично «Расходам» при `section = INCOME` и своём наборе категорий (F2.1).
 * Экран намеренно не копируется: используется общий ledger-движок и те же UI-компоненты
 * (F2.2), поэтому здесь только точка входа и идентификатор для боковой навигации.
 */
object IncomeSection {
    /** Идентификатор для боковой навигации. */
    const val NAVIGATION_ID = "income"

    val section: LedgerSection = LedgerSection.INCOME
}

@Composable
fun IncomeScreen(controller: LedgerController, modifier: Modifier = Modifier) {
    LedgerScreen(controller = controller, modifier = modifier)
}
