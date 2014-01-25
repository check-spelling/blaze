package blaze.channel.nio1

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.tailrec
import java.nio.channels._
import java.nio.ByteBuffer
import scala.concurrent.{Future, Promise}
import blaze.pipeline.{Command, PipelineBuilder, HeadStage}
import blaze.channel.PipeFactory
import scala.util.Try
import java.io.IOException
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.util.concurrent.atomic.AtomicReference

/**
 * @author Bryce Anderson
 *         Created on 1/20/14
 */

final class SelectorLoop(selector: Selector, bufferSize: Int) extends Thread("SelectorLoop") with StrictLogging { self =>

//  private val pendingTasks = new ConcurrentLinkedQueue[Runnable]()
  private class Node(val runnable: Runnable) extends AtomicReference[Node]

  private val queueHead = new AtomicReference[Node](null)
  private val queueTail = new AtomicReference[Node](null)

  private val scratch = ByteBuffer.allocate(bufferSize)

  def executeTask(r: Runnable) {
    if (Thread.currentThread() == self) r.run()
    else enqueTask(r)
  }

  def enqueTask(r: Runnable): Unit = {
    val node = new Node(r)
    val head = queueHead.getAndSet(node)
    if (head eq null) {
      queueTail.set(node)
      wakeup()
    } else head.lazySet(node)
  }

  private def runTasks() {
    @tailrec def spin(n: Node): Node = {
      logger.error("Spinning.")
      val next = n.get()
      if (next ne null) next
      else spin(n)
    }

    @tailrec
    def go(n: Node): Unit = {
      try n.runnable.run()
      catch { case t: Exception => logger.error("Caught exception in queued task", t) }
      val next = n.get()
      if (next eq null) {
        // If we are not the last cell, we will spin until the cons resolves and continue
        if (!queueHead.compareAndSet(n, null)) go(spin(n))
      }
      else go(next)
    }

    val t = queueTail.get()
    if (t ne null) {
      queueTail.lazySet(null)
      go(t)
    }
  }

  override def run() = loop()

  private def loop() {

    try while(true) {
      // Run any pending tasks. It is theoretically possible for the loop to
      // Tasks are run first to add any pending OPS before waiting on the selector but that is
      // moot because a call to wakeup if the selector is not waiting on a select call will
      // cause the next invocation of selector.select() to return immediately per spec.
      runTasks()


    // Block here for a bit until an operation happens or we are awaken by an operation
      selector.select()
      val it = selector.selectedKeys().iterator()


      while(it.hasNext) {
        val k = it.next()
        it.remove()

        try {
          if (k.isValid) {
            val head = k.attachment().asInstanceOf[NIOHeadStage]

            if (head != null) {
              logger.debug{"selection key interests: " +
                "write: " +  k.isWritable +
                ", read: " + k.isReadable }

              val readyOps: Int = k.readyOps()

              if ((readyOps & SelectionKey.OP_READ) != 0) {
                val rt = head.ops.performRead(scratch)
                if (rt != null){
                  head.ops.unsetOp(SelectionKey.OP_READ)
                  head.completeRead(rt)
                }
              }

              if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                val buffers = head.getWrites()
                assert(buffers != null)

                val t = head.ops.performWrite(scratch, buffers)
                if (t != null) {
                  head.ops.unsetOp(SelectionKey.OP_WRITE)
                  head.completeWrite(t)
                }
              }
            }
            else {   // Null head. Must be disconnected
              logger.warn("Selector key had null attachment. Why is the key still in the ops?")
            }

          }
        } catch { case e: CancelledKeyException => /* NOOP */ }
      }

    } catch {
      case e: IOException =>
        logger.error("IOException in SelectorLoop", e)

      case e: ClosedSelectorException =>
        logger.error("Selector unexpectedly closed", e)
        killSelector()

      case e: Throwable =>
        logger.error("Unhandled exception in selector loop", e)
    }
  }

  def close() {
    killSelector()
  }

  @throws[IOException]
  private def killSelector() {
    import scala.collection.JavaConversions._

    selector.keys().foreach { k =>
      try {
        k.channel().close()
        k.attach(null)
      } catch { case _: IOException => /* NOOP */ }
    }

    selector.close()
  }

  def wakeup(): Unit = selector.wakeup()

  def initChannel(builder: PipeFactory, ch: SelectableChannel, ops: SelectionKey => ChannelOps) {
   enqueTask( new Runnable {
      def run() {
        try {
          ch.configureBlocking(false)
          val key = ch.register(selector, 0)

          val head = new NIOHeadStage(ops(key))
          key.attach(head)

          // construct the pipeline
          builder(PipelineBuilder(head))

          head.inboundCommand(Command.Connected)
          logger.trace("Started channel.")
        } catch { case e: Throwable => logger.error("Caught error during channel init.", e) }
      }
    })
  }

  private class NIOHeadStage(private[SelectorLoop] val ops: ChannelOps) extends HeadStage[ByteBuffer] {
    def name: String = "NIO1 ByteBuffer Head Stage"

//    private val writeLock = new AnyRef
    private val writeQueue = new AtomicReference[Array[ByteBuffer]](null)
    @volatile private var _writePromise: Promise[Any] = null

    @volatile private var _readPromise: Promise[ByteBuffer] = null

    ///  channel reading bits //////////////////////////////////////////////
    
    def completeRead(t: Try[ByteBuffer]) {
      val p = _readPromise
      _readPromise = null
      p.complete(t)
    }

    def readRequest(size: Int): Future[ByteBuffer] = {
      logger.trace("NIOHeadStage received a read request")

      if (_readPromise != null) Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
      else {
        _readPromise = Promise[ByteBuffer]
        //ops.setOp(SelectionKey.OP_READ)  // Already in SelectorLoop
        ops.setRead()

        _readPromise.future
      }
    }
    
    /// channel write bits /////////////////////////////////////////////////

    def writeRequest(data: ByteBuffer): Future[Any] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[ByteBuffer]): Future[Any] = {
      val writes = data.toArray
      if (!writeQueue.compareAndSet(null, writes)) Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
      else {
        _writePromise = Promise[Any]
        //ops.setOp(SelectionKey.OP_WRITE)  // Already in SelectorLoop
        ops.setWrite()
        _writePromise.future
      }
    }

    def getWrites(): Array[ByteBuffer] = writeQueue.get

    def completeWrite(t: Try[Any]): Unit = {
      val p = _writePromise
      _writePromise = null
      writeQueue.lazySet(null)
      p.complete(t)
    }
    
    /// set the channel interests //////////////////////////////////////////

    override protected def stageShutdown(): Unit = {
      super.stageShutdown()
      ops.close()
    }
  }

}