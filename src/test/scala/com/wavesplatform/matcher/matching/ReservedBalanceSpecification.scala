package com.wavesplatform.matcher.matching

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import com.wavesplatform.account.AccountPublicKey
import com.wavesplatform.matcher.market.MatcherSpecLike
import com.wavesplatform.matcher.model.Events.{OrderAdded, OrderExecuted}
import com.wavesplatform.matcher.model.{LimitOrder, OrderHistoryStub}
import com.wavesplatform.matcher.{AssetPairDecimals, MatcherTestData, _}
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.assets.exchange.OrderType.{BUY, SELL}
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}
import com.wavesplatform.{NTPTime, WithDB}
import org.scalatest.PropSpecLike
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}

/**
  * Tests for reserved balance
  *
  * This specification checks how different cases affect reserved balance.
  *
  * Test cases was generated by filtered decision table:
  *
  * Field Name          | Values                          | Description
  * -------------------------------------------------------------------
  * CounterType         | Buy, Sell                       | Submitted type will be set automatically
  * Amounts             | A_c = A_s, A_c > A_s, A_c < A_s | A_c - counter amount, A_s - submitted amount
  * CorrectedAmount     | A_corr = A, A_corr < A          | A - match amount
  * Prices              | P_c = P_s, P_c != P_s           | P_c - counter price, P_s - submitted price
  * CounterRestAmount   | 0, A_min, A_min - 1             | Rest amount after matching. A_min - minimal amount can be filled by the price
  * SubmittedRestAmount | 0, A_min, A_min - 1             | Rest amount after matching. A_min - minimal amount can be filled by the price
  *
  * CounterType | Amounts   | CorrectedAmount | Prices     | CounterRestAmount | SubmittedRestAmount
  * ------------------------------------------------------------------------------------------------
  * Buy         | A_c = A_s | A_corr = A      | P_c = P_s  | 0                 | 0
  * Sell        | A_c = A_s | A_corr = A      | P_c = P_s  | 0                 | 0
  * Buy         | A_c = A_s | A_corr = A      | P_c != P_s | 0                 | 0
  * Sell        | A_c = A_s | A_corr = A      | P_c != P_s | 0                 | 0
  * Buy         | A_c > A_s | A_corr = A      | P_c = P_s  | A_min - 1         | 0
  * Sell        | A_c > A_s | A_corr = A      | P_c = P_s  | A_min - 1         | 0
  * Buy         | A_c > A_s | A_corr = A      | P_c != P_s | A_min - 1         | 0
  * Sell        | A_c > A_s | A_corr = A      | P_c != P_s | A_min - 1         | 0
  * Buy         | A_c < A_s | A_corr = A      | P_c = P_s  | 0                 | A_min - 1
  * Sell        | A_c < A_s | A_corr = A      | P_c = P_s  | 0                 | A_min - 1
  * Buy         | A_c < A_s | A_corr = A      | P_c != P_s | 0                 | A_min - 1
  * Sell        | A_c < A_s | A_corr = A      | P_c != P_s | 0                 | A_min - 1
  * Buy         | A_c > A_s | A_corr = A      | P_c = P_s  | A_min             | 0
  * Sell        | A_c > A_s | A_corr = A      | P_c = P_s  | A_min             | 0
  * Buy         | A_c > A_s | A_corr = A      | P_c != P_s | A_min             | 0
  * Sell        | A_c > A_s | A_corr = A      | P_c != P_s | A_min             | 0
  * Buy         | A_c < A_s | A_corr = A      | P_c = P_s  | 0                 | A_min
  * Sell        | A_c < A_s | A_corr = A      | P_c = P_s  | 0                 | A_min
  * Buy         | A_c < A_s | A_corr = A      | P_c != P_s | 0                 | A_min
  * Sell        | A_c < A_s | A_corr = A      | P_c != P_s | 0                 | A_min
  * Buy         | A_c = A_s | A_corr < A      | P_c = P_s  | A_min - 1         | A_min - 1
  * Sell        | A_c = A_s | A_corr < A      | P_c = P_s  | A_min - 1         | A_min - 1
  * Buy         | A_c < A_s | A_corr < A      | P_c = P_s  | A_min - 1         | A_min
  * Sell        | A_c < A_s | A_corr < A      | P_c = P_s  | A_min - 1         | A_min
  * Buy         | A_c > A_s | A_corr < A      | P_c = P_s  | A_min             | A_min - 1
  * Sell        | A_c > A_s | A_corr < A      | P_c = P_s  | A_min             | A_min - 1
  */
class ReservedBalanceSpecification
    extends PropSpecLike
    with MatcherSpecLike
    with WithDB
    with MatcherTestData
    with TableDrivenPropertyChecks
    with NTPTime {

  override protected def actorSystemName: String = "ReservedBalanceSpecification" // getClass.getSimpleName

  private implicit val timeout: Timeout = 5.seconds

  val pair = AssetPair(mkAssetId("WAVES"), mkAssetId("USD"))
  val p    = new AssetPairDecimals(8, 2)

  var oh = new OrderHistoryStub(system, ntpTime)
  private val addressDir = system.actorOf(
    Props(
      new AddressDirectory(
        ignoreSpendableBalanceChanged,
        matcherSettings,
        address =>
          Props(
            new AddressActor(
              address,
              _ => 0L,
              5.seconds,
              ntpTime,
              new TestOrderDB(100),
              _ => false,
              _ => Future.failed(new IllegalStateException("Should not be used in the test"))
            ))
      )
    ))

  private def openVolume(senderPublicKey: AccountPublicKey, assetId: Asset): Long =
    Await
      .result(
        (addressDir ? AddressDirectory.Envelope(senderPublicKey, AddressActor.GetReservedBalance)).mapTo[Map[Asset, Long]],
        Duration.Inf
      )
      .getOrElse(assetId, 0L)

  def execute(counter: Order, submitted: Order): OrderExecuted = {
    addressDir ! OrderAdded(LimitOrder(submitted))
    addressDir ! OrderAdded(LimitOrder(counter))

    oh.process(OrderAdded(LimitOrder(counter)))
    val exec = OrderExecuted(LimitOrder(submitted), LimitOrder(counter), submitted.timestamp)
    addressDir ! exec
    exec
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    oh = new OrderHistoryStub(system, ntpTime)
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2), p.price(2.3), p.amount(2), p.price(2.3)),
      (SELL, p.amount(2), p.price(2.3), p.amount(2), p.price(2.3)),
      (BUY, p.amount(2), p.price(2.3), p.amount(2), p.price(2.2)),
      (SELL, p.amount(2), p.price(2.2), p.amount(2), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(s"Reserves should be 0 when remains are 0: $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      withClue("Both orders should be filled") {
        exec.executedAmount shouldBe counter.amount
      }

      withClue("All remains should be 0") {
        exec.counterRemainingAmount shouldBe 0
        exec.submittedRemainingAmount shouldBe 0
      }

      withClue(s"Counter sender should not have reserves") {
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0
      }

      withClue(s"Submitted sender should not have reserves") {
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0
      }
    }
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2.00434782), p.price(2.3), p.amount(2), p.price(2.3)),
      (SELL, p.amount(2.00434782), p.price(2.3), p.amount(2), p.price(2.3)),
      (BUY, p.amount(2.00434782), p.price(2.3), p.amount(2), p.price(2.2)),
      (SELL, p.amount(2.00454545), p.price(2.2), p.amount(2), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(
      s"Counter reserves should be 0 WHEN remain is (minAmount - 1): $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      withClue("Both orders should be filled") {
        exec.executedAmount shouldBe submittedAmount
      }

      withClue("Counter remain should be (minAmount - 1):") {
        exec.counterRemainingAmount shouldBe p.minAmountFor(counterPrice) - 1
        exec.submittedRemainingAmount shouldBe 0
      }

      withClue(s"Counter sender should not have reserves:") {
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0
      }

      withClue(s"Submitted sender should not have reserves:") {
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0
      }
    }
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2), p.price(2.3), p.amount(2.00434782), p.price(2.3)),
      (SELL, p.amount(2), p.price(2.3), p.amount(2.00434782), p.price(2.3)),
      (BUY, p.amount(2), p.price(2.3), p.amount(2.00454545), p.price(2.2)),
      (SELL, p.amount(2), p.price(2.2), p.amount(2.00434782), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(
      s"Submitted reserves should be 0 WHEN remain is (minAmount - 1): $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      withClue("Both orders should be filled") {
        exec.executedAmount shouldBe counterAmount
      }

      withClue("Submitted remain should be (minAmount - 1):") {
        exec.counterRemainingAmount shouldBe 0
        exec.submittedRemainingAmount shouldBe p.minAmountFor(submittedPrice) - 1
      }

      withClue(s"Counter sender should not have reserves:") {
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0
      }

      withClue(s"Submitted sender should not have reserves:") {
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0
      }
    }
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2.00434783), p.price(2.3), p.amount(2), p.price(2.3)),
      (SELL, p.amount(2.00434783), p.price(2.3), p.amount(2), p.price(2.3)),
      (BUY, p.amount(2.00434783), p.price(2.3), p.amount(2), p.price(2.2)),
      (SELL, p.amount(2.00454546), p.price(2.2), p.amount(2), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(
      s"Counter reserve should be minAmount WHEN remain is minAmount: $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      withClue("Counter should be partially filled:") {
        exec.executedAmount shouldBe submittedAmount
      }

      withClue("Counter remain should be minAmount:") {
        exec.counterRemainingAmount shouldBe p.minAmountFor(counterPrice)
        exec.submittedRemainingAmount shouldBe 0
      }

      withClue(s"Counter sender should have reserved asset:") {
        val (expectedAmountReserve, expectedPriceReserve) = if (counterType == BUY) (0, 1) else (p.minAmountFor(counterPrice), 0)
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe expectedAmountReserve
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe expectedPriceReserve
      }

      withClue(s"Submitted sender should not have reserves:") {
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0
      }
    }
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2), p.price(2.3), p.amount(2.00434783), p.price(2.3)),
      (SELL, p.amount(2), p.price(2.3), p.amount(2.00434783), p.price(2.3)),
      (BUY, p.amount(2), p.price(2.3), p.amount(2.00454546), p.price(2.2)),
      (SELL, p.amount(2), p.price(2.2), p.amount(2.00434783), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(
      s"Submitted reserve should be minAmount WHEN remain is minAmount: $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      withClue("Submitted should be partially filled:") {
        exec.executedAmount shouldBe counterAmount
      }

      withClue("Submitted remain should be minAmount:") {
        exec.counterRemainingAmount shouldBe 0
        exec.submittedRemainingAmount shouldBe p.minAmountFor(submittedPrice)
      }

      withClue(s"Counter sender should not have reserves:") {
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0
      }

      withClue(s"Submitted sender should have reserved asset:") {
        val (expectedAmountReserve, expectedPriceReserve) = if (counterType == BUY) (p.minAmountFor(submittedPrice), 0) else (0, 1)
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe expectedAmountReserve
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe expectedPriceReserve
      }
    }
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2.00434782), p.price(2.3), p.amount(2.00434782), p.price(2.3)),
      (SELL, p.amount(2.00434782), p.price(2.3), p.amount(2.00434782), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(
      s"Counter and submitted reserves should be 0 WHEN both remains are (minAmount - 1): $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      exec.executedAmount shouldBe p.amount(2)

      exec.counterRemainingAmount shouldBe p.minAmountFor(counterPrice) - 1
      exec.submittedRemainingAmount shouldBe p.minAmountFor(submittedPrice) - 1

      withClue(s"Counter sender should not have reserves:") {
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0
      }

      withClue(s"Submitted sender should not have reserves:") {
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0
      }
    }
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2.00434782), p.price(2.3), p.amount(2.00434783), p.price(2.3)),
      (SELL, p.amount(2.00434782), p.price(2.3), p.amount(2.00434783), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(
      s"Counter and submitted reserves should be 0 and minAmount WHEN their remains are (minAmount - 1) and minAmount: $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      exec.executedAmount shouldBe p.amount(2)

      exec.counterRemainingAmount shouldBe p.minAmountFor(counterPrice) - 1
      exec.submittedRemainingAmount shouldBe p.minAmountFor(submittedPrice)

      withClue(s"Counter sender should not have reserves:") {
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0
      }

      withClue(s"Submitted sender should have reserved asset:") {
        val (expectedAmountReserve, expectedPriceReserve) = if (counterType == BUY) (p.minAmountFor(submittedPrice), 0) else (0, 1)
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe expectedAmountReserve
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe expectedPriceReserve
      }
    }
  }

  forAll(
    Table(
      ("counter type", "counter amount", "counter price", "submitted amount", "submitted price"),
      (BUY, p.amount(2.00434783), p.price(2.3), p.amount(2.00434782), p.price(2.3)),
      (SELL, p.amount(2.00434783), p.price(2.3), p.amount(2.00434782), p.price(2.3)),
    )) { (counterType: OrderType, counterAmount: Long, counterPrice: Long, submittedAmount: Long, submittedPrice: Long) =>
    property(
      s"Counter and submitted reserves should be minAmount and 0 WHEN their remains and minAmount are (minAmount - 1): $counterType $counterAmount/$counterPrice:$submittedAmount/$submittedPrice") {
      val counter   = if (counterType == BUY) rawBuy(pair, counterAmount, counterPrice) else rawSell(pair, counterAmount, counterPrice)
      val submitted = if (counterType == BUY) rawSell(pair, submittedAmount, submittedPrice) else rawBuy(pair, submittedAmount, submittedPrice)
      val exec      = execute(counter, submitted)

      exec.executedAmount shouldBe p.amount(2)

      exec.counterRemainingAmount shouldBe p.minAmountFor(counterPrice)
      exec.submittedRemainingAmount shouldBe p.minAmountFor(submittedPrice) - 1

      withClue(s"Counter sender should have reserved asset:") {
        val (expectedAmountReserve, expectedPriceReserve) = if (counterType == BUY) (0, 1) else (p.minAmountFor(submittedPrice), 0)
        openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe expectedAmountReserve
        openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe expectedPriceReserve
      }

      withClue(s"Submitted sender should not have reserves:") {
        openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0
        openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0
      }
    }
  }

}
