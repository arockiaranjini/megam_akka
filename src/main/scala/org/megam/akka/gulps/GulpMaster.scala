/* 
** Copyright [2012] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package org.megam.akka.gulps

import akka.actor.{ Actor, ActorRef, Identify, ActorIdentity }
import akka.actor.ActorContext
import akka.actor.ActorLogging
import akka.actor.ActorPath
import akka.actor.ActorSystem
import akka.actor.Terminated
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import org.megam.akka.Constants._
import org.megam.akka.GulpApp

/**
 * @author rajthilak
 *
 */
class GulpMaster extends Actor with ActorLogging {

  import org.megam.akka.master.MasterWorkerProtocol._
  import scala.collection.mutable.{ Map, Queue }
  import org.megam.akka.gulps.GulpProtocol._

  val cluster = Cluster(context.system)
  val name = GULPACTOR
  val nodeName = NODEACTOR
  // Holds known workers and what they may be working on
  val workers = Map.empty[ActorRef, Option[Tuple2[ActorRef, Any]]]

  // Holds the incoming list of work to be done as well
  // as the memory of who asked for it
  val workQ = Queue.empty[Tuple2[ActorRef, Any]]
  log.info("GulpMaster Started")
  val identifyId = 1
  val nodeIdentifyId = 2
  val watchIdentifyId = 3
  // Notifies workers that there's work available, provided they're
  // not already working on something
  def notifyWorkers(): Unit = {
    if (!workQ.isEmpty) {
      workers.foreach {
        case (worker, m) if (m.isEmpty) => worker ! WorkIsReady
        case _                          =>
      }
    }
  }

  override def preStart(): Unit = {
    log.info("GulpMaster preStart Started")
    cluster.subscribe(self, classOf[MemberEvent])
    cluster.subscribe(self, classOf[UnreachableMember])

    /*
     * To acquire an ActorRef for an ActorSelection you need to send a message to the selection 
     * and use the sender reference of the reply from the actor. 
     * There is a built-in Identify message that all Actors will understand 
     * and automatically reply to with a ActorIdentity message containing the ActorRef.
     * 
     */
    context.actorSelection(ActorPath.fromString("akka://%s/user/%s".format(MEGAMGULP, name))) ! Identify(identifyId)
    context.actorSelection(ActorPath.fromString("akka://%s/user/%s".format(MEGAMGULP, nodeName))) ! Identify(nodeIdentifyId)
    context.actorSelection(ActorPath.fromString("akka://%s/user/%s".format(MEGAMGULP, WATCHACTOR))) ! Identify(watchIdentifyId)
    /**
     * Send out a CloReg to closervice stating that a new master is up.
     */
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  def receive = {

    case ActorIdentity(`identifyId`, Some(ref)) ⇒
      ref ! MasterRegistration

    case ActorIdentity(`nodeIdentifyId`, Some(ref)) ⇒
      ref ! NodeReg

    case ActorIdentity(`watchIdentifyId`, Some(ref)) ⇒
      ref ! WatchReg

    // Worker is alive. Add him to the list, watch him for
    // death, and let him know if there's work to be done
    case WorkerCreated(worker) =>
      log.info("Worker created: {}", worker)
      context.watch(worker)
      workers += (worker -> None)
      notifyWorkers()

    // A worker wants more work.  If we know about him, he's not
    // currently doing anything, and we've got something to do,
    // give it to him.
    case WorkerRequestsWork(worker) =>
      log.info("Worker requests work: {}", worker)
      if (workers.contains(worker)) {
        if (workQ.isEmpty)
          worker ! NoWorkToBeDone
        else if (workers(worker) == None) {
          val (workSender, work) = workQ.dequeue()
          workers += (worker -> Some(workSender -> work))
          // Use the special form of 'tell' that lets us supply
          // the sender
          worker.tell(WorkToBeDone(work), workSender)
        }
      }

    // Worker has completed its work and we can clear it out
    case WorkIsDone(worker) =>
      if (!workers.contains(worker))
        log.error("Blurgh! {} said it's done work but we didn't know about him", worker)
      else
        workers += (worker -> None)

    // A worker died.  If he was doing anything then we need
    // to give it to someone else so we just add it back to the
    // master and let things progress as usual
    case Terminated(worker) =>
      if (workers.contains(worker) && workers(worker) != None) {
        log.error("Blurgh! {} died while processing {}", worker, workers(worker))
        // Send the work that it was doing back to ourselves for processing
        val (workSender, work) = workers(worker).get
        self.tell(work, workSender)
      }
      workers -= worker

    // Anything other than our own protocol is "work to be done"
    case work =>
      log.info("Queueing {}", work)
      workQ.enqueue(sender -> work)
      notifyWorkers()
  }

}