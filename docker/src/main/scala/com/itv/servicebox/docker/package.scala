package com.itv.servicebox

import com.spotify.docker.client.messages.{ContainerInfo, Container => JavaContainer}

package object docker {
  case class ContainerAndInfo(container: JavaContainer, info: ContainerInfo)
}
