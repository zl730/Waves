package com.wavesplatform.api.http

import java.nio.charset.StandardCharsets

import com.wavesplatform.api.http.assets.AssetsApiRoute
import com.wavesplatform.http.{RestAPISettingsHelper, RouteSpec}
import com.wavesplatform.state.{AssetDescription, Blockchain, ByteStr}
import com.wavesplatform.utx.UtxPool
import com.wavesplatform.{NoShrink, TestTime, TestWallet, TransactionGen}
import io.netty.channel.group.ChannelGroup
import org.scalamock.scalatest.PathMockFactory
import org.scalatest.prop.PropertyChecks
import play.api.libs.json._

class AssetsApiRouteSpec
    extends RouteSpec("/assets")
    with PathMockFactory
    with PropertyChecks
    with RestAPISettingsHelper
    with TestWallet
    with TransactionGen
    with NoShrink {

  private val blockchain = stub[Blockchain]

  private val route = AssetsApiRoute(
    restAPISettings,
    testWallet,
    mock[UtxPool],
    mock[ChannelGroup],
    blockchain,
    new TestTime,
    scala.concurrent.ExecutionContext.Implicits.global
  ).route

  private val smartAssetTx = smartIssueTransactionGen().retryUntil(_.script.nonEmpty).sample.get
  private val smartAssetDesc = AssetDescription(
    issuer = smartAssetTx.sender,
    name = smartAssetTx.name,
    description = smartAssetTx.description,
    decimals = smartAssetTx.decimals,
    reissuable = smartAssetTx.reissuable,
    totalVolume = smartAssetTx.quantity,
    script = smartAssetTx.script,
    sponsorship = 0
  )
  (blockchain.transactionInfo _).when(smartAssetTx.id()).onCall((_: ByteStr) => Some((1, smartAssetTx)))
  (blockchain.assetDescription _).when(smartAssetTx.id()).onCall((_: ByteStr) => Some(smartAssetDesc))
  routePath(s"/details/${smartAssetTx.id().base58}") in {
    Get(routePath(s"/details/${smartAssetTx.id().base58}")) ~> route ~> check {
      val response = responseAs[JsObject]
      (response \ "assetId").as[String] shouldBe smartAssetTx.id().base58
      (response \ "issueHeight").as[Long] shouldBe 1
      (response \ "issueTimestamp").as[Long] shouldBe smartAssetTx.timestamp
      (response \ "issuer").as[String] shouldBe smartAssetTx.sender.toString
      (response \ "name").as[String] shouldBe new String(smartAssetTx.name, StandardCharsets.UTF_8)
      (response \ "description").as[String] shouldBe new String(smartAssetTx.description, StandardCharsets.UTF_8)
      (response \ "decimals").as[Int] shouldBe smartAssetTx.decimals
      (response \ "reissuable").as[Boolean] shouldBe smartAssetTx.reissuable
      (response \ "quantity").as[BigDecimal] shouldBe smartAssetDesc.totalVolume
      (response \ "minSponsoredAssetFee").asOpt[Long] shouldBe empty
    }
  }

  private val sillyAssetTx = issueGen.sample.get
  private val sillyAssetDesc = AssetDescription(
    issuer = sillyAssetTx.sender,
    name = sillyAssetTx.name,
    description = sillyAssetTx.description,
    decimals = sillyAssetTx.decimals,
    reissuable = sillyAssetTx.reissuable,
    totalVolume = sillyAssetTx.quantity,
    script = sillyAssetTx.script,
    sponsorship = 0
  )
  (blockchain.transactionInfo _).when(sillyAssetTx.id()).onCall((_: ByteStr) => Some((1, sillyAssetTx)))
  (blockchain.assetDescription _).when(sillyAssetTx.id()).onCall((_: ByteStr) => Some(sillyAssetDesc))
  routePath(s"/details/${sillyAssetTx.id().base58}") in {
    Get(routePath(s"/details/${sillyAssetTx.id().base58}")) ~> route ~> check {
      val response = responseAs[JsObject]
      (response \ "assetId").as[String] shouldBe sillyAssetTx.id().base58
      (response \ "issueHeight").as[Long] shouldBe 1
      (response \ "issueTimestamp").as[Long] shouldBe sillyAssetTx.timestamp
      (response \ "issuer").as[String] shouldBe sillyAssetTx.sender.toString
      (response \ "name").as[String] shouldBe new String(sillyAssetTx.name, StandardCharsets.UTF_8)
      (response \ "description").as[String] shouldBe new String(sillyAssetTx.description, StandardCharsets.UTF_8)
      (response \ "decimals").as[Int] shouldBe sillyAssetTx.decimals
      (response \ "reissuable").as[Boolean] shouldBe sillyAssetTx.reissuable
      (response \ "quantity").as[BigDecimal] shouldBe sillyAssetDesc.totalVolume
      (response \ "minSponsoredAssetFee").asOpt[Long] shouldBe empty
    }
  }
}
