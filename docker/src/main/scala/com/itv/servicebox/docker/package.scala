package com.itv.servicebox

import com.spotify.docker.client.messages.{Container => JavaContainer, ContainerInfo}

package object docker {
  case class ContainerAndInfo(container: JavaContainer, info: ContainerInfo)
}
