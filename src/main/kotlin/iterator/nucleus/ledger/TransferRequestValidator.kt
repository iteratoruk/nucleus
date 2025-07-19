package iterator.nucleus.ledger

interface TransferRequestValidator {
  fun validate(request: CreateTransferRequest)
}
