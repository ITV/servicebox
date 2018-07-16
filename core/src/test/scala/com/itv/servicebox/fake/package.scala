package com.itv.servicebox

package object fake {
  type TestNetworkController[F[_]] = algebra.NetworkController[F] with TestNetworkState[F]
}
