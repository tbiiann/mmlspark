// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.io._
import java.net._

import com.microsoft.ml.lightgbm.{SWIGTYPE_p_float, SWIGTYPE_p_void, lightgbmlib, lightgbmlibConstants}
import com.microsoft.ml.spark.StreamUtilities.using
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.attribute._
import org.apache.spark.ml.linalg.{DenseVector, SparseVector}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.Row
import org.slf4j.Logger

case class NetworkParams(defaultListenPort: Int, addr: String, port: Int)

private object TrainUtils extends Serializable {

  def generateDataset(rows: Array[Row], labelColumn: String, featuresColumn: String,
                      weightColumn: Option[String],
                      referenceDataset: Option[LightGBMDataset]): Option[LightGBMDataset] = {
    val numRows = rows.length
    val labels = rows.map(row => row.getDouble(row.fieldIndex(labelColumn)))
    val hrow = rows.head
    var datasetPtr: Option[LightGBMDataset] = None
    datasetPtr =
      if (hrow.get(hrow.fieldIndex(featuresColumn)).isInstanceOf[DenseVector]) {
        val rowsAsDoubleArray = rows.map(row => row.get(row.fieldIndex(featuresColumn)) match {
          case dense: DenseVector => dense.toArray
          case sparse: SparseVector => sparse.toDense.toArray
        })
        val slotNames = getSlotNames(rows(0).schema, featuresColumn, rowsAsDoubleArray.head.length)
        Some(LightGBMUtils.generateDenseDataset(numRows, rowsAsDoubleArray, referenceDataset, slotNames))
      } else {
        val rowsAsSparse = rows.map(row => row.get(row.fieldIndex(featuresColumn)) match {
          case dense: DenseVector => dense.toSparse
          case sparse: SparseVector => sparse
        })
        val slotNames = getSlotNames(rows(0).schema, featuresColumn, rowsAsSparse(0).size)
        Some(LightGBMUtils.generateSparseDataset(rowsAsSparse, referenceDataset, slotNames))
      }

    // Validate generated dataset has the correct number of rows and cols
    datasetPtr.get.validateDataset()
    datasetPtr.get.addField(labels, "label", numRows)
    weightColumn.foreach { col =>
      val weights = rows.map(row => row.getDouble(row.fieldIndex(col)))
      datasetPtr.get.addField(weights, "weight", numRows)
    }
    datasetPtr
  }

  def createBooster(trainParams: TrainParams, trainDatasetPtr: Option[LightGBMDataset],
                    validDatasetPtr: Option[LightGBMDataset]): Option[SWIGTYPE_p_void] = {
    // Create the booster
    val boosterOutPtr = lightgbmlib.voidpp_handle()
    val parameters = trainParams.toString()
    LightGBMUtils.validate(lightgbmlib.LGBM_BoosterCreate(trainDatasetPtr.map(_.dataset).get,
                                                          parameters, boosterOutPtr), "Booster")
    val boosterPtr = Some(lightgbmlib.voidpp_value(boosterOutPtr))
    trainParams.modelString.foreach { modelStr =>
      val booster = LightGBMUtils.getBoosterPtrFromModelString(modelStr)
      LightGBMUtils.validate(lightgbmlib.LGBM_BoosterMerge(boosterPtr.get, booster), "Booster Merge")
    }
    validDatasetPtr.foreach { lgbmdataset =>
      LightGBMUtils.validate(lightgbmlib.LGBM_BoosterAddValidData(boosterPtr.get,
        lgbmdataset.dataset), "Add Validation Dataset")
    }
    boosterPtr
  }

  def saveBoosterToString(boosterPtr: Option[SWIGTYPE_p_void], log: Logger): String = {
    val bufferLength = LightGBMConstants.defaultBufferLength
    val bufferLengthPtr = lightgbmlib.new_longp()
    lightgbmlib.longp_assign(bufferLengthPtr, bufferLength)
    val bufferLengthPtrInt64 = lightgbmlib.long_to_int64_t_ptr(bufferLengthPtr)
    val bufferOutLengthPtr = lightgbmlib.new_int64_tp()
    lightgbmlib.LGBM_BoosterSaveModelToStringSWIG(boosterPtr.get, 0, -1, bufferLengthPtrInt64, bufferOutLengthPtr)
  }

  def getEvalNames(boosterPtr: Option[SWIGTYPE_p_void]): Array[String]  = {
    // Need to keep track of best scores for each metric, see callback.py in lightgbm for reference
    val evalCountsPtr = lightgbmlib.new_intp()
    val resultCounts = lightgbmlib.LGBM_BoosterGetEvalCounts(boosterPtr.get, evalCountsPtr)
    LightGBMUtils.validate(resultCounts, "Booster Get Eval Counts")
    val evalCounts = lightgbmlib.intp_value(evalCountsPtr)
    // For debugging, can get metric names:
    val evalNamesPtr = lightgbmlib.LGBM_BoosterGetEvalNamesSWIG(boosterPtr.get, evalCounts)
    (0 until evalCounts).map(lightgbmlib.stringArray_getitem(evalNamesPtr, _)).toArray
  }

  def trainCore(trainParams: TrainParams, boosterPtr: Option[SWIGTYPE_p_void],
                log: Logger, hasValid: Boolean): Unit = {
    val isFinishedPtr = lightgbmlib.new_intp()
    var isFinished = false
    var iters = 0
    val evalNames = getEvalNames(boosterPtr)
    val evalCounts = evalNames.length
    val bestScore = new Array[Double](evalCounts)
    val bestScores = new Array[Array[Double]](evalCounts)
    val bestIter = new Array[Int](evalCounts)
    while (!isFinished && iters < trainParams.numIterations) {
      try {
        log.info("LightGBM worker calling LGBM_BoosterUpdateOneIter")
        val result = lightgbmlib.LGBM_BoosterUpdateOneIter(boosterPtr.get, isFinishedPtr)
        LightGBMUtils.validate(result, "Booster Update One Iter")
        isFinished = lightgbmlib.intp_value(isFinishedPtr) == 1
        log.info("LightGBM running iteration: " + iters + " with result: " +
          result + " and is finished: " + isFinished)
      } catch {
        case _: java.lang.Exception =>
          isFinished = true
          log.warn("LightGBM reached early termination on one worker," +
            " stopping training on worker. This message should rarely occur")
      }
      if (hasValid && !isFinished) {
        val evalResults = lightgbmlib.new_doubleArray(evalNames.length)
        val dummyEvalCountsPtr = lightgbmlib.new_intp()
        val resultEval = lightgbmlib.LGBM_BoosterGetEval(boosterPtr.get, 1, dummyEvalCountsPtr, evalResults)
        lightgbmlib.delete_intp(dummyEvalCountsPtr)
        LightGBMUtils.validate(resultEval, "Booster Get Eval")
        evalNames.zipWithIndex.foreach { case (evalName, index) =>
          val score = lightgbmlib.doubleArray_getitem(evalResults, index)
          val cmp =
            if (evalName.startsWith("auc") || evalName.startsWith("ndcg@") || evalName.startsWith("map@"))
              (x: Double, y: Double) => x > y
            else
              (x: Double, y: Double) => x < y
          if (bestScores(index) == null || cmp(score, bestScore(index))) {
            bestScore(index) = score
            bestIter(index) = iters
            bestScores(index) = evalNames.indices
              .map(j => lightgbmlib.doubleArray_getitem(evalResults, j)).toArray
          } else if (iters - bestIter(index) >= trainParams.earlyStoppingRound) {
            isFinished = true
            log.info("Early stopping, best iteration is " + bestIter(index))
          }
        }
        lightgbmlib.delete_doubleArray(evalResults)
      }
      iters = iters + 1
    }
  }

  def getSlotNames(schema: StructType, featuresColumn: String, numCols: Int): Option[Array[String]] = {
    val featuresSchema = schema.fields(schema.fieldIndex(featuresColumn))
    val metadata = AttributeGroup.fromStructField(featuresSchema)
    if (metadata.attributes.isEmpty) None
    else if (metadata.attributes.get.isEmpty) None
    else {
      val colnames = (0 until numCols).map(_.toString).toArray
      metadata.attributes.get.foreach {
        case attr =>
          attr.index.foreach(index => colnames(index) = attr.name.getOrElse(index.toString))
      }
      Some(colnames)
    }
  }

  def translate(labelColumn: String, featuresColumn: String, weightColumn: Option[String],
                validationData: Option[Broadcast[Array[Row]]], log: Logger,
                trainParams: TrainParams, inputRows: Iterator[Row]): Iterator[LightGBMBooster] = {
    val rows = inputRows.toArray
    var trainDatasetPtr: Option[LightGBMDataset] = None
    var validDatasetPtr: Option[LightGBMDataset] = None
    try {
      trainDatasetPtr = generateDataset(rows, labelColumn, featuresColumn, weightColumn, None)
      if (validationData.isDefined) {
        validDatasetPtr = generateDataset(validationData.get.value, labelColumn,
          featuresColumn, weightColumn, trainDatasetPtr)
      }
      var boosterPtr: Option[SWIGTYPE_p_void] = None
      try {
        boosterPtr = createBooster(trainParams, trainDatasetPtr, validDatasetPtr)
        trainCore(trainParams, boosterPtr, log, validDatasetPtr.isDefined)
        val model = saveBoosterToString(boosterPtr, log)
        List[LightGBMBooster](new LightGBMBooster(model)).toIterator
      } finally {
        // Free booster
        boosterPtr.foreach { booster =>
          LightGBMUtils.validate(lightgbmlib.LGBM_BoosterFree(booster), "Finalize Booster")
        }
      }
    } finally {
      // Free datasets
      trainDatasetPtr.foreach(_.close())
      validDatasetPtr.foreach(_.close())
    }
  }

  private def findOpenPort(defaultListenPort: Int, numCoresPerExec: Int, log: Logger): Socket = {
    val basePort = defaultListenPort + (LightGBMUtils.getId() * numCoresPerExec)
    var localListenPort = basePort
    var foundPort = false
    var workerServerSocket: Socket = null
    while (!foundPort) {
      try {
        workerServerSocket = new Socket()
        workerServerSocket.bind(new InetSocketAddress(localListenPort))
        foundPort = true
      } catch {
        case ex: IOException =>
          log.warn(s"Could not bind to port $localListenPort...")
          localListenPort += 1
          if (localListenPort - basePort > 1000) {
            throw new Exception("Error: Could not find open port after 1k tries")
          }
      }
    }
    log.info(s"Successfully bound to port $localListenPort")
    workerServerSocket
  }

  def getNetworkInitNodes(networkParams: NetworkParams,
                          localListenPort: Int, log: Logger,
                          emptyPartition: Boolean): String = {
    using(new Socket(networkParams.addr, networkParams.port)) {
      driverSocket =>
        using(Seq(new BufferedReader(new InputStreamReader(driverSocket.getInputStream)),
          new BufferedWriter(new OutputStreamWriter(driverSocket.getOutputStream)))) {
          io =>
            val driverInput = io(0).asInstanceOf[BufferedReader]
            val driverOutput = io(1).asInstanceOf[BufferedWriter]
            val workerStatus =
              if (emptyPartition) {
                log.info("send empty status to driver")
                LightGBMConstants.ignoreStatus
              } else {
                val workerHost = driverSocket.getLocalAddress.getHostAddress
                val workerInfo = s"$workerHost:$localListenPort"
                log.info(s"send current worker info to driver: $workerInfo ")
                workerInfo
              }
            // Send the current host:port to the driver
            driverOutput.write(s"$workerStatus\n")
            driverOutput.flush()
            if (workerStatus != LightGBMConstants.ignoreStatus) {
              // Wait to get the list of nodes from the driver
              val nodes = driverInput.readLine()
              log.info(s"LightGBM worker got nodes for network init: $nodes")
              nodes
            } else {
              workerStatus
            }
        }.get
    }.get
  }

  def networkInit(nodes: String, localListenPort: Int, log: Logger, retry: Int, delay: Long): Unit = {
    try {
      LightGBMUtils.validate(lightgbmlib.LGBM_NetworkInit(nodes, localListenPort,
        LightGBMConstants.defaultListenTimeout, nodes.split(",").length), "Network init")
    } catch {
      case ex: Throwable => {
        log.info(s"NetworkInit failed with exception on local port $localListenPort, retrying: $ex")
        Thread.sleep(delay)
        if (retry > 0) {
          networkInit(nodes, localListenPort, log, retry - 1, delay * 2)
        } else {
          throw ex
        }
      }
    }
  }

  def trainLightGBM(networkParams: NetworkParams, labelColumn: String, featuresColumn: String,
                    weightColumn: Option[String], validationData: Option[Broadcast[Array[Row]]], log: Logger,
                    trainParams: TrainParams, numCoresPerExec: Int)
                   (inputRows: Iterator[Row]): Iterator[LightGBMBooster] = {
    val emptyPartition = !inputRows.hasNext
    // Ideally we would start the socket connections in the C layer, this opens us up for
    // race conditions in case other applications open sockets on cluster, but usually this
    // should not be a problem
    val (nodes, localListenPort) = using(findOpenPort(networkParams.defaultListenPort, numCoresPerExec, log)) {
      openPort =>
        val localListenPort = openPort.getLocalPort
        // Initialize the native library
        LightGBMUtils.initializeNativeLibrary()
        log.info(s"LightGBM worker connecting to host: ${networkParams.addr} and port: ${networkParams.port}")
        (getNetworkInitNodes(networkParams, localListenPort, log, emptyPartition), localListenPort)
    }.get

    if (emptyPartition) {
      log.warn("LightGBM worker encountered empty partition, for best performance ensure no partitions empty")
      List[LightGBMBooster]().toIterator
    } else {
      // Initialize the network communication
      log.info(s"LightGBM worker listening on: $localListenPort")
      try {
        val retries = 3
        val initialDelay = 1000L
        networkInit(nodes, localListenPort, log, retries, initialDelay)
        translate(labelColumn, featuresColumn, weightColumn, validationData, log, trainParams, inputRows)
      } finally {
        // Finalize network when done
        LightGBMUtils.validate(lightgbmlib.LGBM_NetworkFree(), "Finalize network")
      }
    }
  }

}
