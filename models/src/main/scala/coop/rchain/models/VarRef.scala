package coop.rchain.models

final case class VarRef(
    index: Int = 0,
    depth: Int = 0
) extends RhoType
