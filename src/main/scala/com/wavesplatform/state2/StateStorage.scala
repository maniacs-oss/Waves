package com.wavesplatform.state2

import java.io.File

import com.google.common.primitives.Ints
import com.wavesplatform.utils._
import org.h2.mvstore.MVMap
import scorex.account.Account
import scorex.utils.LogMVMapBuilder

class StateStorage private(file: Option[File]) extends AutoCloseable {

  import StateStorage._

  private val db = createMVStore(file)

  private val variables: MVMap[String, Int] = db.openMap("variables")

  private def setPersistedVersion(version: Int) = variables.put(stateVersion, version)

  private def persistedVersion: Option[Int] = Option(variables.get(stateVersion))

  private def setDirty(isDirty: Boolean): Unit = variables.put(isDirtyFlag, if (isDirty) 1 else 0)

  private def isDirty(): Boolean = variables.get(isDirtyFlag) == 1

  def getHeight: Int = variables.get(heightKey)

  def setHeight(i: Int): Unit = variables.put(heightKey, i)

  val transactions: MVMap[ByteStr, (Int, Array[Byte])] = db.openMap("txs", new LogMVMapBuilder[ByteStr, (Int, Array[Byte])]
    .keyType(new ByteStrDataType))

  val portfolios: MVMap[ByteStr, (Long, (Long, Long), Map[Array[Byte], Long])] = db.openMap("portfolios",
    new LogMVMapBuilder[ByteStr, (Long, (Long, Long), Map[Array[Byte], Long])]
      .keyType(new ByteStrDataType))


  val assets: MVMap[ByteStr, (Boolean, Long)] = db.openMap("assets", new LogMVMapBuilder[ByteStr, (Boolean, Long)]
    .keyType(new ByteStrDataType))

  val accountTransactionIds: MVMap[ByteStr, List[Array[Byte]]] = db.openMap("accountTransactionIds",
    new LogMVMapBuilder[ByteStr, List[Array[Byte]]]
      .keyType(new ByteStrDataType))

  val balanceSnapshots: MVMap[SnapshotKey, (Int, Long, Long)] = db.openMap("balanceSnapshots")

  val paymentTransactionHashes: MVMap[ByteStr, ByteStr] = db.openMap("paymentTransactionHashes",
    new LogMVMapBuilder[ByteStr, ByteStr]
      .keyType(new ByteStrDataType)
      .valueType(new ByteStrDataType))

  val aliasToAddress: MVMap[String, ByteStr] = db.openMap("aliasToAddress", new LogMVMapBuilder[String, ByteStr]
    .valueType(new ByteStrDataType))

  val orderFills: MVMap[ByteStr, (Long, Long)] = db.openMap("orderFills", new LogMVMapBuilder[ByteStr, (Long, Long)]
    .keyType(new ByteStrDataType))

  val leaseState: MVMap[ByteStr, Boolean] = db.openMap("leaseState", new LogMVMapBuilder[ByteStr, Boolean]
    .keyType(new ByteStrDataType))

  val lastUpdateHeight: MVMap[ByteStr, Int] = db.openMap("lastUpdateHeight", new LogMVMapBuilder[ByteStr, Int]
    .keyType(new ByteStrDataType))

  val uniqueAssets: MVMap[ByteStr, ByteStr] = db.openMap("uniqueAssets", new LogMVMapBuilder[ByteStr, ByteStr]
    .keyType(new ByteStrDataType)
    .valueType(new ByteStrDataType))

  def commit(): Unit = db.commit()

  override def close() = db.close()
}

object StateStorage {
  private val Version = 1

  private val heightKey = "height"
  private val isDirtyFlag = "isDirty"
  private val stateVersion = "stateVersion"

  private def validateVersion(ss: StateStorage): Boolean =
    ss.persistedVersion match {
      case None =>
        ss.setPersistedVersion(Version)
        ss.commit()
        true
      case Some(v) => v == Version

    }

  def apply(file: Option[File], dropExisting: Boolean = false) = for {
    ss <- createWithStore[StateStorage](file, new StateStorage(file), !_.isDirty(), dropExisting)
    if validateVersion(ss)
  } yield ss

  type SnapshotKey = Array[Byte]

  def snapshotKey(acc: Account, height: Int): SnapshotKey = acc.bytes.arr ++ Ints.toByteArray(height)

  def dirty[R](p: StateStorage)(f: => R): R = {
    p.setDirty(true)
    val r = f
    p.setDirty(false)
    r
  }
}
