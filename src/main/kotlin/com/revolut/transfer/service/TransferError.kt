package com.revolut.transfer.service

sealed class TransferError
object SourceAccountNotFoundError : TransferError()
object TargetAccountNotFoundError : TransferError()
object SourceAccountCurrencyMismatch: TransferError()
object TargetAccountCurrencyMismatch: TransferError()
object DuplicateRequestId: TransferError()