package io.vamp.lifter.vga

import io.vamp.common.akka._
import io.vamp.container_driver.kubernetes.{ DaemonSet, KubernetesServiceType }
import io.vamp.container_driver.{ ContainerDriverActor, Docker }
import io.vamp.lifter.notification.LifterNotificationProvider
import io.vamp.lifter.vga.VgaKubernetesSynchronizationActor.Synchronize

import scala.language.postfixOps
import scala.util.Try

class VgaKubernetesSynchronizationSchedulerActor extends SchedulerActor with LifterNotificationProvider {

  def tick() = IoC.actorFor[VgaKubernetesSynchronizationActor] ! Synchronize
}

object VgaKubernetesSynchronizationActor {

  sealed trait VgaKubernetesMessages

  object Synchronize extends VgaKubernetesMessages

}

class VgaKubernetesSynchronizationActor extends VgaSynchronizationActor {

  import VgaKubernetesSynchronizationActor._

  val kubernetesServiceType = Try(KubernetesServiceType.withName(configuration.getString("kubernetes.service-type"))).map(Option(_)).getOrElse(None)

  def receive = {
    case Synchronize ⇒ synchronize()
    case _           ⇒
  }

  private def synchronize() = {
    IoC.actorFor[ContainerDriverActor] ! DaemonSet(
      name = id,
      docker = Docker(
        image = container,
        portMappings = ports,
        parameters = Nil,
        privileged = true,
        network = "HOST"
      ),
      cpu = cpu,
      mem = mem,
      serviceType = kubernetesServiceType,
      args = arguments)
  }
}