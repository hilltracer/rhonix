package coop.rchain.casper

import com.google.protobuf.ByteString
import coop.rchain.casper.internals._
import coop.rchain.casper.protocol.{BlockMessage, Bond}
import org.scalatest.{FlatSpec, Matchers}
import coop.rchain.catscontrib._
import Catscontrib._
import cats._
import cats.data._
import cats.implicits._
import cats.mtl.implicits._
import coop.rchain.casper.Estimator.BlockHash
import coop.rchain.catscontrib.TaskContrib._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.{HashMap, HashSet}

class ForkchoiceTest extends FlatSpec with Matchers with BlockGenerator {
  type StateWithChain[A] = State[Chain, A]

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "Estimator" should "return the appropriate score map and forkchoice" in {
    val v1     = ByteString.copyFromUtf8("Validator One")
    val v2     = ByteString.copyFromUtf8("Validator Two")
    val v1Bond = Bond(v1, 2)
    val v2Bond = Bond(v2, 3)
    val bonds  = Seq(v1Bond, v2Bond)
    val createChain =
      for {
        genesis <- createBlock[StateWithChain](Seq(), ByteString.EMPTY, bonds)
        b2 <- createBlock[StateWithChain](Seq(genesis.blockHash),
                                          v2,
                                          bonds,
                                          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash))
        b3 <- createBlock[StateWithChain](Seq(genesis.blockHash),
                                          v1,
                                          bonds,
                                          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash))
        b4 <- createBlock[StateWithChain](Seq(b2.blockHash),
                                          v2,
                                          bonds,
                                          HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash))
        b5 <- createBlock[StateWithChain](Seq(b2.blockHash),
                                          v1,
                                          bonds,
                                          HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash))
        _ <- createBlock[StateWithChain](Seq(b4.blockHash),
                                         v2,
                                         bonds,
                                         HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash))
        b7 <- createBlock[StateWithChain](Seq(b4.blockHash),
                                          v1,
                                          bonds,
                                          HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash))
        b8 <- createBlock[StateWithChain](Seq(b7.blockHash),
                                          v1,
                                          bonds,
                                          HashMap(v1 -> b7.blockHash, v2 -> b4.blockHash))
      } yield b8

    val initState =
      Chain(HashMap.empty[Int, BlockMessage],
            HashMap.empty[ByteString, BlockMessage],
            HashMap.empty[BlockHash, HashSet[BlockHash]],
            0)
    val chain: Chain = createChain.runS(initState).value

    val genesis = chain.idToBlocks(1)
    val b2      = chain.idToBlocks(2)
    val b3      = chain.idToBlocks(3)
    val b4      = chain.idToBlocks(4)
    val b6      = chain.idToBlocks(6)
    val b7      = chain.idToBlocks(7)
    val b8      = chain.idToBlocks(8)

    val latestBlocks = HashMap[ByteString, BlockHash](v1 -> b8.blockHash, v2 -> b6.blockHash)

    val forkchoice = Estimator.tips(chain.childMap, chain.blockLookup, latestBlocks, genesis)
    forkchoice.head should be(b6)
    forkchoice(1) should be(b8)
  }
}
