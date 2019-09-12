package com.revolut.transfer

import com.revolut.transfer.TransferApplication.transferServer
import com.revolut.transfer.TransferState.*
import com.revolut.transfer.dao.Account
import com.revolut.transfer.service.UuidGenerator
import org.assertj.core.api.Assertions.assertThat
import org.http4k.client.OkHttp
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.format.Jackson
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS

@TestInstance(PER_CLASS)
class EndToEndTest {

    private val client = OkHttp()
    private val server = transferServer(0)
    private val json = Jackson
    private val account1Id = UuidGenerator.nextId()
    private val account2Id = UuidGenerator.nextId()

    @BeforeAll
    fun before() {
        server.start()

        val now = DateTime.now()
        transaction {
            Account.new(account1Id) {
                this.currency = "GBP"
                balance = 10000.00
                createdAt = now
                updatedAt = now
            }
            Account.new(account2Id) {
                this.currency = "GBP"
                balance = 10000.00
                createdAt = now
                updatedAt = now
            }
        }
    }

    @AfterAll
    fun after() {
        server.stop()
    }

    @Test
    fun `should successfully transfer`() {
        val transferRequest = TransferRequest(
            requestId = "test-1",
            sourceAccountId = account1Id,
            targetAccountId = account2Id,
            currency = "GBP",
            amount = 2.00.toBigDecimal()
        )

        val response = client(Request(Method.POST, "http://localhost:${server.port()}/transfer").body(json.asJsonString(transferRequest)))

        assertThat(response.status).isEqualTo(OK)
        val responseJson = json.parse(response.bodyString())
        assertThat(responseJson.get("transferId").asText()).isNotBlank()
        assertThat(responseJson.get("state").asText()).isEqualTo(COMPLETED.name)
    }

    @Test
    fun `should decline transfer for insufficient funds`() {
        val transferRequest = TransferRequest(
            requestId = "test-2",
            sourceAccountId = account1Id,
            targetAccountId = account2Id,
            currency = "GBP",
            amount = 2000000.00.toBigDecimal()
        )

        val response = client(Request(Method.POST, "http://localhost:${server.port()}/transfer").body(json.asJsonString(transferRequest)))

        assertThat(response.status).isEqualTo(OK)
        val responseJson = json.parse(response.bodyString())
        assertThat(responseJson.get("transferId").asText()).isNotBlank()
        assertThat(responseJson.get("state").asText()).isEqualTo(DECLINED.name)
    }

    @Test
    fun `should fail transfer for currency mismatch`() {
        val transferRequest = TransferRequest(
            requestId = "test-3",
            sourceAccountId = account1Id,
            targetAccountId = account2Id,
            currency = "JPY",
            amount = 1.00.toBigDecimal()
        )

        val response = client(Request(Method.POST, "http://localhost:${server.port()}/transfer").body(json.asJsonString(transferRequest)))

        assertThat(response.status).isEqualTo(BAD_REQUEST)
        val responseJson = json.parse(response.bodyString())
        assertThat(responseJson.get("state").asText()).isEqualTo(FAILED.name)
        assertThat(responseJson.get("reason").asText()).isEqualTo("Source account currency mismatch")
    }

    @Test
    fun `should fail for duplicate request id for different transfer`() {
        val transferRequest1 = TransferRequest(
            requestId = "test-4",
            sourceAccountId = account1Id,
            targetAccountId = account2Id,
            currency = "GBP",
            amount = 1.00.toBigDecimal()
        )
        val transferRequest2 = transferRequest1.copy(amount = 2.00.toBigDecimal())

        val response1 = client(Request(Method.POST, "http://localhost:${server.port()}/transfer").body(json.asJsonString(transferRequest1)))
        assertThat(response1.status).isEqualTo(OK)

        val response2 = client(Request(Method.POST, "http://localhost:${server.port()}/transfer").body(json.asJsonString(transferRequest2)))
        assertThat(response2.status).isEqualTo(BAD_REQUEST)
        val responseJson = json.parse(response2.bodyString())
        assertThat(responseJson.get("state").asText()).isEqualTo(FAILED.name)
        assertThat(responseJson.get("reason").asText()).isEqualTo("Duplicate request ID")
    }

    @Test
    fun `should accept duplicate request id for same transfer`() {
        val transferRequest = TransferRequest(
            requestId = "test-5",
            sourceAccountId = account1Id,
            targetAccountId = account2Id,
            currency = "GBP",
            amount = 1.00.toBigDecimal()
        )

        val response1 = client(Request(Method.POST, "http://localhost:${server.port()}/transfer").body(json.asJsonString(transferRequest)))
        assertThat(response1.status).isEqualTo(OK)

        val response2 = client(Request(Method.POST, "http://localhost:${server.port()}/transfer").body(json.asJsonString(transferRequest)))
        assertThat(response2.status).isEqualTo(OK)

        val transferId1 = json.parse(response1.bodyString()).get("transferId").asText()
        val transferId2 = json.parse(response2.bodyString()).get("transferId").asText()
        assertThat(transferId2).isEqualTo(transferId1)
    }

}