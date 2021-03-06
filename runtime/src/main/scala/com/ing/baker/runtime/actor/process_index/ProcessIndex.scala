package com.ing.baker.runtime.actor.process_index

import akka.actor.{ActorLogging, ActorRef, Props, Terminated}
import akka.pattern.{ask, pipe}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.stream.{Materializer, StreamRefAttributes}
import com.ing.baker.il.petrinet.{Place, RecipePetriNet, Transition}
import com.ing.baker.il.{CompiledRecipe, petrinet}
import com.ing.baker.petrinet.runtime.{PetriNetRuntime, namedCachedThreadPool}
import com.ing.baker.runtime.actor._
import com.ing.baker.runtime.actor.process_index.ProcessIndex._
import com.ing.baker.runtime.actor.process_index.ProcessIndexProtocol._
import com.ing.baker.runtime.actor.process_instance.ProcessInstance.Settings
import com.ing.baker.runtime.actor.process_instance.ProcessInstanceProtocol._
import com.ing.baker.runtime.actor.process_instance.{ProcessInstance, ProcessInstanceProtocol}
import com.ing.baker.runtime.actor.recipe_manager.RecipeManagerProtocol._
import com.ing.baker.runtime.actor.serialization.Encryption
import com.ing.baker.runtime.core.events.{ProcessCreated, RejectReason}
import com.ing.baker.runtime.core.interations.InteractionManager
import com.ing.baker.runtime.core.{ProcessState, RuntimeEvent, events}
import com.ing.baker.runtime.petrinet._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}


object ProcessIndex {

  def props(cleanupInterval: FiniteDuration = 1 minute,
            processIdleTimeout: Option[FiniteDuration],
            configuredEncryption: Encryption,
            interactionManager: InteractionManager,
            recipeManager: ActorRef)(implicit materializer: Materializer) =
    Props(new ProcessIndex(cleanupInterval, processIdleTimeout, configuredEncryption, interactionManager, recipeManager))

  sealed trait ProcessStatus

  //message
  case object CheckForProcessesToBeDeleted extends InternalBakerMessage

  //The process is created and not deleted
  case object Active extends ProcessStatus

  //The process was deleted
  case object Deleted extends ProcessStatus

  case class ActorMetadata(recipeId: String, processId: String, createdDateTime: Long, processStatus: ProcessStatus)

  // --- Events

  // when an actor is requested again after passivation
  case class ActorActivated(processId: String) extends InternalBakerEvent

  // when an actor is passivated
  case class ActorPassivated(processId: String) extends InternalBakerEvent

  // when an actor is deleted
  case class ActorDeleted(processId: String) extends InternalBakerEvent

  // when an actor is created
  case class ActorCreated(recipeId: String, processId: String, createdDateTime: Long) extends InternalBakerEvent

  def processInstanceProps(recipeName: String, petriNet: RecipePetriNet, petriNetRuntime: PetriNetRuntime[Place, Transition, ProcessState, RuntimeEvent], settings: Settings): Props =
    Props(new ProcessInstance[Place, Transition, ProcessState, RuntimeEvent](
      recipeName,
      petriNet,
      settings,
      petriNetRuntime)
    )

  private val bakerExecutionContext: ExecutionContext = namedCachedThreadPool(s"Baker.CachedThreadPool")

  private val updateCacheTimeout: FiniteDuration = 5 seconds
}

class ProcessIndex(cleanupInterval: FiniteDuration = 1 minute,
                   processIdleTimeout: Option[FiniteDuration],
                   configuredEncryption: Encryption,
                   interactionManager: InteractionManager,
                   recipeManager: ActorRef)(implicit materializer: Materializer) extends PersistentActor with ActorLogging {

  private val index: mutable.Map[String, ActorMetadata] = mutable.Map[String, ActorMetadata]()
  private val recipeCache: mutable.Map[String, CompiledRecipe] = mutable.Map[String, CompiledRecipe]()

  import context.dispatcher

  context.system.scheduler.schedule(cleanupInterval, cleanupInterval, context.self, CheckForProcessesToBeDeleted)

  def updateCache() = {
    // TODO this is a synchronous ask on an actor which is considered bad practice, alternative?
    val futureResult = recipeManager.ask(GetAllRecipes)(updateCacheTimeout).mapTo[AllRecipes]

    try {
      val allRecipes = Await.result(futureResult, updateCacheTimeout)
      recipeCache ++= allRecipes.compiledRecipes
    } catch {
      case exception: Throwable =>
        log.warning("Updating process cache failed", exception)
    }
  }

  def getCompiledRecipe(recipeId: String): Option[CompiledRecipe] =
    recipeCache.get(recipeId) match {
      case None => {
        updateCache()
        recipeCache.get(recipeId)
      }
      case Some(compiledRecipe) => Some(compiledRecipe)
    }

  def getOrCreateProcessActor(processId: String): ActorRef =
    context.child(processId).getOrElse(createProcessActor(processId))

  def createProcessActor(processId: String): ActorRef = {
    val compiledRecipe: CompiledRecipe = getCompiledRecipe(index(processId).recipeId).get
    createProcessActor(processId, compiledRecipe)
  }

  def createProcessActor(processId: String, compiledRecipe: CompiledRecipe): ActorRef = {
    val petriNetRuntime: PetriNetRuntime[Place, Transition, ProcessState, RuntimeEvent] =
      new RecipeRuntime(compiledRecipe.name, interactionManager, context.system.eventStream)

    val processActorProps =
      processInstanceProps(compiledRecipe.name, compiledRecipe.petriNet, petriNetRuntime,
        ProcessInstance.Settings(
          executionContext = bakerExecutionContext,
          encryption = configuredEncryption,
          idleTTL = processIdleTimeout))

    val processActor = context.actorOf(processActorProps, name = processId)

    context.watch(processActor)
    processActor
  }

  def shouldDelete(meta: ActorMetadata): Boolean = {
    meta.processStatus != Deleted &&
      getCompiledRecipe(meta.recipeId)
        .flatMap(_.retentionPeriod)
        .exists { p => meta.createdDateTime + p.toMillis < System.currentTimeMillis() }
  }

  def isDeleted(meta: ActorMetadata): Boolean =
    meta.processStatus == Deleted


  def deleteProcess(processId: String): Unit = {
    persist(ActorDeleted(processId)) { _ =>
      val meta = index(processId)
      index.update(processId, meta.copy(processStatus = Deleted))
    }
  }

  override def receiveCommand: Receive = {

    case GetIndex =>
      sender() ! Index(index.values.filter(_.processStatus == Active).toSet)

    case CheckForProcessesToBeDeleted =>
      val toBeDeleted = index.values.filter(shouldDelete)
      if (toBeDeleted.nonEmpty)
        log.debug(s"Deleting processes: {}", toBeDeleted.mkString(","))

      toBeDeleted.foreach(meta => getOrCreateProcessActor(meta.processId) ! Stop(delete = true))

    case Terminated(actorRef) =>
      val processId = actorRef.path.name
      log.debug(s"Actor terminated: $actorRef")

      index.get(processId) match {
        case Some(meta) if shouldDelete(meta) =>
          deleteProcess(processId)
        case Some(meta) if isDeleted(meta) =>
          log.warning(s"Received Terminated message for already deleted process: ${meta.processId}")
        case Some(_) =>
          persist(ActorPassivated(processId)) { _ => }
        case None =>
          log.warning(s"Received Terminated message for non indexed actor: $actorRef")
      }

    case CreateProcess(recipeId, processId) =>
      context.child(processId) match {
        case None if !index.contains(processId) =>
          val created = System.currentTimeMillis()
          persist(ActorCreated(recipeId, processId, created)) { _ =>

            getCompiledRecipe(recipeId) match {
              case Some(compiledRecipe) =>

                val processState = ProcessState(processId, Map.empty, List.empty)
                val initializeCmd = Initialize(compiledRecipe.initialMarking, processState)

                createProcessActor(processId, compiledRecipe).forward(initializeCmd)
                val actorMetadata = ActorMetadata(recipeId, processId, created, Active)
                index += processId -> actorMetadata
                context.system.eventStream.publish(ProcessCreated(System.currentTimeMillis(), recipeId, compiledRecipe.name, processId))

              case None => sender() ! NoRecipeFound(recipeId)
            }
          }
        case _ if isDeleted(index(processId)) => sender() ! ProcessDeleted(processId)
        case _ => sender() ! ProcessAlreadyInitialized(processId)
      }

    case cmd @ ProcessEvent(processId: String, eventToFire: RuntimeEvent, correlationId, waitForRetries, processEventTimout) =>

      def rejectWith(msg: Any, rejectReason: RejectReason): Unit = {
        context.system.eventStream.publish(events.EventRejected(System.currentTimeMillis(), cmd.processId, cmd.correlationId, cmd.event, rejectReason))
        Source.single(msg).runWith(StreamRefs.sourceRef()).map(ProcessEventResponse(processId, _)).pipeTo(sender())
      }

      //Forwards the event message to the Actor if its in the Receive period for the compiledRecipe
      def forwardEventIfInReceivePeriod(actorRef: ActorRef, recipe: CompiledRecipe) = {

        def forwardEvent(): Unit = {

          val source = ProcessEventActor.processEvent(actorRef, recipe, cmd, waitForRetries)(processEventTimout, context.system, materializer)
          val sourceRef = source.runWith(StreamRefs.sourceRef().addAttributes(StreamRefAttributes.subscriptionTimeout(processEventTimout)))

          sourceRef.map(ProcessEventResponse(processId, _)).pipeTo(sender())
        }

        recipe.eventReceivePeriod match {
          case Some(receivePeriod) =>
            index.get(processId).foreach { p =>
              if (System.currentTimeMillis() - p.createdDateTime > receivePeriod.toMillis) {

                rejectWith(ReceivePeriodExpired(processId), RejectReason.ReceivePeriodExpired)
              }
              else
                forwardEvent()
            }

          case None =>
            forwardEvent()
        }
      }

      //Validates if the event is correct for the given
      //If not return a InvalidEvent message to the sender
      //Otherwise forwardEventIfInReceivePeriod
      def validateEventAvailableForRecipe(actorRef: ActorRef, recipe: CompiledRecipe) = {
        recipe.sensoryEvents.find(sensoryEvent => sensoryEvent.name == eventToFire.name) match {
          case None =>
            rejectWith(InvalidEvent(processId, s"No event with name '${eventToFire.name}' found in recipe '${recipe.name}'"), RejectReason.InvalidEvent)
          case Some(sensoryEvent) =>
            //Check If the sensory event is valid for this recipe
            val eventValidationErrors = eventToFire.validateEvent(sensoryEvent)
            if (eventValidationErrors.nonEmpty)
              rejectWith(InvalidEvent(processId, s"Invalid event: " + eventValidationErrors.mkString(",")), RejectReason.InvalidEvent)
            else {
              forwardEventIfInReceivePeriod(actorRef, recipe)
            }
        }
      }

      //Handles the event, assumes the process is created
      def handleEventWithActor(actorRef: ActorRef) = {
        index.get(processId) match {
          case Some(actorMetadata) =>
            val recipeId = actorMetadata.recipeId
            getCompiledRecipe(recipeId).foreach { compiledRecipe: CompiledRecipe =>
              validateEventAvailableForRecipe(actorRef, compiledRecipe)
            }
          case None =>
            rejectWith(ProcessUninitialized(processId), RejectReason.NoSuchProcess)
        }
      }

      //Check if the process has a active actor
      //If not check if it is available and start
      //If not return a Uninitialized message
      context.child(processId) match {
        case None if !index.contains(processId) => rejectWith(ProcessUninitialized(processId), RejectReason.NoSuchProcess)
        case None if isDeleted(index(processId)) => rejectWith(ProcessUninitialized(processId), RejectReason.ProcessDeleted)
        case None =>
          persist(ActorActivated(processId)) { _ =>
            handleEventWithActor(createProcessActor(processId))
          }
        case Some(actorRef) => handleEventWithActor(actorRef)
      }

    case GetProcessState(processId) =>
      context.child(processId) match {
        case None if !index.contains(processId) => sender() ! ProcessUninitialized(processId)
        case None if isDeleted(index(processId)) => sender() ! ProcessDeleted(processId)
        case None if index.contains(processId) =>
          persist(ActorActivated(processId)) {
            _ =>
              createProcessActor(processId).forward(ProcessInstanceProtocol.GetState)
          }
        case Some(actorRef) => actorRef.forward(ProcessInstanceProtocol.GetState)
      }

    case GetCompiledRecipe(processId) =>
      index.get(processId) match {
        case Some(processMetadata) if isDeleted(processMetadata) => sender() ! ProcessDeleted(processId)
        case Some(processMetadata) =>
          getCompiledRecipe(processMetadata.recipeId) match {
            case Some(compiledRecipe) => sender() ! RecipeFound(compiledRecipe)
            case None => sender() ! ProcessUninitialized(processId)
          }
        case None => sender() ! ProcessUninitialized(processId)
      }
    case cmd =>
      log.error(s"Unrecognized command $cmd")
  }

  // This Set holds all the actor ids to be created again after the recovery completed
  private val actorsToBeCreated: mutable.Set[String] = mutable.Set.empty

  override def receiveRecover: Receive = {
    case ActorCreated(recipeId, processId, createdTime) =>
      index += processId -> ActorMetadata(recipeId, processId, createdTime, Active)
      actorsToBeCreated += processId
    case ActorPassivated(processId) =>
      actorsToBeCreated -= processId
    case ActorActivated(processId) =>
      actorsToBeCreated += processId
    case ActorDeleted(processId) =>
      actorsToBeCreated -= processId
      index.get(processId) match {
        case Some(processMeta) =>
          index.update(processId, processMeta.copy(processStatus = Deleted))
        case None =>
          log.error(s"ActorDeleted persisted for non existing processId: $processId")
      }

    case RecoveryCompleted =>
      actorsToBeCreated.foreach(id => createProcessActor(id))
  }

  override def persistenceId: String = self.path.name
}
