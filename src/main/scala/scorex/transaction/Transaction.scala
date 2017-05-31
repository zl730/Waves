package scorex.transaction

import com.google.common.primitives.Ints
import scorex.account.Account
import scorex.serialization.JsonSerializable
import scorex.transaction.TransactionParser.TransactionType

trait Transaction extends StateChangeReason with JsonSerializable {

  val transactionType: TransactionType.Value
  val assetFee: (Option[AssetId], Long)
  val timestamp: Long
  override def toString: String = json.toString()

  override def equals(other: Any): Boolean = other match {
    case tx: Transaction => id.sameElements(tx.id)
    case _ => false
  }

  override def hashCode(): Int = Ints.fromByteArray(id.takeRight(4))

}
