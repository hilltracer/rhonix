package io.rhonix.models

import com.google.protobuf.ByteString

final case class GPrivate(
    id: ByteString = ByteString.EMPTY
) extends RhoType
