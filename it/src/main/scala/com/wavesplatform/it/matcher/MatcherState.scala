package com.wavesplatform.it.matcher

import com.wavesplatform.account.AccountKeyPair
import com.wavesplatform.it.api.{MatcherStatusResponse, OrderBookResponse, OrderbookHistory}
import com.wavesplatform.matcher.queue.QueueEventWithMeta
import com.wavesplatform.transaction.assets.exchange.AssetPair

case class MatcherState(offset: QueueEventWithMeta.Offset,
                        snapshots: Map[String, QueueEventWithMeta.Offset],
                        orderBooks: Map[AssetPair, OrderBookResponse],
                        orderStatuses: Map[String, MatcherStatusResponse],
                        reservedBalances: Map[AccountKeyPair, Map[String, Long]],
                        orderHistory: Map[AccountKeyPair, Map[AssetPair, Seq[OrderbookHistory]]])
