package coop.rchain.rspace.history

import cats.syntax.all._
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.history.History.KeyPath
import coop.rchain.rspace.history.TestData._
import coop.rchain.shared.Base16
import coop.rchain.store.InMemoryKeyValueStore
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Assertion, FlatSpec, Matchers, OptionValues}
import scodec.bits.ByteVector

import java.nio.ByteBuffer
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.util.Random

class HistoryActionTests
    extends FlatSpec
    with Matchers
    with OptionValues
    with InMemoryHistoryTestBase {

  "creating and read one record" should "works" in withEmptyHistory { emptyHistoryF =>
    val data = insert(_zeros) :: Nil
    for {
      emptyHistory <- emptyHistoryF
      newHistory   <- emptyHistory.process(data)
      readValue    <- newHistory.read(_zeros)
      _            = readValue shouldBe data.head.hash.bytes.some
    } yield ()
  }

  "reset method of history" should "works" in withEmptyHistory { emptyHistoryF =>
    val data = insert(_zeros) :: Nil
    for {
      emptyHistory    <- emptyHistoryF
      newHistory      <- emptyHistory.process(data)
      historyOneReset <- emptyHistory.reset(newHistory.root)
      readValue       <- historyOneReset.read(_zeros)
      _               = readValue shouldBe data.head.hash.bytes.some
    } yield ()
  }

  "creating ten records" should "works" in withEmptyHistory { emptyHistoryF =>
    val data = List.range(0, 10).map(zerosAnd).map(k => InsertAction(k, randomBlake))
    for {
      emptyHistory <- emptyHistoryF
      newHistory   <- emptyHistory.process(data)
      readValues   <- data.traverse(action => newHistory.read(action.key))
      _            = readValues shouldBe data.map(_.hash.bytes.some)
    } yield ()
  }

  "History" should "allow to store different length key records in different branches" in withEmptyHistory {
    emptyHistoryF =>
      val data = insert(hexKey("01")) :: insert(hexKey("02")) ::
        insert(hexKey("0001")) :: insert(hexKey("0002")) :: Nil
      for {
        emptyHistory <- emptyHistoryF
        newHistory   <- emptyHistory.process(data)
        readValues   <- data.traverse(action => newHistory.read(action.key))
        _            = readValues shouldBe data.map(_.hash.bytes.some)
      } yield ()
  }

  // TODO: Don't works for MergingHistory
  "deletion of a non existing records" should "not throw error" in withEmptyHistory {
    emptyHistoryF =>
      val changes1 = insert(hexKey("0011")) :: Nil
      val changes2 = delete(hexKey("0011")) +: delete(hexKey("0012")) +: Nil
      for {
        emptyHistory <- emptyHistoryF
        historyOne   <- emptyHistory.process(changes1)
        err          <- historyOne.process(changes2).attempt
      } yield {
        err.isLeft shouldBe false
      }
  }

  "History don't" should "allow to store different length key records in same branch" in withEmptyHistory {
    emptyHistoryF =>
      val data = insert(hexKey("01")) :: insert(hexKey("0100")) :: Nil
      for {
        emptyHistory <- emptyHistoryF
        err          <- emptyHistory.process(data).attempt
      } yield {
        err.isLeft shouldBe true
        val ex = err.left.get
        ex shouldBe a[AssertionError]
        ex.getMessage shouldBe s"assertion failed: The length of all prefixes in the subtree must be the same."
        // TODO: For MergingHistory
        // ex shouldBe a[RuntimeException]
        // ex.getMessage shouldBe s"malformed trie"
      }
  }

  "History don't" should "allow to process HistoryActions with same keys" in withEmptyHistory {
    emptyHistoryF =>
      val data1 = insert(_zeros) :: insert(_zeros) :: Nil
      for {
        emptyHistory <- emptyHistoryF
        err          <- emptyHistory.process(data1).attempt
      } yield {
        err.isLeft shouldBe true
        val ex = err.left.get
        ex shouldBe a[RuntimeException]
        ex.getMessage shouldBe s"Cannot process duplicate actions on one key."
      }
      val data2 = insert(_zeros) :: delete(_zeros) :: Nil
      for {
        emptyHistory <- emptyHistoryF
        err          <- emptyHistory.process(data2).attempt
      } yield {
        err.isLeft shouldBe true
        val ex = err.left.get
        ex shouldBe a[RuntimeException]
        ex.getMessage shouldBe s"Cannot process duplicate actions on one key."
      }
  }

  "History after deleting all records" should "be empty" in withEmptyHistory { emptyHistoryF =>
    val insertions = insert(_zeros) :: Nil
    val deletions  = delete(_zeros) :: Nil
    for {
      emptyHistory <- emptyHistoryF
      historyOne   <- emptyHistory.process(insertions)
      historyTwo   <- historyOne.process(deletions)
      _            = historyTwo.root shouldBe emptyHistory.root
    } yield ()
  }

  "reading of a non existing records" should "return None" in withEmptyHistory { emptyHistoryF =>
    val key = hexKey("0011")
    for {
      emptyHistory <- emptyHistoryF
      readValue    <- emptyHistory.read(key)
      _            = readValue shouldBe None
    } yield ()
  }

  "update of a record" should "not change past history" in withEmptyHistory { emptyHistoryF =>
    val insertOne = insert(_zeros) :: Nil
    val insertTwo = insert(_zeros) :: Nil
    for {
      emptyHistory     <- emptyHistoryF
      historyOne       <- emptyHistory.process(insertOne)
      readValueOnePre  <- historyOne.read(_zeros)
      historyTwo       <- historyOne.process(insertTwo)
      readValueOnePost <- historyOne.read(_zeros)
      readValueTwo     <- historyTwo.read(_zeros)
      _                = readValueOnePre shouldBe readValueOnePost
      _                = readValueOnePre should not be readValueTwo
    } yield ()
  }

  "History" should "correctly build the same trees in different ways" in withEmptyHistory {
    emptyHistoryF =>
      val insertOne       = insert(hexKey("010000")) :: insert(hexKey("0200")) :: Nil
      val insertTwo       = insert(hexKey("010001")) :: insert(hexKey("0300")) :: Nil
      val insertOneAndTwo = insertOne ::: insertTwo
      val deleteOne       = delete(insertOne.head.key) :: delete(insertOne(1).key) :: Nil
      val deleteTwo       = delete(insertTwo.head.key) :: delete(insertTwo(1).key) :: Nil
      val deleteOneAndTwo = deleteOne ::: deleteTwo

      for {
        emptyHistory <- emptyHistoryF

        historyOne       <- emptyHistory.process(insertOne)
        historyTwo       <- emptyHistory.process(insertTwo)
        historyOneAndTwo <- emptyHistory.process(insertOneAndTwo)

        historyOneAndTwoAnotherWay <- historyOne.process(insertTwo)
        _                          = historyOneAndTwo.root shouldBe historyOneAndTwoAnotherWay.root

        historyOneAnotherWay <- historyOneAndTwo.process(deleteTwo)
        _                    = historyOne.root shouldBe historyOneAnotherWay.root

        historyTwoAnotherWay <- historyOneAndTwo.process(deleteOne)
        _                    = historyTwo.root shouldBe historyTwoAnotherWay.root

        emptyHistoryAnotherWay <- historyOneAndTwo.process(deleteOneAndTwo)
        _                      = emptyHistory.root shouldBe emptyHistoryAnotherWay.root

      } yield ()
  }

  "Adding already existing records" should "not change history" in withEmptyHistoryAndStore {
    (emptyHistoryF, inMemoStore) =>
      val inserts = insert(_zeros) :: Nil
      for {
        emptyHistory     <- emptyHistoryF
        emptyHistorySize = inMemoStore.sizeBytes()
        historyOne       <- emptyHistory.process(inserts)
        historyOneSize   = inMemoStore.sizeBytes()
        historyTwo       <- historyOne.process(inserts)
        historyTwoSize   = inMemoStore.sizeBytes()
        _                = historyOne.root shouldBe historyTwo.root
        _                = emptyHistorySize shouldBe 0L
        _                = historyOneSize shouldBe historyTwoSize
      } yield ()
  }
// TODO: Don't works for MergingHistory
  "Collision detecting in KVDB" should "works" in withEmptyHistoryAndStore {
    (emptyHistoryF, inMemoStore) =>
      def copyBVToBuf(bv: ByteVector): ByteBuffer = {
        val arr    = bv.toArray
        val newBuf = ByteBuffer.allocateDirect(arr.length)
        newBuf.put(arr).rewind()
      }
      val insertRecord    = insert(_zeros) :: Nil
      val deleteRecord    = delete(_zeros) :: Nil
      val collisionKVPair = (copyBVToBuf(History.emptyRootHash.bytes), randomBlake.bytes)
      for {
        emptyHistory <- emptyHistoryF
        newHistory   <- emptyHistory.process(insertRecord)
        _            <- inMemoStore.put[ByteVector](Seq(collisionKVPair), copyBVToBuf)
        err          <- newHistory.process(deleteRecord).attempt
      } yield {
        err.isLeft shouldBe true
        val ex = err.left.get
        ex shouldBe a[AssertionError]
        ex.getMessage shouldBe
          s"assertion failed: 1 collisions in KVDB (first collision with key = " +
            s"${History.emptyRootHash.bytes.toHex})."
      }
  }

  protected def withEmptyHistory(f: Task[History[Task]] => Task[Unit]): Unit = {
    val emptyHistory = History.create(History.emptyRootHash, InMemoryKeyValueStore[Task])
    f(emptyHistory).runSyncUnsafe(20.seconds)
  }

  protected def withEmptyHistoryAndStore(
      f: (Task[History[Task]], InMemoryKeyValueStore[Task]) => Task[Unit]
  ): Unit = {
    val store        = InMemoryKeyValueStore[Task]
    val emptyHistory = History.create(History.emptyRootHash, store)
    f(emptyHistory, store).runSyncUnsafe(20.seconds)
  }

  def skipShouldHaveAffix(t: Trie, bytes: KeyPath): Assertion =
    t match {
      case Skip(affix, _) => affix.toSeq.toList shouldBe bytes
      case p              => fail("unknown trie prefix" + p)
    }

  def randomKey(size: Int): Seq[Byte] = List.fill(size)((Random.nextInt(256) - 128).toByte)

  def generateRandomInsert(size: Int): Seq[InsertAction] = List.fill(size)(insert(randomKey(32)))
  def generateRandomDelete(size: Int): Seq[DeleteAction] = List.fill(size)(delete(randomKey(32)))
  def generateRandomDeleteFromInsert(size: Int, inserts: List[InsertAction]): Seq[DeleteAction] =
    Random.shuffle(inserts).take(size).map(i => delete(i.key))

  def updateState(
      state: TrieMap[KeyPath, Blake2b256Hash],
      actions: List[HistoryAction]
  ): TrieMap[KeyPath, Blake2b256Hash] = {
    actions.map {
      case InsertAction(key, hash) => state.put(key, hash)
      case DeleteAction(key)       => state.remove(key)
    }
    state
  }
}

object TestData {

  implicit def toByteVector(bytes: KeyPath): ByteVector = ByteVector(bytes)

  val _zeros: KeyPath           = List.fill(32)(0).map(_.toByte)
  val _zerosOnes: KeyPath       = (List.fill(16)(0) ++ List.fill(16)(1)).map(_.toByte)
  val _31zeros: KeyPath         = List.fill(31)(0).map(_.toByte)
  def zerosAnd(i: Int): KeyPath = _31zeros :+ i.toByte
  def prefixWithZeros(s: String): KeyPath = {
    val a = List.fill(32 - s.length)(0).map(_.toByte)
    val b = s.toCharArray.toList.map(_.asDigit).map(_.toByte)
    a ++ b
  }

  def hexKey(s: String): Seq[Byte] = Base16.unsafeDecode(s).toList

  def randomBlake: Blake2b256Hash =
    Blake2b256Hash.create(Random.alphanumeric.take(32).map(_.toByte).toArray)

  def zerosBlake: Blake2b256Hash = Blake2b256Hash.create(List.fill(32)(0).map(_.toByte).toArray)

  def insert(k: KeyPath): InsertAction = InsertAction(k, randomBlake)

  def delete(k: KeyPath): DeleteAction = DeleteAction(k)
}