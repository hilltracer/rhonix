package coop.rchain.node.api

import coop.rchain.node.diagnostics
import coop.rchain.p2p.effects._
import io.grpc.{Server, ServerBuilder}

import scala.concurrent.Future
import cats._
import cats.data._
import cats.implicits._
import com.google.protobuf.empty.Empty
import coop.rchain.catscontrib.mtl.implicits._
import coop.rchain.casper.MultiParentCasper
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.protocol.{DeployData, DeployServiceGrpc, DeployServiceResponse}
import coop.rchain.casper.util.rholang.InterpreterUtil
import coop.rchain.catscontrib._
import Catscontrib._
import coop.rchain.crypto.codec.Base16
import coop.rchain.node.model.repl._
import coop.rchain.node.model.diagnostics._
import coop.rchain.rholang.interpreter.{RholangCLI, Runtime}
import coop.rchain.rholang.interpreter.accounting._
import coop.rchain.rholang.interpreter.storage.StoragePrinter
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler
import com.google.protobuf.ByteString
import java.io.{Reader, StringReader}

import coop.rchain.casper.api.BlockAPI
import coop.rchain.node.diagnostics.{JvmMetrics, NodeMetrics}
import coop.rchain.rholang.interpreter.errors.InterpreterError
import coop.rchain.comm.transport._
import coop.rchain.comm.discovery._
import coop.rchain.shared._
import coop.rchain.models.Par
import coop.rchain.rholang.interpreter._
import Interpreter._
import storage.StoragePrinter

private[api] class ReplGrpcService(runtime: Runtime[Task], worker: Scheduler)
    extends ReplGrpcMonix.Repl {

  private[this] def interpreter() =
    for {
      costAlg <- CostAccounting.of[Task](Cost.Max)
      cost    = loggingCost[Task](costAlg, noOpCostLog)
      result = {
        implicit val c = cost
        Interpreter[Task]
      }
    } yield result

  def exec(source: String): Task[ReplResponse] =
    ParBuilder[Task]
      .buildNormalizedTerm(source)
      .attempt
      .flatMap {
        case Left(er) =>
          er match {
            case _: InterpreterError => Task.now(s"Error: ${er.toString}")
            case th: Throwable       => Task.now(s"Error: $th")
          }
        case Right(term) =>
          for {
            _                            <- Task.now(printNormalizedTerm(term))
            interpreter                  <- interpreter()
            res                          <- interpreter.evaluate(runtime, source)
            EvaluateResult(cost, errors) = res
          } yield {
            val errorStr =
              if (errors.isEmpty)
                ""
              else
                errors
                  .map(_.toString())
                  .mkString("Errors received during evaluation:\n", "\n", "\n")
            s"Deployment cost: $cost\n" +
              s"${errorStr}Storage Contents:\n ${StoragePrinter.prettyPrint(runtime.space.store)}"

          }

      }
      .map(ReplResponse(_))

  private def defer[A](task: Task[A]): Task[A] =
    Task.defer(task).executeOn(worker)

  def run(request: CmdRequest): Task[ReplResponse] =
    defer(exec(request.line))

  def eval(request: EvalRequest): Task[ReplResponse] =
    defer(exec(request.program))

  private def printNormalizedTerm(normalizedTerm: Par): Unit = {
    Console.println("\nEvaluating:")
    Console.println(PrettyPrinter().buildString(normalizedTerm))
  }
}
