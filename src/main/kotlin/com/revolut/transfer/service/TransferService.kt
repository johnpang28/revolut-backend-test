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

interface TransferService {
    fun doTransfer(request: TransferRequest): Either<TransferError, TransferResult>
}

class DefaultTransferService(private val idGenerator: IdGenerator<String>) : TransferService {

    override fun doTransfer(request: TransferRequest): Either<TransferError, TransferResult> {
        return transaction {

            // Use the database to lock the account record. Using "for update" in the select will give the exclusive lock.
            Account.wrapRows(Accounts.select { Accounts.id eq request.sourceAccountId }.forUpdate()).firstOrNull()?.let { sourceAcc ->
                Account.wrapRows(Accounts.select { Accounts.id eq request.targetAccountId }.forUpdate()).firstOrNull()?.let { targetAcc ->

                    val currencyValidationError = validateCurrency(sourceAcc, targetAcc, request)
                    currencyValidationError?.left()
                        ?: (Transfer.find { Transfers.requestId eq request.requestId }.firstOrNull()?.let { existingTransfer ->

                            // If there is already a transfer with the same request ID, and the request details are the same,
                            // do nothing and return same response as previous request (for idempotency)
                            if (isSameTransfer(request, existingTransfer)) when (existingTransfer.state) {
                                DECLINED.name ->  InsufficientFunds(existingTransfer.id.value).right()
                                else -> SuccessfulTransfer(existingTransfer.id.value).right()
                            }

                            else DuplicateRequestId.left()
                        } ?: run {

                            // Check there are sufficient funds. If not, persist the transfer with state of DECLINED
                            if (request.amount.compareTo(sourceAcc.balance.toBigDecimal()) > 0) {
                                val transfer = request.persist(DECLINED)
                                InsufficientFunds(transfer.id.value).right()
                            } else {
                                val now = DateTime.now()
                                sourceAcc.balance = sourceAcc.balance.toBigDecimal().minus(request.amount).toDouble()
                                sourceAcc.updatedAt = now
                                targetAcc.balance = targetAcc.balance.toBigDecimal().plus(request.amount).toDouble()
                                targetAcc.updatedAt = now
                                val transfer = request.persist(COMPLETED)
                                SuccessfulTransfer(transfer.id.value).right()
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
                    && amount.compareTo(transfer.amount.toBigDecimal()) == 0
        }
    }

    private fun TransferRequest.persist(state: TransferState): Transfer {
        val request = this
        return Transfer.new(idGenerator.nextId()) {
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