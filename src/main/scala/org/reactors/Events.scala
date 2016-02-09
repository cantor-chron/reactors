package org.reactors



import org.reactors.common._



/** A basic event stream.
 *
 *  Event streams are special objects that may produce events of a certain type `T`.
 *  Clients may subscribe side-effecting functions (i.e. callbacks)
 *  to these events with `onReaction`, `onEvent`, `onMatch` and `on` --
 *  each of these methods will invoke the callback when an event
 *  is produced, but some may be more suitable depending on the use-case.
 *
 *  An event stream produces events until it ''unreacts''.
 *  After the event stream unreacts, it never produces an event again.
 *  
 *  Event streams can also be manipulated using declarative combinators
 *  such as `map`, `filter`, `until`, `after` and `scanPast`:
 *
 *  {{{
 *  def positiveSquares(r: Events[Int]) = r.map(x => x * x).filter(_ != 0)
 *  }}}
 *
 *  With the exception of `onX` family of methods, and unless otherwise specified,
 *  operators passed to these declarative combinators should be pure -- they should not
 *  have any side-effects.
 *
 *  The result of applying a declarative combinator on `Events[T]` is usually
 *  another `Events[S]`, possibly with a type parameter `S` different from `T`.
 *  Event sink combinators, such as `onEvent`, return a `Subscription` object used to
 *  `unsubscribe` from their event source.
 *
 *  Event streams are specialized for `Int`, `Long` and `Double`.
 *
 *  Every event stream is bound to a specific `Reactor`, the basic unit of concurrency.
 *  Within a reactor, at most a single event stream propagates events at a time.
 *  An event stream will only produce events during the execution of that reactor --
 *  events are never triggered on a different reactor.
 *  It is forbidden to share event streams between reactors.
 *
 *  @author        Aleksandar Prokopec
 *
 *  @tparam T      type of the events in this event stream
 */
trait Events[@spec(Int, Long, Double) T] {

  /** Registers a new `observer` to this event stream.
   *
   *  The `observer` argument may be invoked multiple times -- whenever an event is
   *  produced, or an exception occurs, and at most once when the event stream is
   *  terminated. After the event stream terminates, no events or exceptions are
   *  propagated on this event stream any more.
   *
   *  @param ovserver    the observer for `react`, `except` and `unreact` events
   *  @return            a subscription for unsubscribing from reactions
   */
  def onReaction(observer: Observer[T]): Subscription

  /** Registers callbacks for react and unreact events.
   *
   *  A shorthand for `onReaction` -- the specified functions are invoked whenever there
   *  is an event or an unreaction.
   *
   *  @param reactFunc   called when this event stream produces an event
   *  @param unreactFunc called when this event stream unreacts
   *  @return            a subscription for unsubscribing from reactions
   */
  def onEventOrDone(reactFunc: T => Unit)(unreactFunc: =>Unit): Subscription = {
    val observer = new Events.OnEventOrDone(reactFunc, () => unreactFunc)
    onReaction(observer)
  }

  /** Registers callback for react events.
   *
   *  A shorthand for `onReaction` -- the specified function is invoked whenever
   *  there is an event.
   *
   *  @param observer    the callback for events
   *  @return            a subcriptions for unsubscribing from reactions
   */
  def onEvent(observer: T => Unit): Subscription = {
    onEventOrDone(observer)(() => {})
  }

  /** Registers callback for events that match the specified patterns.
   *
   *  A shorthand for `onReaction` -- the specified partial function is applied
   *  to only those events for which it is defined.
   *  
   *  This method only works for `AnyRef` values.
   *
   *  Example:
   *
   *  {{{
   *  r onMatch {
   *    case s: String => println(s)
   *    case n: Int    => println("number " + s)
   *  }
   *  }}}
   *
   *  '''Use case''':
   *
   *  {{{
   *  def onMatch(reactor: PartialFunction[T, Unit]): Subscription
   *  }}}
   *  
   *  @param observer    the callback for those events for which it is defined
   *  @return            a subscription for unsubscribing from reactions
   */
  def onMatch(observer: PartialFunction[T, Unit])(implicit sub: T <:< AnyRef):
    Subscription = {
    onReaction(new Observer[T] {
      def react(event: T) = {
        if (observer.isDefinedAt(event)) observer(event)
      }
      def except(t: Throwable) {
        throw t
      }
      def unreact() {}
    })
  }

  /** Registers a callback for react events, disregarding their values
   *
   *  A shorthand for `onReaction` -- called whenever an event occurs.
   *
   *  This method is handy when the precise event value is not important,
   *  or the type of the event is `Unit`.
   *
   *  @param observer    the callback invoked when an event arrives
   *  @return            a subscription for unsubscribing from reactions
   */
  def on(observer: =>Unit)(implicit dummy: Spec[T]): Subscription = {
    onReaction(new Events.On[T](() => observer))
  }

  /** Executes the specified block when `this` event stream unreacts.
   *
   *  @param observer    the callback invoked when `this` unreacts
   *  @return            a subscription for the unreaction notification
   */
  def onDone(observer: =>Unit)(implicit dummy: Spec[T]): Subscription = {
    onReaction(new Events.OnDone[T](() => observer))
  }

  /** Executes the specified block when `this` event stream forwards an exception.
   *
   *  @param pf          the partial function used to handle the exception
   *  @return            a subscription for the exception notifications
   */
  def onExcept(pf: PartialFunction[Throwable, Unit]): Subscription = {
    onReaction(new Observer[T] {
      def react(value: T) {}
      def except(t: Throwable) {
        if (pf.isDefinedAt(t)) pf(t)
        else throw t
      }
      def unreact() {}
    })
  }

  /** Transforms emitted exceptions into an event stream.
   *
   *  If the specified partial function is defined for the exception,
   *  an event is emitted.
   *  Otherwise, the same exception is forwarded.
   * 
   *  @tparam U          type of events exceptions are mapped to
   *  @param  pf         partial mapping from functions to events
   *  @return            an event stream that emits events when an exception arrives
   */
  def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit evid: U <:< AnyRef):
    Events[U] =
    new Events.Recover(this, pf, evid)

  /** Returns an event stream that ignores all exceptions in this event stream.
   *
   *  @return            an event stream that forwards all events,
   *                     and ignores all exceptions
   */
  def ignoreExceptions: Events[T] = new Events.IgnoreExceptions(this)

  /** Creates a new event stream `s` that produces events by consecutively
   *  applying the specified operator `op` to the previous event that `s`
   *  produced and the current event that this event stream value produced.
   *
   *  The `scanPast` operation allows the current event from this event stream to be
   *  mapped into a different event by looking "into the past", i.e. at the
   *  event previously emitted by the resulting event stream.
   *
   *  Example -- assume that an event stream `r` produces events `1`, `2` and
   *  `3`. The following `s`:
   *
   *  {{{
   *  val s = r.scanPast(0)((sum, n) => sum + n)
   *  }}}
   *
   *  will produce events `1`, `3` (`1 + 2`) and `6` (`3 + 3`).
   *  '''Note:''' the initial value `0` is '''not emitted'''.
   *  
   *  The `scanPast` can also be used to produce an event stream of a different
   *  type. The following produces a complete history of all the events seen so
   *  far:
   *
   *  {{{
   *  val s2 = r.scanPast(List[Int]()) {
   *    (history, n) => n :: history
   *  }
   *  }}}
   *  
   *  The `s2` will produce events `1 :: Nil`, `2 :: 1 :: Nil` and
   *  `3 :: 2 :: 1 :: Nil`.
   *  '''Note:''' the initial value `Nil` is '''not emitted'''.
   *
   *  This operation is closely related to a `scanLeft` on a collection --
   *  if an event stream were a sequence of elements, then `scanLeft` would
   *  produce a new sequence whose elements correspond to the events of the
   *  resulting event stream.
   *
   *  @tparam S        the type of the events in the resulting event stream
   *  @param z         the initial value of the scan past
   *  @param op        the operator the combines the last produced and the
   *                   current event into a new one
   *  @return          a subscription that is also an event stream that scans
   *                   events from `this` event stream
   */
  def scanPast[@spec(Int, Long, Double) S](z: S)(op: (S, T) => S): Events[S] =
    new Events.ScanPast(this, z, op)

  /** Converts this event stream into a `Signal`.
   *
   *  The resulting signal initially does not contain an event,
   *  and subsequently contains any event that `this` event stream produces.
   *
   *  @param init      an initial value for the signal
   *  @return          the signal version of the current event stream
   */
  def toSignal: Signal[T] with Subscription =
    new Events.ToSignal(this, false, null.asInstanceOf[T])

  /** Given an initial event `init`, converts this event stream into a `Signal`.
   *
   *  The resulting signal initially contains the event `init`,
   *  and subsequently any event that `this` event stream produces.
   *
   *  @param init      an initial value for the signal
   *  @return          the signal version of the current event stream
   */
  def toSignalWith(init: T): Signal[T] with Subscription =
    new Events.ToSignal(this, true, init)

  /** Emits the total number of events produced by this event stream.
   *
   *  The returned value is a [[scala.reactive.Signal]] that holds the total number of
   *  emitted events.
   *
   *  {{{
   *  time  ---------------->
   *  this  ---x---y--z--|
   *  count ---1---2--3--|
   *  }}}
   *
   *  @return           a subscription and an event stream that emits the total number
   *                    of events emitted since `card` was called
   */
  def count(implicit dummy: Spec[T]): Events[Int] = new Events.Count[T](this)

  /** Mutates the target mutable event stream called `mutable` each time `this`
   *  event stream produces an event.
   *
   *  Here is an example, given an event stream of type `r`:
   *
   *  {{{
   *  val eventLog = new Events.Mutable(mutable.Buffer[String]())
   *  val eventLogMutations = r.mutate(eventLog) { buffer => event =>
   *    buffer += "at " + System.nanoTime + ": " + event
   *  } // <-- eventLog event propagated
   *  }}}
   *
   *  Whenever an event arrives on `r`, an entry is added to the buffer underlying
   *  `eventLog`. After the `mutation` completes, a modification event is produced by
   *  the `eventLog` and can be used subsequently:
   *
   *  {{{
   *  val uiUpdates = eventLog onEvent { b =>
   *    eventListWidget.add(b.last)
   *  }
   *  }}}
   *
   *  '''Note:'''
   *  No two events will ever be concurrently processed by different threads on the same
   *  event stream mutable, but an event that is propagated from within the `mutation`
   *  can trigger an event on `this`.
   *  The result is that `mutation` is invoked concurrently on the same thread.
   *  The following code is problematic has a feedback loop in the dataflow graph:
   *  
   *  {{{
   *  val emitter = new Events.Emitter[Int]
   *  val mutable = new Events.Mutable[mutable.Buffer[Int]()]
   *  emitter.mutate(mutable) { buffer => n =>
   *    buffer += n
   *    if (n == 0)
   *      emitter.react(n + 1) // <-- event propagated
   *    assert(buffer.last == n)
   *  }
   *  emitter.react(0)
   *  }}}
   *  
   *  The statement `emitter.react(n + 1)` in the `mutate` block
   *  suspends the current mutation, calls the mutation
   *  recursively and changes the value of `cell`, and the assertion fails when
   *  the first mutation resumes.
   *
   *  Care must be taken to avoid `f` from emitting events in feedback loops.
   *
   *  @tparam M   the type of the event stream mutable value
   *  @param m    the target mutable stream to be mutated with events from this stream
   *  @param f    the function that modifies `mutable` given an event of
   *              type `T`
   *  @return     a subscription used to cancel this mutation
   */
  def mutate[M >: Null <: AnyRef](m: Events.Mutable[M])(f: M => T => Unit):
    Subscription =
    onReaction(new Events.MutateObserver(m, f))

  /** Mutates multiple mutable event stream values `m1` and `m2` each time that `this`
   *  event stream produces an event.
   *
   *  Note that the objects `m1` and `m2` are mutated simultaneously, and events are
   *  propagated after the mutation ends. This version of the `mutate` works on multiple
   *  event streams.
   *  
   *  @tparam M1          the type of the first mutable event stream
   *  @tparam M2          the type of the second mutable event stream
   *  @param m1          the first mutable stream
   *  @param m2          the second mutable stream
   *  @param f           the function that modifies the mutables
   *  @return            a subscription used to cancel this mutation
   */
  def mutate[M1 >: Null <: AnyRef, M2 >: Null <: AnyRef](
    m1: Events.Mutable[M1], m2: Events.Mutable[M2]
  )(f: (M1, M2) => T => Unit): Subscription =
    onReaction(new Events.Mutate2Observer(m1, m2, f))

  /** Mutates multiple mutable event stream values `m1`, `m2` and `m3` each time that
   *  `this` event stream produces an event.
   *
   *  Note that the objects `m1`, `m2` and `m3` are mutated simultaneously, and events
   *  are propagated after the mutation ends. This version of the `mutate` works on
   *  multiple event streams.
   *  
   *  @tparam M1          the type of the first mutable event stream
   *  @tparam M2          the type of the second mutable event stream
   *  @tparam M3          the type of the third mutable event stream
   *  @param m1          the first mutable stream
   *  @param m2          the second mutable stream
   *  @param m3          the second mutable stream
   *  @param f           the function that modifies the mutables
   *  @return            a subscription used to cancel this mutation
   */
  def mutate[M1 >: Null <: AnyRef, M2 >: Null <: AnyRef, M3 >: Null <: AnyRef](
    m1: Events.Mutable[M1], m2: Events.Mutable[M2], m3: Events.Mutable[M3]
  )(f: (M1, M2, M3) => T => Unit)(implicit dummy: Spec[T]): Subscription =
    onReaction(new Events.Mutate3Observer(m1, m2, m3, f))

  /** Creates a new event stream that produces events from `this` event stream only
   *  after `that` produces an event.
   *
   *  After `that` emits some event, all events from `this` are produced on the
   *  resulting event stream.
   *  If `that` unreacts before an event is produced on `this`, the resulting event\
   *  stream unreacts.
   *  If `this` unreacts, the resulting event stream unreacts.
   *
   *  @tparam S          the type of `that` event stream
   *  @param that        the event stream after whose first event the result can start
   *                     propagating events
   *  @return            a subscription and the resulting event stream that emits only
   *                     after `that` emits at least once.
   */
  def after[@spec(Int, Long, Double) S](that: Events[S]): Events[T] =
    new Events.After[T, S](this, that)

  /** Creates a new event stream value that produces events from `this` event stream
   *  value until `that` produces an event.
   *  
   *  If `this` unreacts before `that` produces a value, the resulting event stream
   *  unreacts.
   *  Otherwise, the resulting event stream unreacts whenever `that` produces a
   *  value.
   *
   *  @tparam S         the type of `that` event stream
   *  @param that       the event stream until whose first event the result
   *                    propagates events
   *  @return           a subscription and the resulting event stream that emits
   *                    only until `that` emits
   */
  def until[@spec(Int, Long, Double) S](that: Events[S]): Events[T] =
    new Events.Until[T, S](this, that)

  /** Creates an event stream that forwards an event from this event stream only once.
   *
   *  The resulting event stream emits only a single event produced by `this`
   *  event stream after `once` is called, and then unreacts.
   *
   *  {{{
   *  time ----------------->
   *  this --1-----2----3--->
   *  once      ---2|
   *  }}}
   *
   *  @return           a subscription and an event stream with the first event from
   *                    `this`
   */
  def once: Events[T] = new Events.Once[T](this)

  /** Filters events from `this` event stream value using a specified predicate `p`.
   *
   *  Only events from `this` for which `p` returns `true` are emitted on the
   *  resulting event stream.
   *
   *  @param p          the predicate used to filter events
   *  @return           a subscription and an event streams with the filtered events
   */
  def filter(p: T => Boolean): Events[T] = new Events.Filter(this, p)

  /** Filters events from `this` event stream and maps them in the same time.
   *
   *  The `collect` combinator uses a partial function `pf` to filter events
   *  from `this` event stream. Events for which the partial function is defined
   *  are mapped using the partial function, others are discarded.
   *
   *  '''Note:'''
   *  This combinator is defined only for event streams that contain reference events.
   *  You cannot call it for event streams whose events are primitive values, such as
   *  `Int`. This is because the `PartialFunction` class is not itself specialized.
   *
   *  @tparam S         the type of the mapped event stream
   *  @param pf         partial function used to filter and map events
   *  @param evidence   evidence that `T` is a reference type
   *  @return           a subscription and an event stream with the partially
   *                    mapped events
   */
  def collect[S <: AnyRef](pf: PartialFunction[T, S])(implicit evidence: T <:< AnyRef):
    Events[S] =
    new Events.Collect(this, pf)

  /** Returns a new event stream that maps events from `this` event stream using the
   *  mapping function `f`.
   *
   *  {{{
   *  time    --------------------->
   *  this    --e1------e2------|
   *  mapped  --f(e1)---f(e2)---|
   *  }}}
   *
   *  @tparam S         the type of the mapped events
   *  @param f          the mapping function
   *  @return           a subscription and event stream value with the mapped events
   */
  def map[@spec(Int, Long, Double) S](f: T => S): Events[S] = new Events.Map(this, f)

  /** Returns a new event stream that forwards the events from `this` event stream as
   *  long as they satisfy the predicate `p`.
   *
   *  After an event that does not specify the predicate occurs, the resulting event
   *  stream unreacts.
   *
   *  If the predicate throws an exception, the exceptions is propagated, and the
   *  resulting event stream unreacts.
   *
   *  {{{
   *  time             ------------------------>
   *  this             -0---1--2--3-4--1-5--2-->
   *  takeWhile(_ < 4)     -1--2--3-|---------->
   *  }}}
   *
   *  @param p          the predicate that specifies whether to take the element
   *  @return           a subscription and event stream value with the forwarded events
   */
  def takeWhile(p: T => Boolean): Events[T] = new Events.TakeWhile(this, p)

  /** Returns a new event stream that forwards the events from `this` event stream as
   *  long as they satisfy the predicate `p`.
   *
   *  After an event that does not specify the predicate occurs, the resulting event
   *  stream unreacts.
   *
   *  If the predicate throws an exception, the exceptions is propagated, and the
   *  resulting event stream unreacts.
   *
   *  {{{
   *  time             ------------------------>
   *  this             -0---1--2--3-4--1-5--2-->
   *  takeWhile(_ < 4)     -1--2--3-|---------->
   *  }}}
   *
   *  @param p          the predicate that specifies whether to take the element
   *  @return           a subscription and event stream value with the forwarded events
   */
  def dropWhile(p: T => Boolean): Events[T] = new Events.DropWhile(this, p)

  /** Returns events from the last event stream that `this` emitted as an
   *  event of its own, in effect multiplexing the nested reactives.
   *
   *  The resulting event stream only emits events from the event stream last
   *  emitted by `this`, the preceding event streams are ignored.
   *
   *  This combinator is only available if this event stream emits events
   *  that are themselves event streams.
   *
   *  Example:
   *
   *  {{{
   *  val currentEvents = new Events.Emitter[Events[Int]]
   *  val e1 = new Events.Emitter[Int]
   *  val e2 = new Events.Emitter[Int]
   *  val currentEvent = currentEvents.mux()
   *  val prints = currentEvent.onEvent(println) 
   *  
   *  currentEvents.react(e1)
   *  e2.react(1) // nothing is printed
   *  e1.react(2) // 2 is printed
   *  currentEvents.react(e2)
   *  e2.react(6) // 6 is printed
   *  e1.react(7) // nothing is printed
   *  }}}
   *
   *  Shown on the diagram:
   *
   *  {{{
   *  time            ------------------->
   *  currentEvents   --e1------e2------->
   *  e1              --------2----6----->
   *  e2              -----1----------7-->
   *  currentEvent    --------2----6----->
   *  }}}
   *
   *  '''Use case:'''
   *
   *  {{{
   *  def mux[S](): Events[S]
   *  }}}
   *
   *  @tparam S          the type of the events in the nested event stream
   *  @param evidence    an implicit evidence that `this` event stream is nested -- it
   *                     emits events of type `T` that is actually an `Events[S]`
   *  @return            event stream of events from the event stream last emitted by
   *                     `this`
   */
  def mux[@spec(Int, Long, Double) S](
    implicit evidence: T <:< Events[S], ds: Spec[S]
  ): Events[S] =
    new Events.Mux[T, S](this, evidence)

  /** Returns a new event stream that emits an event when this event stream unreacts.
   *
   *  After the current event stream unreacts, the result event stream first emits an
   *  event of type `Unit`, and then unreacts itself.
   *
   *  Exceptions from this event stream are propagated until the resulting event stream
   *  unreacts -- after that, `this` event stream is not allowed to produce exceptions.
   *
   *  Shown on the diagram:
   *
   *  {{{
   *  time            ------------------->
   *  this            --1--2-----3-|----->
   *  currentEvent    -------------()-|-->
   *  }}}
   *
   *  @return           the unreaction event stream and subscription
   */
  def unreacted(implicit ds: Spec[T]): Events[Unit] = new Events.Unreacted(this)

}


/** Contains useful `Events` implementations and factory methods.
 */
object Events {
  private val bufferUpperBound = 8

  private val hashTableLowerBound = 5

  /** The default implementation of a event stream value.
   *
   *  Keeps an optimized weak collection of weak references to subscribers.
   *  References to subscribers that are no longer reachable in the application
   *  will be removed eventually.
   *
   *  @tparam T       type of the events in this event stream value
   */
  private[reactors] trait Default[@spec(Int, Long, Double) T] extends Events[T] {
    private[reactors] var demux: AnyRef = null
    private[reactors] var eventsUnreacted: Boolean = false
    def onReaction(observer: Observer[T]): Subscription = {
      if (eventsUnreacted) {
        observer.unreact()
        Subscription.empty
      } else {
        demux match {
          case null =>
            demux = new Ref(observer)
          case w: Ref[Observer[T] @unchecked] =>
            val wb = new FastBuffer[Observer[T]]
            wb.addRef(w)
            wb.addEntry(observer)
            demux = wb
          case wb: FastBuffer[Observer[T] @unchecked] =>
            if (wb.size < bufferUpperBound) {
              wb.addEntry(observer)
            } else {
              val wht = toHashTable(wb)
              wht.addEntry(observer)
              demux = wht
            }
          case wht: FastHashTable[Observer[T] @unchecked] =>
            wht.addEntry(observer)
        }
        newSubscription(observer)
      }
    }
    private def newSubscription(r: Observer[T]) = new Subscription {
      def unsubscribe() {
        removeReaction(r)
      }
    }
    private def removeReaction(r: Observer[T]) {
      demux match {
        case null =>
          // nothing to invalidate
        case w: Ref[Observer[T] @unchecked] =>
          if (w.get eq r) w.clear()
        case wb: FastBuffer[Observer[T] @unchecked] =>
          wb.invalidateEntry(r)
        case wht: FastHashTable[Observer[T] @unchecked] =>
          wht.invalidateEntry(r)
      }
    }
    def reactAll(value: T) {
      demux match {
        case null =>
          // no need to inform anybody
        case w: Ref[Observer[T] @unchecked] =>
          val r = w.get
          if (r != null) r.react(value)
          else demux = null
        case wb: FastBuffer[Observer[T] @unchecked] =>
          bufferReactAll(wb, value)
        case wht: FastHashTable[Observer[T] @unchecked] =>
          tableReactAll(wht, value)
      }
    }
    def exceptAll(t: Throwable) {
      demux match {
        case null =>
          // no need to inform anybody
        case w: Ref[Observer[T] @unchecked] =>
          val r = w.get
          if (r != null) r.except(t)
          else demux = null
        case wb: FastBuffer[Observer[T] @unchecked] =>
          bufferExceptAll(wb, t)
        case wht: FastHashTable[Observer[T] @unchecked] =>
          tableExceptAll(wht, t)
      }
    }
    def unreactAll() {
      eventsUnreacted = true
      demux match {
        case null =>
          // no need to inform anybody
        case w: Ref[Observer[T] @unchecked] =>
          val r = w.get
          if (r != null) r.unreact()
          else demux = null
        case wb: FastBuffer[Observer[T] @unchecked] =>
          bufferUnreactAll(wb)
        case wht: FastHashTable[Observer[T] @unchecked] =>
          tableUnreactAll(wht)
      }
    }
    private def checkBuffer(wb: FastBuffer[Observer[T]]) {
      if (wb.size <= 1) {
        if (wb.size == 1) demux = new Ref(wb(0))
        else if (wb.size == 0) demux = null
      }
    }
    private def bufferReactAll(wb: FastBuffer[Observer[T]], value: T) {
      val array = wb.array
      var until = wb.size
      var i = 0
      while (i < until) {
        val ref = array(i)
        val r = ref.get
        if (r ne null) {
          r.react(value)
          i += 1
        } else {
          wb.removeEntryAt(i)
          until -= 1
        }
      }
      checkBuffer(wb)
    }
    private def bufferExceptAll(wb: FastBuffer[Observer[T]], t: Throwable) {
      val array = wb.array
      var until = wb.size
      var i = 0
      while (i < until) {
        val ref = array(i)
        val r = ref.get
        if (r ne null) {
          r.except(t)
          i += 1
        } else {
          wb.removeEntryAt(i)
          until -= 1
        }
      }
      checkBuffer(wb)
    }
    private def bufferUnreactAll(wb: FastBuffer[Observer[T]]) {
      val array = wb.array
      var until = wb.size
      var i = 0
      while (i < until) {
        val ref = array(i)
        val r = ref.get
        if (r ne null) {
          r.unreact()
          i += 1
        } else {
          wb.removeEntryAt(i)
          until -= 1
        }
      }
      checkBuffer(wb)
    }
    private def toHashTable(wb: FastBuffer[Observer[T]]) = {
      val wht = new FastHashTable[Observer[T]]
      val array = wb.array
      val until = wb.size
      var i = 0
      while (i < until) {
        val r = array(i).get
        if (r != null) wht.addEntry(r)
        i += 1
      }
      wht
    }
    private def toBuffer(wht: FastHashTable[Observer[T]]) = {
      val wb = new FastBuffer[Observer[T]](bufferUpperBound)
      val table = wht.table
      var i = 0
      while (i < table.length) {
        var ref = table(i)
        if (ref != null && ref.get != null) wb.addRef(ref)
        i += 1
      }
      wb
    }
    private def cleanHashTable(wht: FastHashTable[Observer[T]]) {
      val table = wht.table
      var i = 0
      while (i < table.length) {
        val ref = table(i)
        if (ref ne null) {
          val r = ref.get
          if (r eq null) wht.removeEntryAt(i, null)
        }
        i += 1
      }
    }
    private def checkHashTable(wht: FastHashTable[Observer[T]]) {
      if (wht.size < hashTableLowerBound) {
        val wb = toBuffer(wht)
        demux = wb
        checkBuffer(wb)
      }
    }
    private def tableReactAll(wht: FastHashTable[Observer[T]], value: T) {
      val table = wht.table
      var i = 0
      while (i < table.length) {
        val ref = table(i)
        if (ref ne null) {
          val r = ref.get
          if (r ne null) r.react(value)
        }
        i += 1
      }
      cleanHashTable(wht)
      checkHashTable(wht)
    }
    private def tableExceptAll(wht: FastHashTable[Observer[T]], t: Throwable) {
      val table = wht.table
      var i = 0
      while (i < table.length) {
        val ref = table(i)
        if (ref ne null) {
          val r = ref.get
          if (r ne null) r.except(t)
        }
        i += 1
      }
      cleanHashTable(wht)
      checkHashTable(wht)
    }
    private def tableUnreactAll(wht: FastHashTable[Observer[T]]) {
      val table = wht.table
      var i = 0
      while (i < table.length) {
        val ref = table(i)
        if (ref ne null) {
          val r = ref.get
          if (r ne null) r.unreact()
        }
        i += 1
      }
      cleanHashTable(wht)
      checkHashTable(wht)
    }
  }

  /** Event source that emits events when `react`, `except` or `unreact` is called.
   *
   *  Emitter is simultaneously an event stream, and an observer.
   */
  class Emitter[@spec(Int, Long, Double) T]
  extends Default[T] with Events[T] with Observer[T] {
    private var table = new FastHashTable[Observer[T]]
    private var closed = false
    def react(x: T) = if (!closed) {
      reactAll(x)
    }
    def except(t: Throwable) = if (!closed) {
      exceptAll(t)
    }
    def unreact() = if (!closed) {
      closed = true
      unreactAll()
    }
  }

  /** An event stream that emits events when the underlying mutable object is modified.
   *
   *  An event with underlying mutable value is emitted whenever the mutable value was
   *  potentially mutated. This type of a signal provides a controlled way of
   *  manipulating mutable values.
   *
   *  '''Note:'''
   *  The underlying mutable object must '''never''' be mutated directly by accessing
   *  the value of the signal and changing the mutable object. Instead, the `mutate`
   *  operation of `Events` should be used to mutate the underlying mutable object.
   *
   *  Example:
   *
   *  {{{
   *  val systemMessages = new Events.Emitter[String]
   *  val log = new Events.Mutable(new mutable.ArrayBuffer[String])
   *  val logMutations = systemMessages.mutate(log) { buffer => msg =>
   *    buffer += msg
   *  }
   *  systemMessages.react("New message arrived!") // buffer now contains the message
   *  }}}
   *
   *  As long as there are no feedback loops in the dataflow graph,
   *  the same thread will never modify the mutable object at the same time.
   *  See the `mutate` method on `Events`s for more information.
   *
   *  Note that mutable event stream never unreacts.
   *
   *  @see [[scala.reactive.Events]]
   *  @tparam M          the type of the underlying mutable object
   *  @param content     the mutable object
   */
  class Mutable[M >: Null <: AnyRef](private[reactors] val content: M)
  extends Default[M] with Events[M]

  private[reactors] class MutateObserver[
    @spec(Int, Long, Double) T, M >: Null <: AnyRef
  ](val target: Mutable[M], f: M => T => Unit) extends Observer[T] {
    val mutation = f(target.content)
    def react(x: T) = {
      try mutation(x)
      catch {
        case NonLethal(t) => target.exceptAll(t)
      }
      target.reactAll(target.content)
    }
    def except(t: Throwable) = target.exceptAll(t)
    def unreact() = {}
  }

  private[reactors] class Mutate2Observer[
    @spec(Int, Long, Double) T, M1 >: Null <: AnyRef, M2 >: Null <: AnyRef
  ](val t1: Mutable[M1], val t2: Mutable[M2], f: (M1, M2) => T => Unit)
  extends Observer[T] {
    val mutation = f(t1.content, t2.content)
    def react(x: T) = {
      try mutation(x)
      catch {
        case NonLethal(t) =>
          t1.exceptAll(t)
          t2.exceptAll(t)
      }
      t1.reactAll(t1.content)
      t2.reactAll(t2.content)
    }
    def except(t: Throwable) = {
      t1.exceptAll(t)
      t2.exceptAll(t)
    }
    def unreact() = {}
  }

  private[reactors] class Mutate3Observer[
    @spec(Int, Long, Double) T,
    M1 >: Null <: AnyRef, M2 >: Null <: AnyRef, M3 >: Null <: AnyRef
  ](
    val t1: Mutable[M1], val t2: Mutable[M2], val t3: Mutable[M3],
    f: (M1, M2, M3) => T => Unit
  ) extends Observer[T] {
    val mutation = f(t1.content, t2.content, t3.content)
    def react(x: T) = {
      try mutation(x)
      catch {
        case NonLethal(t) =>
          t1.exceptAll(t)
          t2.exceptAll(t)
          t3.exceptAll(t)
      }
      t1.reactAll(t1.content)
      t2.reactAll(t2.content)
      t3.reactAll(t3.content)
    }
    def except(t: Throwable) = {
      t1.exceptAll(t)
      t2.exceptAll(t)
      t3.exceptAll(t)
    }
    def unreact() = {}
  }

  private[reactors] class OnEventOrDone[@spec(Int, Long, Double) T](
    val reactFunc: T => Unit, val unreactFunc: () => Unit
  ) extends Observer[T] {
    def react(x: T) = reactFunc(x)
    def except(t: Throwable) = throw t
    def unreact() = unreactFunc()
  }

  private[reactors] class On[@spec(Int, Long, Double) T](
    val reactFunc: () => Unit
  ) extends Observer[T] {
    def react(x: T) = reactFunc()
    def except(t: Throwable) = throw t
    def unreact() = {}
  }

  private[reactors] class OnDone[@spec(Int, Long, Double) T](
    val unreactFunc: () => Unit
  ) extends Observer[T] {
    def react(x: T) = {}
    def except(t: Throwable) = throw t
    def unreact() = unreactFunc()
  }

  private[reactors] class Recover[T, U >: T](
    val self: Events[T],
    val pf: PartialFunction[Throwable, U],
    val evid: U <:< AnyRef
  ) extends Events[U] {
    def onReaction(observer: Observer[U]): Subscription =
      self.onReaction(new RecoverObserver(observer, pf, evid))
  }

  private[reactors] class RecoverObserver[T, U >: T](
    val target: Observer[U],
    val pf: PartialFunction[Throwable, U],
    val evid: U <:< AnyRef
  ) extends Observer[T] {
    def react(value: T) {
      target.react(value)
    }
    def except(t: Throwable) {
      val isDefined = try {
        pf.isDefinedAt(t)
      } catch {
        case other if isNonLethal(other) =>
          target.except(other)
          return
      }
      if (!isDefined) target.except(t)
      else {
        val event = try {
          pf(t)
        } catch {
          case other if isNonLethal(other) =>
            target.except(other)
            return
        }
        target.react(event)
      }
    }
    def unreact() {
      target.unreact()
    }
  }

  private[reactors] class IgnoreExceptions[@spec(Int, Long, Double) T](
    val self: Events[T]
  ) extends Events[T] {
    def onReaction(observer: Observer[T]): Subscription =
      self.onReaction(new IgnoreExceptionsObserver(observer))
  }

  private[reactors] class IgnoreExceptionsObserver[@spec(Int, Long, Double) T](
    val target: Observer[T]
  ) extends Observer[T] {
    def react(value: T) {
      target.react(value)
    }
    def except(t: Throwable) {
    }
    def unreact() {
      target.unreact()
    }
  }

  private[reactors] class ScanPast[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val self: Events[T],
    val z: S,
    val op: (S, T) => S
  ) extends Events[S] {
    def onReaction(observer: Observer[S]): Subscription =
      self.onReaction(new ScanPastObserver(observer, z, op))
  }

  private[reactors] class ScanPastObserver[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val target: Observer[S],
    private var cached: S,
    val op: (S, T) => S
  ) extends Observer[T] {
    def apply() = cached
    def react(value: T) {
      try {
        cached = op(cached, value)
      } catch {
        case t if isNonLethal(t) =>
          target.except(t)
          return
      }
      target.react(cached)
    }
    def except(t: Throwable) {
      target.except(t)
    }
    def unreact() {
      target.unreact()
    }
  }

  private[reactors] class ToSignal[@spec(Int, Long, Double) T](
    val self: Events[T],
    private var full: Boolean,
    private var cached: T
  ) extends Signal[T] with Observer[T] with Default[T] with Subscription.Proxy {
    private var rawSubscription = Subscription.empty
    def subscription = rawSubscription
    def init(dummy: T) {
      rawSubscription = self.onReaction(this)
    }
    init(cached)
    def apply(): T = {
      if (full) cached
      else throw new NoSuchElementException
    }
    def react(x: T) {
      cached = x
      if (!full) full = true
      reactAll(x)
    }
    def except(t: Throwable) = exceptAll(t)
    def unreact() = unreactAll()
  }

  private[reactors] class Count[@spec(Int, Long, Double) T](val self: Events[T])
  extends Events[Int] {
    private def newCountObserver(observer: Observer[Int]): Observer[T] =
      new CountObserver[T](observer)
    def onReaction(observer: Observer[Int]): Subscription =
      self.onReaction(newCountObserver(observer))
  }

  private[reactors] class CountObserver[@spec(Int, Long, Double) T](
    val target: Observer[Int]
  ) extends Observer[T] {
    private var cnt: Int = _
    def init(dummy: Observer[T]) {
      cnt = 0
    }
    init(this)
    def apply() = cnt
    def react(value: T) {
      cnt += 1
      target.react(cnt)
    }
    def except(t: Throwable) {
      target.except(t)
    }
    def unreact() {
      target.unreact()
    }
  }

  private[reactors] class After[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val self: Events[T],
    val that: Events[S]
  ) extends Events[T] {
    def onReaction(observer: Observer[T]): Subscription = {
      val afterObserver = new AfterObserver(observer, that)
      val afterThatObserver = new AfterThatObserver[T, S](afterObserver)
      val sub = self.onReaction(afterObserver)
      val subThat = that.onReaction(afterThatObserver)
      afterThatObserver.subscription = subThat
      new Subscription.Composite(sub, subThat)
    }
  }

  private[reactors] class AfterObserver[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val target: Observer[T],
    val that: Events[S]
  ) extends Observer[T] {
    private[reactors] var rawStarted = false
    private[reactors] var rawLive = true
    def started = rawStarted
    def started_=(v: Boolean) = rawStarted = v
    def live = rawLive
    def live_=(v: Boolean) = rawLive = v
    def unreactBoth() = if (live) {
      live = false
      target.unreact()
    }
    def react(value: T) {
      if (started) target.react(value)
    }
    def except(t: Throwable) {
      target.except(t)
    }
    def unreact() = unreactBoth()
  }

  private[reactors] class AfterThatObserver[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](val afterObserver: AfterObserver[T, S]) extends Observer[S] {
    var subscription = Subscription.empty
    def react(value: S) {
      if (!afterObserver.started) {
        afterObserver.started = true
        subscription.unsubscribe()
      }
    }
    def except(t: Throwable) {
      if (!afterObserver.started) afterObserver.target.except(t)
    }
    def unreact() = if (!afterObserver.started) afterObserver.unreactBoth()
  }

  private[reactors] class Until[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val self: Events[T],
    val that: Events[S]
  ) extends Events[T] {
    def onReaction(observer: Observer[T]): Subscription = {
      val untilObserver = new UntilObserver(observer, that)
      val untilThatObserver = new UntilThatObserver[T, S](untilObserver)
      val sub = new Subscription.Composite(
        self.onReaction(untilObserver),
        that.onReaction(untilThatObserver)
      )
      untilObserver.subscription = sub
      sub
    }
  }

  private[reactors] class UntilObserver[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val target: Observer[T],
    val that: Events[S]
  ) extends Observer[T] {
    private[reactors] var rawLive = true
    var subscription = Subscription.empty
    def live = rawLive
    def live_=(v: Boolean) = rawLive = v
    def unreactBoth() = if (live) {
      live = false
      subscription.unsubscribe()
      target.unreact()
    }
    def react(value: T) {
      if (live) target.react(value)
    }
    def except(t: Throwable) {
      if (live) target.except(t)
    }
    def unreact() = unreactBoth()
  }

  private[reactors] class UntilThatObserver[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](val untilObserver: UntilObserver[T, S]) extends Observer[S] {
    def react(value: S) = untilObserver.unreactBoth()
    def except(t: Throwable) {
      if (untilObserver.live) untilObserver.target.except(t)
    }
    def unreact() = {}
  }

  private[reactors] class Once[@spec(Int, Long, Double) T](val self: Events[T])
  extends Events[T] {
    def onReaction(observer: Observer[T]): Subscription = {
      val obs = new OnceObserver(observer)
      val sub = self.onReaction(obs)
      obs.subscription = sub
      sub
    }
  }

  private[reactors] class OnceObserver[@spec(Int, Long, Double) T](
    val target: Observer[T]
  ) extends Observer[T] {
    private var seen: Boolean = _
    var subscription = Subscription.empty
    def init(dummy: Observer[T]) {
      seen = false
    }
    init(this)
    def react(value: T) = if (!seen) {
      seen = true
      subscription.unsubscribe()
      target.react(value)
      target.unreact()
    }
    def except(t: Throwable) = if (!seen) {
      target.except(t)
    }
    def unreact() = if (!seen) {
      target.unreact()
    }
  }

  private[reactors] class Filter[@spec(Int, Long, Double) T](
    val self: Events[T],
    val p: T => Boolean
  ) extends Events[T] {
    def onReaction(observer: Observer[T]): Subscription =
      self.onReaction(new FilterObserver(observer, p))
  }

  private[reactors] class FilterObserver[@spec(Int, Long, Double) T](
    val target: Observer[T],
    val p: T => Boolean
  ) extends Observer[T] {
    def react(value: T) = {
      val ok = try p(value) catch {
        case NonLethal(t) =>
          target.except(t)
          false
      }
      if (ok) target.react(value)
    }
    def except(t: Throwable) = {
      target.except(t)
    }
    def unreact() = {
      target.unreact()
    }
  }

  private[reactors] class Collect[T, S <: AnyRef](
    val self: Events[T],
    val pf: PartialFunction[T, S]
  ) extends Events[S] {
    def onReaction(observer: Observer[S]): Subscription =
      self.onReaction(new CollectObserver(observer, pf))
  }

  private[reactors] class CollectObserver[T, S <: AnyRef](
    val target: Observer[S],
    val pf: PartialFunction[T, S]
  ) extends Observer[T] {
    def react(value: T) = {
      val ok = try pf.isDefinedAt(value) catch {
        case NonLethal(t) =>
          target.except(t)
          false
      }
      if (ok) target.react(pf(value))
    }
    def except(t: Throwable) {
      target.except(t)
    }
    def unreact() {
      target.unreact()
    }
  }

  private[reactors] class Map[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val self: Events[T],
    val f: T => S
  ) extends Events[S] {
    def onReaction(observer: Observer[S]): Subscription =
      self.onReaction(new MapObserver(observer, f))
  }

  private[reactors] class MapObserver[
    @spec(Int, Long, Double) T,
    @spec(Int, Long, Double) S
  ](
    val target: Observer[S],
    val f: T => S
  ) extends Observer[T] {
    def react(value: T) {
      val x = try {
        f(value)
      } catch {
        case NonLethal(t) =>
          target.except(t)
          return
      }
      target.react(x)
    }
    def except(t: Throwable) {
      target.except(t)
    }
    def unreact() {
      target.unreact()
    }
  }

  private[reactors] class TakeWhile[@spec(Int, Long, Double) T](
    val self: Events[T],
    val p: T => Boolean
  ) extends Events[T] {
    def onReaction(observer: Observer[T]): Subscription = {
      val obs = new TakeWhileObserver(observer, p)
      val sub = self.onReaction(obs)
      obs.subscription = sub
      sub
    }
  }

  private[reactors] class TakeWhileObserver[@spec(Int, Long, Double) T](
    val target: Observer[T],
    val p: T => Boolean
  ) extends Observer[T] {
    private var closed: Boolean = _
    var subscription = Subscription.empty
    def init(dummy: Observer[T]) {
      closed = false
    }
    init(this)
    def react(value: T) = if (!closed) {
      if (p(value)) target.react(value)
      else {
        closed = true
        subscription.unsubscribe()
        target.unreact()
      }
    }
    def except(t: Throwable) = if (!closed) {
      target.except(t)
    }
    def unreact() = if (!closed) {
      target.unreact()
    }
  }

  private[reactors] class DropWhile[@spec(Int, Long, Double) T](
    val self: Events[T],
    val p: T => Boolean
  ) extends Events[T] {
    def onReaction(observer: Observer[T]): Subscription =
      self.onReaction(new DropWhileObserver(observer, p))
  }

  private[reactors] class DropWhileObserver[@spec(Int, Long, Double) T](
    val target: Observer[T],
    val p: T => Boolean
  ) extends Observer[T] {
    private var started: Boolean = _
    def init(dummy: Observer[T]) {
      started = false
    }
    init(this)
    def react(value: T) = {
      if (!started && !p(value)) started = true
      if (started) target.react(value)
    }
    def except(t: Throwable) = target.except(t)
    def unreact() = target.unreact()
  }

  private[reactors] class Mux[T, @spec(Int, Long, Double) S: Spec](
    val self: Events[T],
    val evid: T <:< Events[S]
  ) extends Events[S] {
    def onReaction(observer: Observer[S]): Subscription =
      self.onReaction(new MuxObserver(observer, evid))
  }

  private[reactors] class MuxObserver[T, @spec(Int, Long, Double) S: Spec](
    val target: Observer[S],
    val evidence: T <:< Events[S]
  ) extends Observer[T] {
    private[reactors] var currentSubscription: Subscription = _
    private[reactors] var terminated = false
    def init(e: T <:< Events[S]) {
      currentSubscription = Subscription.empty
    }
    init(evidence)
    def checkUnreact() =
      if (terminated && currentSubscription == Subscription.empty) target.unreact()
    def newMuxNestedObserver: Observer[S] = new MuxNestedObserver(target, this)
    def react(value: T): Unit = if (!terminated) {
      val nextEvents = try {
        evidence(value)
      } catch {
        case t if isNonLethal(t) =>
          target.except(t)
          return
      }
      currentSubscription.unsubscribe()
      currentSubscription = nextEvents.onReaction(newMuxNestedObserver)
    }
    def except(t: Throwable) = if (!terminated) {
      target.except(t)
    }
    def unreact() = if (!terminated) {
      terminated = true
      checkUnreact()
    }
  }

  private[reactors] class MuxNestedObserver[T, @spec(Int, Long, Double) S: Spec](
    val target: Observer[S],
    val muxObserver: MuxObserver[T, S]
  ) extends Observer[S] {
    def react(value: S) = target.react(value)
    def except(t: Throwable) = target.except(t)
    def unreact() {
      muxObserver.currentSubscription = Subscription.empty
      muxObserver.checkUnreact()
    }
  }

  private[reactors] class Unreacted[@spec(Int, Long, Double) T](
    val self: Events[T]
  ) extends Events[Unit] {
    def newUnreactedObserver(obs: Observer[Unit]): UnreactedObserver[T] =
      new UnreactedObserver[T](obs)
    def onReaction(observer: Observer[Unit]): Subscription = {
      val obs = newUnreactedObserver(observer)
      val sub = self.onReaction(obs)
      obs.subscription = sub
      sub
    }
  }

  private[reactors] class UnreactedObserver[@spec(Int, Long, Double) T](
    val target: Observer[Unit]
  ) extends Observer[T] {
    var subscription = Subscription.empty
    def react(value: T) = {}
    def except(t: Throwable) = target.except(t)
    def unreact() {
      target.react(())
      target.unreact()
      subscription.unsubscribe()
    }
  }

}
