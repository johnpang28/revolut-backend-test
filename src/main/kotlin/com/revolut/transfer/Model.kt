package com.revolut.transfer

import com.revolut.transfer.TransferState.COMPLETED
import com.revolut.transfer.TransferState.DECLINED
import java.math.BigDecimal

data class TransferRequest(val requestId: String, val sourceAccountId: String, val targetAccountId: String, val currency: String, val amount: BigDecimal)

enum class TransferState { COMPLETED, DECLINED, FAILED }

interface TransferResult {
    val transferId: String
    val state: TransferState
}

data class SuccessfulTransfer(override val transferId: String): TransferResult {
    override val state = COMPLETED
}

data class InsufficientFunds(override val transferId: String): TransferResult {
    override val state = DECLINED
}