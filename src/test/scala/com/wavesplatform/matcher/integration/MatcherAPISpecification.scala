package com.wavesplatform.matcher.integration

import com.wavesplatform.matcher.api.CancelOrderRequest
import com.wavesplatform.settings.Constants
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar._
import org.scalatest.{FunSuite, Matchers}
import play.api.libs.json.{JsArray, JsValue, Json}
import scorex.account.{Account, PrivateKeyAccount}
import scorex.api.http.assets.{IssueRequest, TransferRequest}
import scorex.crypto.encode.Base58
import scorex.transaction.AssetAcc
import scorex.transaction.assets.exchange.{AssetPair, Order}
import scorex.transaction.assets.exchange.OrderJson._
import scorex.utils.NTP

/**
  * !!! Tests should work only as whole TestSuite in sequence one by one, not separately,
  * as the state depends on the previous test
  */
class MatcherAPISpecification extends FunSuite with Matchers with Eventually with scorex.waves.TestingCommons {
  private val wallet = application.wallet
  private val AccountM = wallet.privateKeyAccounts()(2)
  private val AccountA = wallet.privateKeyAccounts().head
  private var Asset1 = Option("")
  private var MBalance = 0L
  private var MBalance1 = 0L
  private var ABalance = 0L
  private var ABalance1 = 0L
  private val TxFee = 100000L
  private val storedState = application.storedState
  private var orderIdToCancel = Option.empty[String]

  private val MatcherPubKey = application.wallet.privateKeyAccount(application.settings.matcherSettings.account).
    map(a => Base58.encode(a.publicKey)).get

  def initBalances() = {
    assetTransfer(AccountM, AccountA, 5000 * Constants.UnitsInWave)
    Asset1 = Some(issueAsset(AccountM, 1000 * Constants.UnitsInWave))
    MBalance = storedState.assetBalance(AssetAcc(AccountM, None))
    MBalance1 = storedState.assetBalance(AssetAcc(AccountM, Asset1.flatMap(Base58.decode(_).toOption)))
    ABalance = storedState.assetBalance(AssetAcc(AccountA, None))
    ABalance1 = storedState.assetBalance(AssetAcc(AccountA, Asset1.flatMap(Base58.decode(_).toOption)))
  }

  def issueAsset(from: Account, amount: Long): String = {
    val json =
      s"""{
         |  "name": "string",
         |  "quantity": $amount,
         |  "description": "string",
         |  "sender": "${from.address}",
         |  "decimals": 8,
         |  "reissuable": true,
         |  "fee": 100000000
         |}""".stripMargin
    val req = Json.parse(json).validate[IssueRequest].get
    val resp = postRequest(us = "/assets/issue", body = json)
    val id = (resp \ "id").as[String]
    id should not be empty

    waitForBalance(amount, from, Some(id))
    id
  }

  def assetTransfer(from: Account, to: Account, amount: Long, assetId: Option[String] = None) = {
    val json =
      s"""
         |{
         |  "recipient": "${to.address}",
         |  "amount": $amount,
         |  "attachment": "",
         |  "sender": "${from.address}",
         |  "fee": 100000
         |}""".stripMargin

    val req = Json.parse(json).validate[TransferRequest].get
    val resp = postRequest(us = "/assets/transfer", body = json)
    (resp \ "id").as[String] should not be empty

    waitForBalance(amount, to, None)
  }

  def waitForBalance(balance: Long, acc: Account, asset: Option[String] = None): Unit = {
    val assetId = asset.flatMap(Base58.decode(_).toOption)
    eventually(timeout(5.seconds), interval(500.millis)) {
      Thread.sleep(100)
      storedState.assetBalance(
        AssetAcc(acc, assetId)) should be(balance)
    }
  }

  def placeOrder(acc: PrivateKeyAccount, spendAsset: Option[String], receiveAsset: Option[String],
                 price: Double, amount: Long, expectedStatus: String = "OrderAccepted"): Option[String] = {
    val created = NTP.correctedTime()
    val timeToLive = created + Order.MaxLiveTime - 1000
    val pubKeyStr = Base58.encode(acc.publicKey)
    val json =
      s"""{
         |  "matcherFee": 100000,
         |  "price": ${(price * Order.PriceConstant).toLong},
         |  "spendAssetId": "${spendAsset.getOrElse("")}",
         |  "receiveAssetId": "${receiveAsset.getOrElse("")}",
         |  "amount": $amount,
         |  "timestamp": $created,
         |  "expiration": $timeToLive,
         |  "matcherPublicKey": "$MatcherPubKey",
         |  "senderPublicKey": "$pubKeyStr"
         |}""".stripMargin
    val order = Json.parse(json).validate[Order].get
    val signed = Order.sign(order, acc)
    val signedJson = signed.json

    val resp = matcherPostRequest("/orderbook", body = signedJson.toString)

    (resp \ "status").as[String] shouldBe expectedStatus
    (resp \ "message" \ "id").toOption.map(_.as[String])
  }

  def getOrderBook(asset: Option[String]): JsValue = {
    matcherGetRequest(s"/orderbook/WAVES/${asset.get}")
  }

  def getOrderStatus(asset: Option[String], id: String): JsValue = {
    matcherGetRequest(s"/orderbook/WAVES/${asset.get}/$id")
  }

  def waitForOrderStatus(asset: Option[String], id: String, status: String) = {
    eventually(timeout(5.seconds), interval(500.millis)) {
      (getOrderStatus(Asset1, id) \ "status").as[String] should be("Filled")
    }
  }

  def cancelOrder(acc: PrivateKeyAccount, spendAsset: Option[String], receiveAsset: Option[String],
                  orderId: String, expectedStatus: String = "OrderCanceled"): Unit = {
    val ts = NTP.correctedTime()
    val pubKeyStr = Base58.encode(acc.publicKey)
    val json =
      s"""{
         |  "sender": "$pubKeyStr",
         |  "orderId": "$orderId",
         |  "signature": "signature"
         |}""".stripMargin
    val orderCancel = Json.parse(json).validate[CancelOrderRequest].get
    val signed = CancelOrderRequest.sign(orderCancel, acc)
    val signedJson = signed.json

    val (a1, a2) = if (spendAsset.isDefined) (spendAsset.get, receiveAsset.getOrElse("")) else
      (receiveAsset.get, spendAsset.getOrElse(""))

    val pair = AssetPair.createAssetPair(spendAsset.getOrElse(AssetPair.WavesName),
      receiveAsset.getOrElse(AssetPair.WavesName)).get
    val resp = matcherPostRequest(s"/orderbook/${pair.firstStr}/${pair.secondStr}/cancel", body = signedJson.toString)

    (resp \ "status").as[String] shouldBe expectedStatus
  }

  test("start") {
    // don't move this to `beforeAll`! if this fails, `afterAll` never happens, leading to ports remain open
    waitForSingleConnection(application)
    waitForNextBlock(application)
    initBalances()
    Thread.sleep(1000)
  }

  test("/matcher/") {
    val resp = matcherGetRequest("/")
    resp.as[String] shouldBe MatcherPubKey
  }

  test("place sell order") {
    orderIdToCancel = placeOrder(AccountM, Asset1, None, 2, 500 * Constants.UnitsInWave)
    val ob = getOrderBook(Asset1)
    ((ob \ "asks") (0) \ "price").as[Long] shouldBe 2 * Order.PriceConstant
    ((ob \ "asks") (0) \ "amount").as[Long] shouldBe 500 * Constants.UnitsInWave
  }

  test("match with buy order") {
    val id = placeOrder(AccountA, None, Asset1, 2, 200 * Constants.UnitsInWave)
    val ob = getOrderBook(Asset1)
    ((ob \ "asks") (0) \ "amount").as[Long] shouldBe 300 * Constants.UnitsInWave

    val executedFee = 100000L
    MBalance += 2 * 200 * Constants.UnitsInWave + executedFee
    waitForBalance(MBalance, AccountM, None)
    ABalance -= 2 * 200 * Constants.UnitsInWave + executedFee
    waitForBalance(ABalance, AccountA, None)

    MBalance1 -= 200 * Constants.UnitsInWave
    waitForBalance(MBalance1, AccountM, Asset1)
    ABalance1 += 200 * Constants.UnitsInWave
    waitForBalance(ABalance1, AccountA, Asset1)

    (getOrderStatus(Asset1, id.get) \ "status").as[String] should be("Filled")
  }

  test("submit more orders than available assets including open") {
    waitForBalance(800 * Constants.UnitsInWave, AccountM, Asset1) // And 300 by price = 2 is open
    // Should be available Asset1 = 800 - 300 = 500 Asset1
    placeOrder(AccountM, Asset1, None, 1.5, 501 * Constants.UnitsInWave, "OrderRejected")
    placeOrder(AccountM, Asset1, None, 1.5, 500 * Constants.UnitsInWave, "OrderAccepted")

    val ob = getOrderBook(Asset1)
    ((ob \ "asks") (0) \ "price").as[Long] shouldBe (1.5 * Order.PriceConstant).toLong
    ((ob \ "asks") (0) \ "amount").as[Long] shouldBe 500 * Constants.UnitsInWave
    ((ob \ "asks") (1) \ "price").as[Long] shouldBe (2 * Order.PriceConstant).toLong
    ((ob \ "asks") (1) \ "amount").as[Long] shouldBe 300 * Constants.UnitsInWave
  }

  test("buy order match several price levels") {
    val id = placeOrder(AccountA, None, Asset1, 2.5, 700 * Constants.UnitsInWave, "OrderAccepted")
    waitForOrderStatus(Asset1, id.get, "Filled")

    val wavesAmount = (1.5 * 500 + 2 * 200).toLong * Constants.UnitsInWave
    val executedFee =  100000L * 500 / 700 + 100000L * 200 / 700
    MBalance += wavesAmount + executedFee
    waitForBalance(MBalance, AccountM, None)
    ABalance -= wavesAmount + executedFee
    waitForBalance(ABalance, AccountA, None)

    val ob = getOrderBook(Asset1)
    ((ob \ "asks") (0) \ "price").as[Long] shouldBe (2 * Order.PriceConstant).toLong
    ((ob \ "asks") (0) \ "amount").as[Long] shouldBe 100 * Constants.UnitsInWave

    val assetAmount = (500 + 200) * Constants.UnitsInWave
    MBalance1 -= assetAmount // shouldBe 100
    waitForBalance(MBalance1, AccountM, Asset1)
    ABalance1 += assetAmount // shouldBe 900
    waitForBalance(ABalance1, AccountA, Asset1)
  }

  test("cancel order and resubmit a new one") {
    cancelOrder(AccountM, Asset1, None, orderIdToCancel.get)
    placeOrder(AccountM, Asset1, None, 5, 100 * Constants.UnitsInWave, "OrderAccepted")
  }

  test("buy order should execute all open orders and put remaining in OrderBook") {
    waitForBalance(ABalance, AccountA, None)
    placeOrder(AccountA, None, Asset1, 5.5, 250 * Constants.UnitsInWave, "OrderAccepted")
    MBalance1 = 0
    waitForBalance(MBalance1, AccountM, Asset1)
    ABalance1 = 1000 * Constants.UnitsInWave
    waitForBalance(ABalance1, AccountA, Asset1)
    ABalance -= 500 * Constants.UnitsInWave + (TxFee * 100 / 250)
    waitForBalance(ABalance, AccountA, None)

    val ob = getOrderBook(Asset1)
    ((ob \ "bids") (0) \ "price").as[Long] shouldBe (5.5 * Order.PriceConstant).toLong
    ((ob \ "bids") (0) \ "amount").as[Long] shouldBe 150 * Constants.UnitsInWave
    (ob \ "asks").get.asInstanceOf[JsArray].value.size shouldBe 0
  }
}
