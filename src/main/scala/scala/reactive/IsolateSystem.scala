package scala.reactive



import java.util.concurrent.atomic._
import scala.annotation.tailrec
import scala.collection._
import scala.util.DynamicVariable
import scala.reactive.isolate._



/** A system used to create, track and identify isolates.
 *
 *  An isolate system is composed of a set of isolates that have
 *  a common configuration.
 */
abstract class IsolateSystem {

  /** Name of this isolate system instance.
   *
   *  @return          the name of the isolate system
   */
  def name: String

  /** Creates an isolate in this isolate system using the specified scheduler.
   *
   *  '''Use case:'''
   *  {{{
   *  def isolate(proto: Proto[Isolate[T]]): Channel[T]
   *  }}}
   *
   *  Implementations of this method must initialize the isolate frame,
   *  add the isolate to the specific bookkeeping code,
   *  and then call the `wake` method on the isolate frame to start it for the first time.
   *
   *  @tparam T         the type of the events for the isolate
   *  @tparam Q         the type of the events in the event queue of the isolate,
   *                    for most isolate types the same as `T`
   *  @param proto      the prototype for the isolate
   *  @param scheduler  the scheduler used to scheduler the isolate
   *  @return           the channel for this isolate
   */
  def isolate[@spec(Int, Long, Double) T: Arrayable](proto: Proto[Isolate[T]], name: String = null): Channel[T]

  /** Creates a new channel for the specified isolate frame.
   *
   *  '''Note:'''
   *  The `channel` field of the isolate frame is not set at the time this method is called.
   *  
   *  @tparam Q         the type of the events for the isolate
   *  @param frame      the isolate frame for the channel
   *  @return           the new channel for the isolate frame
   */
  protected def newChannel[@spec(Int, Long, Double) Q](frame: IsolateFrame[Q]): Channel[Q]

  /** Creates an isolate from the `Proto` object.
   *
   *  Starts by memoizing the old isolate object,
   *  and then calling the creation method.
   */
  protected def createAndResetIsolate[T](proto: Proto[Isolate[T]]): Isolate[T] = {
    val oldi = Isolate.selfIsolate.get
    try {
      proto.create()
    } finally {
      Isolate.selfIsolate.set(oldi)
    }
  }

  /** Creates an isolate frame.
   *
   *  Should only be overridden if the default isolate initialization order needs to change.
   *  See the source code of the default implementation of this method for more details.
   *
   *  @tparam T         the type of the events for the isolate
   *  @param proto      prototype for the isolate
   *  @param name       name of the new isolate
   *  @return           the resulting isolate frame
   */
  protected def createFrame[@spec(Int, Long, Double) T: Arrayable](proto: Proto[Isolate[T]], name: String): IsolateFrame[T] = {
    val scheduler = proto.scheduler match {
      case null => bundle.defaultScheduler
      case name => bundle.scheduler(name)
    }
    val eventQueue = proto.eventQueueFactory match {
      case null => new EventQueue.SingleSubscriberSyncedUnrolledRing[T](new util.Monitor)
      case fact => fact.create[T]
    }
    val uname = uniqueName(name)
    val frame = new IsolateFrame[T](
      uname,
      IsolateSystem.this,
      eventQueue,
      new Reactive.Emitter[SysEvent],
      new Reactive.Emitter[T],
      new Reactive.Emitter[Throwable],
      scheduler,
      new IsolateFrame.State,
      new AtomicReference(IsolateFrame.Created)
    )
    val channel = newChannel(frame)
    frame.channel = channel
    val isolate = Isolate.argFrame.withValue(frame) {
      createAndResetIsolate(proto)
    }
    frame.isolate = isolate
    scheduler.initiate(frame)
    frame
  }

  /** Generates a unique isolate name if the `name` argument is `null`,
   *  and throws an exception if the `name` is already taken.
   *
   *  The implementation of this method needs to be thread-safe.
   *
   *  @param name       proposed name
   *  @return           a unique isolate name
   */
  protected def uniqueName(name: String): String

  /** Retrieves the scheduler bundle for this isolate system.
   *  
   *  @return           the scheduler bundle
   */
  def bundle: IsolateSystem.Bundle

}


/** Contains factory methods for creating isolate systems.
 */
object IsolateSystem {

  /** Retrieves the default isolate system.
   *  
   *  @param name       the name for the isolate system instance
   *  @param scheduler  the default scheduler
   *  @return           a new isolate system instance
   */
  def default(name: String, bundle: IsolateSystem.Bundle = IsolateSystem.defaultBundle) = new isolate.DefaultIsolateSystem(name, bundle)

  /** Contains a set of schedulers registered with each isolate system.
   */
  class Bundle(val defaultScheduler: Scheduler) {
    private val schedulers = mutable.Map[String, Scheduler]()

    /** Retrieves the scheduler registered under the specified name.
     *  
     *  @param name        the name of the scheduler
     *  @return            the scheduler object associated with the name
     */
    def scheduler(name: String): Scheduler = {
      schedulers(name)
    }
  
    /** Registers the scheduler under a specific name,
     *  so that it can be later retrieved using the 
     *  `scheduler` method.
     *
     *  @param name       the name under which to register the scheduler
     *  @param s          the scheduler object to register
     */
    def registerScheduler(name: String, s: Scheduler) {
      if (schedulers contains name) sys.error(s"Scheduler $name already registered.")
      else schedulers(name) = s
    }
  }

  /** Scheduler bundle factory methods.
   */
  object Bundle {
    /** A bundle with default schedulers from the `Scheduler` companion object.
     *  
     *  @return           the default scheduler bundle
     */
    def default(default: Scheduler): Bundle = {
      val b = new Bundle(default)
      b.registerScheduler("scala.reactive.Scheduler.globalExecutionContext", Scheduler.globalExecutionContext)
      b.registerScheduler("scala.reactive.Scheduler.default", Scheduler.default)
      b.registerScheduler("scala.reactive.Scheduler.newThread", Scheduler.newThread)
      b.registerScheduler("scala.reactive.Scheduler.piggyback", Scheduler.piggyback)
      b
    }
  }

  /** Default scheduler bundle.
   */
  lazy val defaultBundle = Bundle.default(Scheduler.default)

}


