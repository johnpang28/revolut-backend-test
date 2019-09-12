package com.revolut.transfer.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.revolut.transfer.*
import com.revolut.transfer.TransferState.COMPLETED
import com.revolut.transfer.TransferState.DECLINED
import com.revolut.transfer.dao.Account
import com.revolut.transfer.dao.Accounts
import com.revolut.transfer.dao.Transfer
import com.revolut.transfer.dao.Transfers
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

interface TransferService {
    fun doTransfer(request: TransferRequest): Either<TransferError, TransferResult>
}

class DefaultTransferService : TransferService {

    override fun doTransfer(request: TransferRequest): Either<TransferError, TransferResult> {
        return transaction {

            // Use the database to lock the account record. Using "for update" in the select will give the exclusive lock.
            Account.wrapRows(Accounts.select { Accounts.id eq request.sourceAccountId }.forUpdate()).firstOrNull()?.let { sourceAcc ->
                Account.wrapRows(Accounts.select { Accounts.id eq request.targetAccountId }.forUpdate()).firstOrNull()?.let { targetAcc ->

                    val currencyValidationError = validateCurrency(sourceAcc, targetAcc, request)
                    currencyValidationError?.left()
                        ?: (Transfer.find { Transfers.requestId eq request.requestId }.firstOrNull()?.let { existingTransfer ->

                            // If there is already a transfer with the same request ID, and the request details are the same,
                            // do nothing and return success (for idempotency)
                            if (isSameTransfer(request, existingTransfer)) SuccessfulTransfer.right()
                            else DuplicateRequestId.left()
                        } ?: run {

                            // Check there are sufficent funds. If not, persist the transfer with state of DECLINED
                            if (request.amount.compareTo(sourceAcc.balance.toBigDecimal()) > 0) {
                                request.persist(DECLINED)
                                InsufficientFunds.right()
                            } else {
                                sourceAcc.balance = sourceAcc.balance.toBigDecimal().minus(request.amount).toDouble()
                                targetAcc.balance = targetAcc.balance.toBigDecimal().plus(request.amount).toDouble()
                                request.persist(COMPLETED)
                                SuccessfulTransfer.right()
                            }
                        })
                } ?: TargetAccountNotFoundError.left()
            } ?: SourceAccountNotFoundError.left()
        }
    }

    private fun validateCurrency(source: Account, target: Account, request: TransferRequest): TransferError? {
        return when {
            source.currency != request.currency -> SourceAccountCurrencyMismatch
            target.currency != request.currency -> TargetAccountCurrencyMismatch
            else -> null
        }
    }

    private fun isSameTransfer(transferRequest: TransferRequest, transfer: Transfer): Boolean {
        return with(transferRequest) {
            sourceAccountId == transfer.sourceAccount.value
                    && targetAccountId == transfer.targetAccount.value
                    && currency == transfer.currency
                    && amount == transfer.amount.toBigDecimal()
        }
    }

    private fun TransferRequest.persist(state: TransferState) {
        val request = this
        Transfer.new(UUID.randomUUID().toString()) {
            requestId = request.requestId
            sourceAccount =  EntityID(request.sourceAccountId, Accounts)
            targetAccount = EntityID(request.targetAccountId, Accounts)
            currency = request.currency
            amount = request.amount.toDouble()
            this.state = state.name
            createdAt = DateTime.now()
        }
    }
}