package com.revolut.transfer.dao

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IdTable

object Accounts : IdTable<String>("account") {
    override val id = varchar("account_id", 36).primaryKey().entityId()
    val currency = varchar("currency", 3)
    val balance = double("balance")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

class Account(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, Account>(Accounts, Account::class.java)

    var currency by Accounts.currency
    var balance by Accounts.balance
    var createdAt by Accounts.createdAt
    var updatedAt by Accounts.updatedAt
}

object Transfers : IdTable<String>("transfer") {
    override val id = varchar("transfer_id", 36).primaryKey().entityId()
    val requestId = varchar("request_id", 40).uniqueIndex()
    val sourceAccount = reference("source_account_id", Accounts)
    val targetAccount = reference("target_account_id", Accounts)
    val currency = varchar("currency", 3)
    val amount = double("amount")
    val state = varchar("state", 16)
    val createdAt = datetime("created_at")
}

class Transfer(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, Transfer>(Transfers, Transfer::class.java)

    var requestId by Transfers.requestId
    var sourceAccount by Transfers.sourceAccount
    var targetAccount by Transfers.targetAccount
    var currency by Transfers.currency
    var amount by Transfers.amount
    var state by Transfers.state
    var createdAt by Transfers.createdAt
}