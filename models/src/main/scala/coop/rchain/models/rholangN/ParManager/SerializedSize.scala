package coop.rchain.models.rholangN.ParManager

import com.google.protobuf.CodedOutputStream
import coop.rchain.models.rholangN._

import scala.annotation.unused

private[ParManager] object SerializedSize {

  import Constants._

  private def sSize(@unused value: Boolean): Int = booleanSize
  private def sSize(value: Int): Int             = CodedOutputStream.computeInt32SizeNoTag(value)
  private def sSize(value: Long): Int            = CodedOutputStream.computeInt64SizeNoTag(value)
  private def sSize(value: String): Int          = CodedOutputStream.computeStringSizeNoTag(value)

  private def sSize(p: RhoTypeN): Int = p.serializedSize

  private def sSizeSeq[T](seq: Seq[T], f: T => Int): Int =
    sSize(seq.size) + seq.map(f).sum

  private def sSize(ps: Seq[RhoTypeN]): Int =
    sSizeSeq[RhoTypeN](ps, sSize)

  private def sSizeStrings(strings: Seq[String]): Int =
    sSizeSeq[String](strings, sSize)

  private def sSize(pOpt: Option[RhoTypeN]): Int =
    booleanSize + (if (pOpt.isDefined) pOpt.get.serializedSize else 0)

  private def totalSize(sizes: Int*): Int =
    tagSize + sizes.sum

  def serializedSizeFn(p: RhoTypeN): Int = p match {

    /** Main types */
    case pProc: ParProcN =>
      val psSize = sSize(pProc.ps)
      totalSize(psSize)

    case send: SendN =>
      totalSize(sSize(send.chan), sSize(send.data), sSize(send.persistent))

    case receive: ReceiveN =>
      val bindsSize      = sSize(receive.binds)
      val bodySize       = sSize(receive.body)
      val persistentSize = sSize(receive.persistent)
      val peekSize       = sSize(receive.peek)
      val bindCountSize  = sSize(receive.bindCount)
      totalSize(bindsSize, bodySize, persistentSize, peekSize, bindCountSize)

    case m: MatchN =>
      val targetSize = sSize(m.target)
      val casesSize  = sSize(m.cases)
      totalSize(targetSize, casesSize)

    case n: NewN =>
      val bindCountSize = sSize(n.bindCount)
      val pSize         = sSize(n.p)
      val uriSize       = sSizeStrings(n.uri)
      totalSize(bindCountSize, pSize, uriSize)

    /** Ground types */
    case _: GNilN      => totalSize()
    case gBool: GBoolN => totalSize(sSize(gBool.v))
    case gInt: GIntN   => totalSize(sSize(gInt.v))

    /** Collections */
    case list: EListN =>
      totalSize(sSize(list.ps), sSize(list.remainder))

    /** Vars */
    case v: BoundVarN => totalSize(sSize(v.idx))
    case v: FreeVarN  => totalSize(sSize(v.idx))
    case _: WildcardN => totalSize()

    /** Expr */
    /** Bundle */
    /** Connective */
    /** Auxiliary types */
    case bind: ReceiveBindN =>
      val patternsSize  = sSize(bind.patterns)
      val sourceSize    = sSize(bind.source)
      val reminderSize  = sSize(bind.remainder)
      val freeCountSize = sSize(bind.freeCount)
      totalSize(patternsSize, sourceSize, reminderSize, freeCountSize)

    case mCase: MatchCaseN =>
      val patternSize   = sSize(mCase.pattern)
      val sourceSize    = sSize(mCase.source)
      val freeCountSize = sSize(mCase.freeCount)
      totalSize(patternSize, sourceSize, freeCountSize)

    case _ =>
      assert(assertion = false, "Not defined type")
      0
  }
}
