package iterator.nucleus.ledger

interface LedgerEntryValidator {
  fun validate(request: CreateTransferRequest)
}
