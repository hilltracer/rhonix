package coop.rchain.models.rholang

import cats.Eval
import coop.rchain.catscontrib.effect.implicits.sEval
import org.openjdk.jmh.annotations._
import scodec.bits.ByteVector

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import coop.rchain.models.Expr.ExprInstance._
import coop.rchain.models._
import coop.rchain.models.serialization.implicits._
import coop.rchain.shared.Serialize
import coop.rchain.models.rholang.implicits._

@Fork(value = 1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@OperationsPerInvocation(value = 100)
@State(Scope.Benchmark)
class ParBench {

  def findMaxRecursionDepth(): Int = {
    def count(i: Int): Int =
      try {
        count(i + 1) //apparently, the try-catch is enough for tailrec to not work. Lucky!
      } catch {
        case _: StackOverflowError => i
      }

    println("About to find max recursion depth for this test run")
    val maxDepth = count(0)
    println(s"Calculated max recursion depth is $maxDepth")
    // Because of OOM errors on CI depth recursion is limited
    val maxDepthLimited = Math.min(1000, maxDepth)
    println(s"Used recursion depth is limited to $maxDepthLimited")
    maxDepthLimited
  }

  @tailrec
  final def createNestedPar(n: Int, par: Par = Par(exprs = Seq(GInt(0)))): Par =
    if (n == 0) par
    else createNestedPar(n - 1, Par(exprs = Seq(EList(Seq(par)))))

  final def createParProc(n: Int): Par = {
    val elSize     = 33
    def el(i: Int) = EListBody(EList(Seq.fill(elSize)(GInt(i.toLong))))
    Par(exprs = Seq.tabulate(n)(el))
  }

  final def appendTest(n: Int): Par = {
    val elSize = 33
    def el(i: Int) = EListBody(EList(Seq.fill(elSize)(GInt(i.toLong))))
    val seq = Seq.tabulate(n)(el)
    seq.foldLeft(Par()) { (acc, p) =>
      acc.addExprs(p)
    }
  }

  var maxRecursionDepth: Int     = _
  var nestedPar: Par             = _
  var nestedAnotherPar: Par      = _
  var nestedParSData: ByteVector = _

  val parProcSize: Int         = 1000
  var parProc: Par             = _
  var parProcAnother: Par      = _
  var parProcSData: ByteVector = _
  @Setup(Level.Trial)
  def setup(): Unit = {
    maxRecursionDepth = findMaxRecursionDepth()
    nestedPar = createNestedPar(maxRecursionDepth)
    nestedAnotherPar = createNestedPar(maxRecursionDepth)
    nestedParSData = Serialize[Par].encode(nestedPar)

    parProc = createParProc(parProcSize)
    parProcSData = Serialize[Par].encode(parProc)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def nestedCreation(): Unit = {
    val _ = createNestedPar(maxRecursionDepth)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def nestedSerialization(): Unit = {
    val _ = Serialize[Par].encode(nestedPar)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def nestedDeserialization(): Unit = {
    val _ = Serialize[Par].decode(nestedParSData)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def nestedSerializedSize(): Unit = {
    val _ = ProtoM.serializedSize(nestedPar).value
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def nestedHash(): Unit = {
    val _ = HashM[Par].hash[Eval](nestedPar).value
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def nestedEqual(): Unit = {
    val _ = EqualM[Par].equal[Eval](nestedPar, nestedAnotherPar).value
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def nestedAdd(): Unit = {
    val _ = nestedPar.addExprs(GInt(0))
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def parProcCreation(): Unit = {
    val _ = createParProc(parProcSize)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def parProcSerialization(): Unit = {
    val _ = Serialize[Par].encode(parProc)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def parProcDeserialization(): Unit = {
    val _ = Serialize[Par].decode(parProcSData)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def parProcSerializedSize(): Unit = {
    val _ = ProtoM.serializedSize(parProc).value
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def parProcHash(): Unit = {
    val _ = HashM[Par].hash[Eval](parProc).value
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def parProcEqual(): Unit = {
    val _ = EqualM[Par].equal[Eval](parProc, parProcAnother).value
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def parProcAdd(): Unit = {
    val _ = parProc.addExprs(GInt(0))
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def manyAppends(): Unit = {
    val _ = appendTest(1000)
  }
}
