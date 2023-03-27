package io.rhonix.rholang.interpreter.accounting

import io.rhonix.models.ProtoBindings.toProto
import io.rhonix.models.{ProtoM, RhoType, StacksafeMessage}

/* TODO: Make Chargeable instances for requisite rspace type parameters. Then, create an instance of PureRSpace
         that uses the generic instances, _cost, and _error for a single, charging PureRSpace. */

trait Chargeable[A] {
  def cost(a: A): Long
}

object Chargeable {
  def apply[T](implicit ev: Chargeable[T]): Chargeable[T] = ev

  implicit def fromProtobuf[T <: RhoType] =
    new Chargeable[T] {
      override def cost(a: T): Long = ProtoM.serializedSize(toProto(a)).value.toLong
    }
}
