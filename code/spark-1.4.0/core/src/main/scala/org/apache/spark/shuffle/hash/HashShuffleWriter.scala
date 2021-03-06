/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.hash

import org.apache.spark._
import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle._
import org.apache.spark.storage.BlockObjectWriter

private[spark] class HashShuffleWriter[K, V](
                                              shuffleBlockResolver: FileShuffleBlockResolver,
                                              handle: BaseShuffleHandle[K, V, _],
                                              mapId: Int,
                                              context: TaskContext)
  extends ShuffleWriter[K, V] with Logging {

  private val dep = handle.dependency
  private val numOutputSplits = dep.partitioner.numPartitions
  private val metrics = context.taskMetrics

  // Are we in the process of stopping? Because map tasks can call stop() with success = true
  // and then call stop() with success = false if they get an exception, we want to make sure
  // we don't try deleting files, etc twice.
  private var stopping = false

  private val writeMetrics = new ShuffleWriteMetrics()
  metrics.shuffleWriteMetrics = Some(writeMetrics)

  private val blockManager = SparkEnv.get.blockManager
  private val ser = Serializer.getSerializer(dep.serializer.getOrElse(null))
  private val shuffle = shuffleBlockResolver.forMapTask(dep.shuffleId, mapId, numOutputSplits, ser,
    writeMetrics)

  /** Write a bunch of records to this task's output */
  override def write(records: Iterator[Product2[K, V]]): Unit = {
    //trace
    InternalProfiler.getTaskProfileForThreadId(Thread.currentThread().getId).isShuffleWrite=true
    val iter = if (dep.aggregator.isDefined) {
      if (dep.mapSideCombine) {
        //trace
        InternalProfiler.getTaskProfileForThreadId(Thread.currentThread().getId).shuffleWrite.mapSideCombine=true
        InternalProfiler.getTaskProfileForThreadId(Thread.currentThread().getId).hashToReadOrWriteAggerator.put(dep.aggregator.get.hashCode(),1)
        dep.aggregator.get.combineValuesByKey(records, context)
      } else {
        records
      }
    } else {
      require(!dep.mapSideCombine, "Map-side combine without Aggregator specified!")
      records
    }

    //trace
    var cPartitionPhaseTime:Long=0L
    var cMergeWriteTime: Long = 0L
    var computeTime: Long = 0L
    var dComputeOutRecs :Long=0L
    val startTime=System.nanoTime()
    for (elem <- iter) {
      dComputeOutRecs+=1
      val startTime=System.nanoTime()
      val bucketId = dep.partitioner.getPartition(elem._1)
      val startTime1=System.nanoTime()
      shuffle.writers(bucketId).write(elem._1, elem._2)
      val startTime2=System.nanoTime()
      cPartitionPhaseTime+=startTime1-startTime
      cMergeWriteTime+=startTime2-startTime1
    }
    computeTime=System.nanoTime()-startTime-cPartitionPhaseTime-cMergeWriteTime
    if(!dep.mapSideCombine){
      InternalProfiler.getTaskProfileForThreadId(Thread.currentThread().getId).computeTime+=computeTime
      InternalProfiler.getTaskProfileForThreadId(Thread.currentThread().getId).dComputeOutRecs=dComputeOutRecs
    }
    InternalProfiler.getTaskProfileForThreadId(Thread.currentThread().getId).shuffleWrite.cPartitionPhaseTime=cPartitionPhaseTime
    InternalProfiler.getTaskProfileForThreadId(Thread.currentThread().getId).shuffleWrite.cMergeWriteTime=cMergeWriteTime

  }

  /** Close this writer, passing along whether the map completed */
  override def stop(initiallySuccess: Boolean): Option[MapStatus] = {
    var success = initiallySuccess
    try {
      if (stopping) {
        return None
      }
      stopping = true
      if (success) {
        try {
          Some(commitWritesAndBuildStatus())
        } catch {
          case e: Exception =>
            success = false
            revertWrites()
            throw e
        }
      } else {
        revertWrites()
        None
      }
    } finally {
      // Release the writers back to the shuffle block manager.
      if (shuffle != null && shuffle.writers != null) {
        try {
          shuffle.releaseWriters(success)
        } catch {
          case e: Exception => logError("Failed to release shuffle writers", e)
        }
      }
    }
  }

  private def commitWritesAndBuildStatus(): MapStatus = {
    // Commit the writes. Get the size of each bucket block (total block size).
    val sizes: Array[Long] = shuffle.writers.map { writer: BlockObjectWriter =>
      writer.commitAndClose()
      writer.fileSegment().length
    }
    MapStatus(blockManager.shuffleServerId, sizes)
  }

  private def revertWrites(): Unit = {
    if (shuffle != null && shuffle.writers != null) {
      for (writer <- shuffle.writers) {
        writer.revertPartialWritesAndClose()
      }
    }
  }
}
