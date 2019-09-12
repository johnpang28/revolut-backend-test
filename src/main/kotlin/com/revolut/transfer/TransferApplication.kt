package com.revolut.transfer

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.revolut.transfer.TransferApplication.transferServer
import com.revolut.transfer.TransferState.FAILED
import com.revolut.transfer.dao.Account
import com.revolut.transfer.dao.Accounts
import com.revolut.transfer.dao.Transfers
import com.revolut.transfer.service.*
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.http4k.format.Jackson
import org.http4k.format.Jackson.auto
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

fun main() {
    transferServer(8080).start()

    // The following code is just to provide some seed data for the application. Seed data comprises of 2 accounts.
    val now = DateTime.now()
    transaction {
        Account.new("08d9f37f-b8cf-4eb1-abd0-ddf8e90d7c07") {
            currency = "GBP"
            balance = 10000.00
            createdAt = now
            updatedAt = now
        }
        Account.new("3de7684e-aaa0-473d-982a-a9606d1741aa") {
            currency = "GBP"
            balance = 10000.00
            createdAt = now
            updatedAt = now
        }
    }
}

object TransferApplication {

    private val transferRequestLens = Body.auto<TransferRequest>().toLens()
    private val json = Jackson

    fun transferServer(port: Int): Http4kServer {
        Database.Companion.connect(url = "jdbc:h2:mem:revolut;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver") // TODO Make configurable through properties
        transaction { SchemaUtils.create(Accounts, Transfers) }

        return transferApp(DefaultTransferService(UuidGenerator)).asServer(Jetty(port))
    }

    data class TransferResponse(val transferId: String? = null, val state: String, val reason: String? = null)

    private fun transferApp(transferService: TransferService): HttpHandler = ServerFilters.CatchLensFailure.then(
        routes(
            "/transfer" bind POST to { request ->
                val transferRequest = transferRequestLens(request)
                when (val result = transferService.doTransfer(transferRequest)) {
                    is Right<TransferResult> -> Response(OK).body(json.asJsonString(result.b.toResponse()))
                    is Left<TransferError> ->
                        when(result.a) {
                            SourceAccountNotFoundError -> respondWithBadRequest("Source account not found")
                            TargetAccountNotFoundError -> respondWithBadRequest("Target account not found")
                            SourceAccountCurrencyMismatch -> respondWithBadRequest("Source account currency mismatch")
                            TargetAccountCurrencyMismatch ->respondWithBadRequest("Target account currency mismatch")
                            DuplicateRequestId -> respondWithBadRequest("Duplicate request ID")
                        }
                }
            }
        )
    )

    private fun TransferResult.toResponse(): TransferResponse = TransferResponse(transferId, state.name)

    private fun respondWithBadRequest(reason: String): Response {
        return Response(BAD_REQUEST).body(json.asJsonString(TransferResponse(state = FAILED.name, reason = reason)))
    }
}

