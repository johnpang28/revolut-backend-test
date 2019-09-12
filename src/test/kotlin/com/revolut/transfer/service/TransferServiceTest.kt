package com.revolut.transfer.service

import arrow.core.Tuple2
import arrow.core.left
import arrow.core.right
import com.revolut.transfer.InsufficientFunds
import com.revolut.transfer.SuccessfulTransfer
import com.revolut.transfer.TransferRequest
import com.revolut.transfer.TransferState.COMPLETED
import com.revolut.transfer.TransferState.DECLINED
import com.revolut.transfer.dao.Account
import com.revolut.transfer.dao.Accounts
import com.revolut.transfer.dao.Transfer
import com.revolut.transfer.dao.Transfers
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import java.util.*

@TestInstance(PER_CLASS)
internal class TransferServiceTest {

    private val transferService = DefaultTransferService()

    @BeforeAll
    fun dbSetup() {
        Database.Companion.connect(url = "jdbc:h2:mem:revolut;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")

        // Create the DB tables
        transaction {
            SchemaUtils.create(Accounts, Transfers)
        }
    }

    @Test
    fun `should transfer successfully`() {
        val (account1, account2) = transaction {
            Tuple2(
                newAccount(756.45),
                newAccount(54.45)
            )
        }

        val amount = 1.23
        val transferRequest = TransferRequest("request-1", account1.id.value, account2.id.value, "GBP", amount.toBigDecimal())
        val result = transferService.doTransfer(transferRequest)

        assertThat(result).isEqualTo(SuccessfulTransfer.right())

        transaction {
            assertThat(Account.findById(account1.id)!!.balance).isEqualTo(755.22)
            assertThat(Account.findById(account2.id)!!.balance).isEqualTo(55.68)

            val transfer = Transfer.find { Transfers.requestId eq transferRequest.requestId }.first()
            assertThat(transfer.sourceAccount).isEqualTo(account1.id)
            assertThat(transfer.targetAccount).isEqualTo(account2.id)
            assertThat(transfer.amount).isEqualTo(amount)
            assertThat(transfer.currency).isEqualTo("GBP")
            assertThat(transfer.state).isEqualTo(COMPLETED.name)
            assertThat(transfer.createdAt).isLessThanOrEqualTo(DateTime.now())
        }
    }

    @Test
    fun `should fail transfer when there are insufficient funds`() {
        val (account1, account2) = transaction {
            Tuple2(
                newAccount(10.00),
                newAccount(0.00)
            )
        }

        val amount = 20.00
        val transferRequest = TransferRequest("request-2", account1.id.value, account2.id.value, "GBP", amount.toBigDecimal())
        val result = transferService.doTransfer(transferRequest)

        assertThat(result).isEqualTo(InsufficientFunds.right())

        transaction {
            assertThat(Account.findById(account1.id)!!.balance).isEqualTo(10.00)
            assertThat(Account.findById(account2.id)!!.balance).isEqualTo(0.00)

            val transfer = Transfer.find { Transfers.requestId eq transferRequest.requestId }.first()
            assertThat(transfer.sourceAccount).isEqualTo(account1.id)
            assertThat(transfer.targetAccount).isEqualTo(account2.id)
            assertThat(transfer.amount).isEqualTo(amount)
            assertThat(transfer.state).isEqualTo(DECLINED.name)
        }
    }

    @Test
    fun `should fail transfer when source account not found`() {
        val badSourceAccountId = "i-dont-exist"
        val targetAccount = transaction { newAccount(9.99) }

        val transferRequest = TransferRequest("request-3", badSourceAccountId, targetAccount.id.value, "GBP", 5.00.toBigDecimal())
        val result = transferService.doTransfer(transferRequest)

        assertThat(result).isEqualTo(SourceAccountNotFoundError.left())

        transaction {
            assertThat(Account.findById(targetAccount.id)!!.balance).isEqualTo(9.99)
            assertThat(Transfer.find { Transfers.requestId eq transferRequest.requestId }).isEmpty()
        }
    }

    @Test
    fun `should fail transfer when target account not found`() {
        val sourceAccount = transaction { newAccount(4535.34) }
        val badTargetAccountId = "i-dont-exist"

        val transferRequest = TransferRequest("request-4", sourceAccount.id.value, badTargetAccountId, "GBP", 15.00.toBigDecimal())
        val result = transferService.doTransfer(transferRequest)

        assertThat(result).isEqualTo(TargetAccountNotFoundError.left())

        transaction {
            assertThat(Account.findById(sourceAccount.id)!!.balance).isEqualTo(4535.34)
            assertThat(Transfer.find { Transfers.requestId eq transferRequest.requestId }).isEmpty()
        }
    }

    @Test
    fun `should fail transfer when source account currency is not same as transfer currency`() {
        val (account1, account2) = transaction {
            Tuple2(
                newAccount(10.00, "JPY"),
                newAccount(11.00)
            )
        }

        val transferRequest = TransferRequest("request-5", account1.id.value, account2.id.value, "GBP", 1.50.toBigDecimal())
        val result = transferService.doTransfer(transferRequest)

        assertThat(result).isEqualTo(SourceAccountCurrencyMismatch.left())

        transaction {
            assertThat(Account.findById(account1.id)!!.balance).isEqualTo(10.00)
            assertThat(Account.findById(account2.id)!!.balance).isEqualTo(11.00)
            assertThat(Transfer.find { Transfers.requestId eq transferRequest.requestId }).isEmpty()
        }
    }

    @Test
    fun `should fail transfer when target account currency is not same as transfer currency`() {
        val (account1, account2) = transaction {
            Tuple2(
                newAccount(12.00),
                newAccount(13.00,"EUR")
            )
        }

        val transferRequest = TransferRequest("request-6", account1.id.value, account2.id.value, "GBP", 1.50.toBigDecimal())
        val result = transferService.doTransfer(transferRequest)

        assertThat(result).isEqualTo(TargetAccountCurrencyMismatch.left())

        transaction {
            assertThat(Account.findById(account1.id)!!.balance).isEqualTo(12.00)
            assertThat(Account.findById(account2.id)!!.balance).isEqualTo(13.00)
            assertThat(Transfer.find { Transfers.requestId eq transferRequest.requestId }).isEmpty()
        }
    }

    @Test
    fun `should accept duplicate transfer request`() {
        val (account1, account2) = transaction {
            Tuple2(
                newAccount(1000.00),
                newAccount(800.00)
            )
        }

        val transferRequest = TransferRequest("request-7", account1.id.value, account2.id.value, "GBP", 50.00.toBigDecimal())
        val result1 = transferService.doTransfer(transferRequest)
        assertThat(result1).isEqualTo(SuccessfulTransfer.right())

        // do the same transfer again (same requestId)
        val result2 = transferService.doTransfer(transferRequest)
        assertThat(result2).isEqualTo(SuccessfulTransfer.right())

        transaction {
            assertThat(Account.findById(account1.id)!!.balance).isEqualTo(950.00)
            assertThat(Account.findById(account2.id)!!.balance).isEqualTo(850.00)
            assertThat(Transfer.find { Transfers.requestId eq transferRequest.requestId }).hasSize(1)
        }
    }

    @Test
    fun `should fail on duplicate transfer request ID when transfer payload different`() {
        val (account1, account2) = transaction {
            Tuple2(
                newAccount(500.00),
                newAccount(500.00)
            )
        }

        val transferRequest1 = TransferRequest("request-8", account1.id.value, account2.id.value, "GBP", 50.00.toBigDecimal())
        val result1 = transferService.doTransfer(transferRequest1)
        assertThat(result1).isEqualTo(SuccessfulTransfer.right())

        val transferRequest2 = TransferRequest("request-8", account1.id.value, account2.id.value, "GBP", 75.00.toBigDecimal()) // same request ID, different amount
        val result2 = transferService.doTransfer(transferRequest2)
        assertThat(result2).isEqualTo(DuplicateRequestId.left())

        transaction {
            assertThat(Account.findById(account1.id)!!.balance).isEqualTo(450.00)
            assertThat(Account.findById(account2.id)!!.balance).isEqualTo(550.00)
            assertThat(Transfer.find { Transfers.requestId eq transferRequest1.requestId }).hasSize(1)
        }
    }

    private fun newAccount(startingBalance: Double, currency: String = "GBP"): Account {
        val now = DateTime.now()
        return Account.new(UUID.randomUUID().toString()) {
            this.currency = currency
            balance = startingBalance
            createdAt = now
            updatedAt = now
        }
    }
}