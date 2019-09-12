package com.revolut.transfer

import com.revolut.transfer.TransferState.COMPLETED
import com.revolut.transfer.TransferState.DECLINED
import java.math.BigDecimal

data class TransferRequest(val requestId: String, val sourceAccountId: String, val targetAccountId: String, val currency: String, val amount: BigDecimal)

enum class TransferState { COMPLETED, DECLINED }

sealed class TransferResult(val state: TransferState)
object SuccessfulTransfer: TransferResult(COMPLETED)
object InsufficientFunds: TransferResult(DECLINED)