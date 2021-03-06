package io.vamp.operation.workflow

import akka.actor._
import akka.pattern.ask
import io.vamp.common.akka.IoC._
import io.vamp.common.akka._
import io.vamp.model.artifact.Workflow.Status.RestartingPhase
import io.vamp.model.artifact._
import io.vamp.model.event.Event
import io.vamp.operation.notification._
import io.vamp.persistence.db.WorkflowPersistenceMessages.{ ResetWorkflow, UpdateWorkflowStatus }
import io.vamp.persistence.db.{ ArtifactPaginationSupport, ArtifactSupport, PersistenceActor }
import io.vamp.persistence.kv.KeyValueStoreActor
import io.vamp.pulse.Percolator.{ RegisterPercolator, UnregisterPercolator }
import io.vamp.pulse.PulseActor.Publish
import io.vamp.pulse.{ PulseActor, PulseEventTags }
import io.vamp.workflow_driver.{ WorkflowDeployable, WorkflowDriver, WorkflowDriverActor }

import scala.concurrent.Future

object WorkflowActor {

  case class Update(workflow: Workflow, running: Boolean)

  case class Trigger(workflow: Workflow)

}

class WorkflowActor extends ArtifactPaginationSupport with ArtifactSupport with CommonSupportForActors with OperationNotificationProvider {

  import PulseEventTags.Workflows._
  import WorkflowActor._

  private val percolator = "workflow://"

  implicit val timeout = WorkflowDriverActor.timeout

  def receive: Receive = {

    case Update(workflow, running) ⇒ try update(workflow, running) catch {
      case t: Throwable ⇒ reportException(WorkflowSchedulingError(t))
    }

    case (Trigger(workflow), event: Event) ⇒ try trigger(workflow, event.tags) catch {
      case t: Throwable ⇒ reportException(WorkflowExecutionError(t))
    }

    case _ ⇒
  }

  private def update(workflow: Workflow, running: Boolean): Unit = workflow.status match {

    case Workflow.Status.Starting      ⇒ run(workflow, running)

    case Workflow.Status.Stopping      ⇒ stop(workflow, running)

    case Workflow.Status.Running       ⇒ run(workflow, running)

    case Workflow.Status.Suspended     ⇒ suspend(workflow, running)

    case Workflow.Status.Suspending    ⇒ suspend(workflow, running)

    case _: Workflow.Status.Restarting ⇒ restart(workflow, running)
  }

  private def run(workflow: Workflow, running: Boolean): Unit = {
    deploy(workflow, running, () ⇒ {
      if (workflow.status != Workflow.Status.Running)
        (actorFor[PersistenceActor] ? UpdateWorkflowStatus(workflow, Workflow.Status.Running)).map { _ ⇒
          pulse(workflow, scheduled = true)
        }
    })
  }

  private def stop(workflow: Workflow, running: Boolean): Unit = {
    undeploy(workflow, running, () ⇒ {
      (actorFor[PersistenceActor] ? PersistenceActor.Delete(workflow.name, classOf[Workflow])).map { _ ⇒
        actorFor[PersistenceActor] ! ResetWorkflow(workflow)
        pulse(workflow, scheduled = false)
      }
    })
  }

  private def suspend(workflow: Workflow, running: Boolean): Unit = {
    undeploy(workflow, running, () ⇒ {
      if (workflow.status != Workflow.Status.Suspended)
        (actorFor[PersistenceActor] ? UpdateWorkflowStatus(workflow, Workflow.Status.Suspended)).map { _ ⇒
          pulse(workflow, scheduled = false)
        }
    })
  }

  private def restart(workflow: Workflow, running: Boolean): Unit = {
    workflow.status match {
      case Workflow.Status.Restarting(Some(RestartingPhase.Starting)) ⇒ deploy(workflow, running, () ⇒ {
        (actorFor[PersistenceActor] ? UpdateWorkflowStatus(workflow, Workflow.Status.Running)).map { _ ⇒
          pulse(workflow, scheduled = true)
        }
      })
      case _ ⇒ undeploy(workflow, running, () ⇒ {
        (actorFor[PersistenceActor] ? UpdateWorkflowStatus(workflow, Workflow.Status.Restarting(Option(RestartingPhase.Starting)))).map { _ ⇒
          pulse(workflow, scheduled = false)
        }
      })
    }
  }

  private def deploy(workflow: Workflow, running: Boolean, update: () ⇒ Unit) = {
    if (running) update()
    else {
      workflow.schedule match {
        case DaemonSchedule        ⇒ trigger(workflow)
        case TimeSchedule(_, _, _) ⇒ trigger(workflow)
        case EventSchedule(tags)   ⇒ (IoC.actorFor[PulseActor] ? RegisterPercolator(s"$percolator${workflow.name}", tags, Trigger(workflow))).map(_ ⇒ update())
        case _                     ⇒
      }
    }
  }

  private def undeploy(workflow: Workflow, running: Boolean, update: () ⇒ Unit) = {
    workflow.schedule match {
      case DaemonSchedule        ⇒ if (running) IoC.actorFor[WorkflowDriverActor] ? WorkflowDriverActor.Unschedule(workflow) else update()
      case TimeSchedule(_, _, _) ⇒ if (running) IoC.actorFor[WorkflowDriverActor] ? WorkflowDriverActor.Unschedule(workflow) else update()
      case EventSchedule(tags)   ⇒ (IoC.actorFor[PulseActor] ? UnregisterPercolator(s"$percolator${workflow.name}")).map(_ ⇒ update())
      case _                     ⇒
    }
  }

  private def trigger(workflow: Workflow, data: Any = None): Future[_] = {
    log.info(s"Triggering workflow: '${workflow.name}'.")

    artifactFor[DefaultBreed](workflow.breed).flatMap { breed ⇒

      (if (workflow.scale.isDefined) artifactFor[DefaultScale](workflow.scale.get).map(Option(_)) else Future.successful(None)).flatMap { scale ⇒

        if (WorkflowDeployable.matches(breed.deployable)) {
          IoC.actorFor[KeyValueStoreActor] ? KeyValueStoreActor.Set(WorkflowDriver.path(workflow), Option(breed.deployable.definition)) flatMap {
            _ ⇒ IoC.actorFor[WorkflowDriverActor] ? WorkflowDriverActor.Schedule(workflow.copy(breed = breed, scale = scale), data)
          }
        }
        else {
          IoC.actorFor[WorkflowDriverActor] ? WorkflowDriverActor.Schedule(workflow.copy(breed = breed, scale = scale), data)
        }
      }
    }
  }

  private def pulse(workflow: Workflow, scheduled: Boolean) = {
    actorFor[PulseActor] ! Publish(Event(Set(s"workflows${Event.tagDelimiter}${workflow.name}", if (scheduled) scheduledTag else unscheduledTag), workflow), publishEventValue = false)
  }
}
