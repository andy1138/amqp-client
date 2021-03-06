package com.aphelia.amqp

import akka.util.duration._
import java.io.IOException
import com.aphelia.amqp.ConnectionOwner._
import akka.util.Duration
import com.rabbitmq.client.{Connection, ShutdownSignalException, ShutdownListener, ConnectionFactory}
import akka.actor.{ActorRef, FSM, Actor, Props}
import akka.dispatch.Await
import akka.util.Timeout._
import akka.pattern.ask

object ConnectionOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  private[amqp] sealed trait Data

  private[amqp] case object Uninitialized extends Data

  private[amqp] case class Connected(conn: Connection) extends Data

  private[amqp] case class CreateChannel()

  private[amqp] case class Shutdown(cause: ShutdownSignalException)

  case class Create(props: Props, name: Option[String] = None)

  /** ask a ConnectionOwner to create a "channel owner" actor (producer, consumer, rpc client or server
   * ... or even you own)
   *
   * @param conn ConnectionOwner actor
   * @param props actor configuration object
   * @param name optional actor name
   * @param timeout time out
   * @return a reference to to created actor
   */
  def createActor(conn: ActorRef, props: Props, name: Option[String] = None, timeout: Duration = 5000 millis) : ActorRef = {
    val future = conn.ask(Create(props, name))(timeout).mapTo[ActorRef]
    Await.result(future, timeout)
  }

  def createActor(conn: ActorRef, props: Props, timeout: Duration) : ActorRef = createActor(conn, props, None, timeout)

  /**
   * creates an amqp uri from a ConnectionFactory. From the specs:
   * <ul>
   * <li>amqp_URI       = "amqp://" amqp_authority [ "/" vhost ]</li>
   * <li>amqp_authority = [ amqp_userinfo "@" ] host [ ":" port ]</li>
   * <li>amqp_userinfo  = username [ ":" password ]</li>
   * </ul>
   * @param cf connection factory
   * @return an amqp uri
   */
  def toUri(cf: ConnectionFactory): String = {
    "amqp://%s:%s@%s:%d/%s".format(cf.getUsername, cf.getPassword, cf.getHost, cf.getPort, cf.getVirtualHost)
  }
}

/**
 * ConnectionOwner class, which holds an AMQP connection and handles re-connection
 * It is implemented as a state machine which 2 possible states
 * <ul>
 * <li>Disconnected, and it will try to connect to the broker at regular intervals</li>
 * <li>Connected; it is then holding a connection
 * </ul>
 * Connection owner is responsible for creating "channel aware" actor (channel are like virtual connections,
 * which are multiplexed on the underlying connection). The parent connection owner will automatically tell
 * its children when the connection is lost, and send them new channels when it comes back on.
 * YMMV, but it is a good practice to have few connections and several channels per connection
 * @param connFactory connection factory
 * @param reconnectionDelay delay between reconnection attempts
 */
class ConnectionOwner(connFactory: ConnectionFactory, reconnectionDelay: Duration = 10000 millis) extends Actor with FSM[State, Data] {

  startWith(Disconnected, Uninitialized)

  /**
   * ask this connection owner to create a "channel aware" child
   * @param props actor creation properties
   * @param name optional actor name
   * @return a new actor
   */
  def createChild(props: Props, name: Option[String]) = {
    // why isn't there an actorOf(props: Props, name: Option[String] = None) ?
    name match {
      case None => context.actorOf(props)
      case Some(actorName) => context.actorOf(props, actorName)
    }
  }

  when(Disconnected) {
    case Event('connect, _) => {
      try {
        val conn = connFactory.newConnection()
        conn.addShutdownListener(new ShutdownListener {
          def shutdownCompleted(cause: ShutdownSignalException) {
            self ! Shutdown(cause)
          }
        })
        cancelTimer("reconnect")
        goto(Connected) using (Connected(conn))
      }
      catch {
        case e: IOException => {
          log.error(e, "cannot connect to {}, retrying in {}", connFactory, reconnectionDelay)
          setTimer("reconnect", 'connect, reconnectionDelay, true)
        }
      }
    }
    /*
     * create a "channel aware" actor that will request channels from this connection actor
     */
    case Event(Create(props, name), _) => {
      val child = createChild(props, name)
      log.debug("creating child {} while in disconnected state", child)
      stay replying child
    }
    /*
     * when disconnected, ignore channel request. Another option would to send back something like None...
     */
    case Event(CreateChannel, _) => stay
  }

  when(Connected) {
    /*
     * channel request. send back a channel
     */
    case Event(CreateChannel, Connected(conn)) => stay replying conn.createChannel()
    /*
     * create a "channel aware" actor that will request channels from this connection actor
     */
    case Event(Create(props, name), Connected(conn)) => {
      val channel = conn.createChannel()
      val child = createChild(props, name)
      log.debug("creating child {} with channel {}", child, channel)
      // send a channel to the kid
      child ! channel
      stay replying child
    }
    /*
     * shutdown event sent by the connection's shutdown listener
     */
    case Event(Shutdown(cause), _) => {
      if (!cause.isInitiatedByApplication) {
        log.error(cause.toString)
        self ! 'connect
        context.children.foreach(_ ! Shutdown(cause))
      }
      goto(Disconnected) using (Uninitialized)
    }
    /*
     * send a channel to each child actor
     */
    case Event('feedTheKids, Connected(conn)) => {
      context.children.foreach(_ ! conn.createChannel())
      stay
    }
  }

  onTransition {
    case Disconnected -> Connected => {
      log.info("connected to " + toUri(connFactory))
      self ! 'feedTheKids
    }
    case Connected -> Disconnected => log.warning("lost connection to " + toUri(connFactory))
  }

  onTermination {
    case StopEvent(_, Connected, Connected(conn)) => {
      log.info("closing connection to " + toUri(connFactory))
      conn.close()
    }
  }

  override def preStart() {
    self ! 'connect
  }
}

