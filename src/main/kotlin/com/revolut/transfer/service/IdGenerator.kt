package com.revolut.transfer.service

import java.util.*

interface IdGenerator<T> {
    fun nextId(): T
}

object UuidGenerator : IdGenerator<String> {
    override fun nextId() = UUID.randomUUID().toString()
}