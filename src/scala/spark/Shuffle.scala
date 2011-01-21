package spark

import java.net._
import java.util.{BitSet}
import java.util.concurrent.{Executors, ThreadFactory, ThreadPoolExecutor}

/**
 * A trait for shuffle system. Given an input RDD and combiner functions
 * for PairRDDExtras.combineByKey(), returns an output RDD.
 */
@serializable
trait Shuffle[K, V, C] {
  def compute(input: RDD[(K, V)],
              numOutputSplits: Int,
              createCombiner: V => C,
              mergeValue: (C, V) => C,
              mergeCombiners: (C, C) => C)
  : RDD[(K, C)]
}

/**
 * An object containing common shuffle config parameters
 */
private object Shuffle 
extends Logging {
  // Tracker communication constants
  val ReducerEntering = 0
  val ReducerLeaving = 1
  val ReducerCompleted = 2

  // ShuffleTracker info
  private var MasterHostAddress_ = System.getProperty(
    "spark.shuffle.masterHostAddress", InetAddress.getLocalHost.getHostAddress)
  private var MasterTrackerPort_ = System.getProperty(
    "spark.shuffle.masterTrackerPort", "22222").toInt

  private var BlockSize_ = System.getProperty(
    "spark.shuffle.blockSize", "1024").toInt * 1024

  // Used thoughout the code for small and large waits/timeouts
  private var MinKnockInterval_ = System.getProperty(
    "spark.shuffle.minKnockInterval", "1000").toInt
  private var MaxKnockInterval_ =  System.getProperty(
    "spark.shuffle.maxKnockInterval", "5000").toInt

  // Maximum number of connections
  private var MaxRxConnections_ = System.getProperty(
    "spark.shuffle.maxRxConnections", "4").toInt
  private var MaxTxConnections_ = System.getProperty(
    "spark.shuffle.maxTxConnections", "8").toInt

  // Upper limit on receiving in blocked implementations (whichever comes first)
  private var MaxChatTime_ = System.getProperty(
    "spark.shuffle.maxChatTime", "250").toInt
  private var MaxChatBlocks_ = System.getProperty(
    "spark.shuffle.maxChatBlocks", "1024").toInt
    
  // A reducer is throttled if it is this much faster 
  private var ThrottleFraction_ = System.getProperty(
    "spark.shuffle.throttleFraction", "2.0").toDouble
  
  def MasterHostAddress = MasterHostAddress_
  def MasterTrackerPort = MasterTrackerPort_

  def BlockSize = BlockSize_

  def MinKnockInterval = MinKnockInterval_
  def MaxKnockInterval = MaxKnockInterval_
  
  def MaxRxConnections = MaxRxConnections_
  def MaxTxConnections = MaxTxConnections_

  def MaxChatTime = MaxChatTime_
  def MaxChatBlocks = MaxChatBlocks_
  
  def ThrottleFraction = ThrottleFraction_
  
  // Returns a standard ThreadFactory except all threads are daemons
  private def newDaemonThreadFactory: ThreadFactory = {
    new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        var t = Executors.defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        return t
      }
    }
  }

  // Wrapper over newFixedThreadPool
  def newDaemonFixedThreadPool(nThreads: Int): ThreadPoolExecutor = {
    var threadPool =
      Executors.newFixedThreadPool(nThreads).asInstanceOf[ThreadPoolExecutor]

    threadPool.setThreadFactory(newDaemonThreadFactory)
    
    return threadPool
  }
  
  // Wrapper over newCachedThreadPool
  def newDaemonCachedThreadPool: ThreadPoolExecutor = {
    var threadPool =
      Executors.newCachedThreadPool.asInstanceOf[ThreadPoolExecutor]
  
    threadPool.setThreadFactory(newDaemonThreadFactory)
    
    return threadPool
  }
}

@serializable
case class SplitInfo(val hostAddress: String, val listenPort: Int,
  val splitId: Int) { 

  var hasSplits = 0
  var hasSplitsBitVector: BitSet = null
  
  // Used by mappers of dim |numOutputSplits|
  var totalBlocksPerOutputSplit: Array[Int] = null
  // Used by reducers of dim |numInputSplits|
  var hasBlocksPerInputSplit: Array[Int] = null
}

object SplitInfo {
  // Constants for special values of listenPort
  val MappersBusy = -1
  
  // Used by SuperTracker-related implementation
  val TrackerDoesNotExist = -1
  val ShuffleAlreadyFinished = -2

  // Other constants
  val UnusedParam = 0
}
