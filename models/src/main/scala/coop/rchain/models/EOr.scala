package coop.rchain.models

final case class EOr(
    p1: Par = Par(),
    p2: Par = Par()
) extends RhoType
