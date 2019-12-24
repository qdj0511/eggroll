/*
 * Copyright (c) 2019 - now, Eggroll Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.webank.eggroll.rollframe

import java.lang.management.ManagementFactory

import com.webank.eggroll.core.io.adapter.{BlockDeviceAdapter, HdfsBlockAdapter}
import com.webank.eggroll.format._
import org.junit.{Before, Test}

class FrameFormatTests {
  private val testAssets = TestAssets

  @Before
  def setup(): Unit = {
    HdfsBlockAdapter.fastSetLocal()
  }

  @Test
  def testNullableFields(): Unit = {
    val fb = new FrameBatch(new FrameSchema(testAssets.getDoubleSchema(4)), 3000)
    val path = "/tmp/unittests/RollFrameTests/file/test1/nullable_test"
    val adapter = FrameDB.file(path)
    adapter.writeAll(Iterator(fb.sliceByColumn(0, 3)))
    adapter.close()
    val adapter2 = FrameDB.file(path)
    val fb2 = adapter2.readOne()
    assert(fb2.rowCount == 3000)
  }

  @Test
  def TestFileFrameDB(): Unit = {
    // create FrameBatch data
    val fb = new FrameBatch(new FrameSchema(testAssets.getDoubleSchema(2)), 100)
    for (i <- 0 until fb.fieldCount) {
      for (j <- 0 until fb.rowCount) {
        fb.writeDouble(i, j, j)
      }
    }
    // write FrameBatch data to File
    val filePath = "/tmp/unittests/RollFrameTests/file/test1/framedb_test"
    val fileWriteAdapter = FrameDB.file(filePath)
    fileWriteAdapter.writeAll(Iterator(fb))
    fileWriteAdapter.close()

    // read FrameBatch data from File
    val fileReadAdapter = FrameDB.file(filePath)
    val fbFromFile = fileReadAdapter.readOne()

    assert(fbFromFile.readDouble(0, 3) == 3.0)
  }

  @Test
  def testJvmFrameDB(): Unit = {
    // create FrameBatch data
    val fb = new FrameBatch(new FrameSchema(testAssets.getDoubleSchema(2)), 100)
    for (i <- 0 until fb.fieldCount) {
      for (j <- 0 until fb.rowCount) {
        fb.writeDouble(i, j, j)
      }
    }
    // write FrameBatch data to Jvm
    val jvmPath = "/tmp/unittests/RollFrameTests/jvm/test1/framedb_test"
    val jvmAdapter = FrameDB.cache(jvmPath)
    jvmAdapter.writeAll(Iterator(fb))
    // read FrameBatch data from Jvm
    val fbFromJvm = jvmAdapter.readOne()

    assert(fbFromJvm.readDouble(0, 10) == 10.0)
  }

  @Test
  def testHdfsFrameDB(): Unit = {
    // create FrameBatch data
    val fb = new FrameBatch(new FrameSchema(testAssets.getDoubleSchema(2)), 100)
    for (i <- 0 until fb.fieldCount) {
      for (j <- 0 until fb.rowCount) {
        fb.writeDouble(i, j, j)
      }
    }
    // write FrameBatch data to HDFS
    val hdfsPath = "/tmp/unittests/RollFrameTests/hdfs/test1/framedb_test/0"
    val hdfsWriteAdapter = FrameDB.hdfs(hdfsPath)
    hdfsWriteAdapter.writeAll(Iterator(fb))
    hdfsWriteAdapter.close() // must be closed

    // read FrameBatch data from HDFS
    val hdfsReadAdapter = FrameDB.hdfs(hdfsPath)
    val fbFromHdfs = hdfsReadAdapter.readOne()

    assert(fbFromHdfs.readDouble(0, 20) == 20.0)
  }

  @Test
  def testNetworkFrameDB(): Unit = {
    // start transfer server
    val service = new NioTransferEndpoint
    val port = 8818
    val host = "127.0.0.1"
    new Thread() {
      override def run(): Unit = {
        try {
          service.runServer(host, port)
        } catch {
          case e: Throwable => e.printStackTrace()
        }
      }
    }.start()

    // create FrameBatch data
    val fb = new FrameBatch(new FrameSchema(testAssets.getDoubleSchema(2)), 100)
    for (i <- 0 until fb.fieldCount) {
      for (j <- 0 until fb.rowCount) {
        fb.writeDouble(i, j, j)
      }
    }
    // write FrameBatch data to Network
    val networkPath = "/tmp/unittests/RollFrameTests/network/test1/framedb_test/0"
    val networkWriteAdapter = FrameDB.network(networkPath, host, port.toString)
    networkWriteAdapter.append(fb)
    networkWriteAdapter.append(fb)
    //    networkWriteAdapter.writeAll(Iterator(fb))
    Thread.sleep(1000) // wait for QueueFrameDB insert the frame

    // read FrameBatch data from network
    val networkReadAdapter = FrameDB.network(networkPath, host, port.toString)
    networkReadAdapter.readAll().foreach(fb =>
      assert(fb.readDouble(0, 30) == 30.0)
    )
  }

  @Test
  def testFrameDataType(): Unit = {
    val schema =
      """
      {
        "fields": [
          {"name":"double1", "type": {"name" : "floatingpoint","precision" : "DOUBLE"}},
          {"name":"long1", "type": {"name" : "int","bitWidth" : 64,"isSigned":true}},
          {"name":"longarray1", "type": {"name" : "fixedsizelist","listSize" : 10},
            "children":[{"name":"$data$", "type": {"name" : "int","bitWidth" : 64,"isSigned":true}}]
          },
          {"name":"longlist1", "type": {"name" : "list"},
             "children":[{"name":"$data$", "type": {"name" : "int","bitWidth" : 64,"isSigned":true}}]
           }
        ]
      }
      """.stripMargin

    val batch = new FrameBatch(new FrameSchema(schema), 2)
    batch.writeDouble(0, 0, 1.2)
    batch.writeLong(1, 0, 22)
    val arr = batch.getArray(2, 0)
    val list0 = batch.getList(3, 0, 3)
    val list2 = batch.getList(3, 1, 4)
    batch.getList(3, 2, 5)
    val list0Copy = batch.getList(3, 0)
    //    list.valueCount(3)
    list0.writeLong(2, 33)
    list0.writeLong(0, 44)
    list2.writeLong(3, 55)
    (0 until 10).foreach(i => arr.writeLong(i, i * 100 + 1))
    val outputStore = FrameDB.file("/tmp/unittests/RollFrameTests/file/test1/type_test")

    outputStore.append(batch)
    outputStore.close()
    assert(batch.readDouble(0, 0) == 1.2)
    assert(batch.readLong(1, 0) == 22)
    assert(arr.readLong(0) == 1)
    assert(list0.readLong(2) == 33)
    assert(list0.readLong(0) == 44)
    assert(list0Copy.readLong(0) == 44)

    val inputStore = FrameDB.file("/tmp/unittests/RollFrameTests/file/test1/type_test")
    for (b <- inputStore.readAll()) {
      assert(b.readDouble(0, 0) == 1.2)
      assert(b.readLong(1, 0) == 22)
      assert(b.getArray(2, 0).readLong(0) == 1)
      assert(b.getList(3, 0).readLong(0) == 44)
      assert(b.getList(3, 0).readLong(2) == 33)
      assert(b.getList(3, 1).readLong(3) == 55)
    }
  }

  @Test
  def testReadWrite(): Unit = {
    val schema =
      """
        {
          "fields": [
          {"name":"double1", "type": {"name" : "floatingpoint","precision" : "DOUBLE"}},
          {"name":"double2", "type": {"name" : "floatingpoint","precision" : "DOUBLE"}},
          {"name":"double3", "type": {"name" : "floatingpoint","precision" : "DOUBLE"}}
          ]
        }
      """.stripMargin
    val path = "/tmp/unittests/RollFrameTests/file/testColumnarWrite/0"
    val cw = new FrameWriter(new FrameSchema(schema), BlockDeviceAdapter.file(path))
    val valueCount = 10
    val fieldCount = 3
    val batchSize = 5
    cw.write(valueCount, batchSize,
      (fid, cv) => (0 until valueCount).foreach(
        n => cv.writeDouble(n, fid * valueCount + n * 0.5)
      )
    )
    cw.close()
    val cr = new FrameReader(path)
    for (cb <- cr.getColumnarBatches()) {
      for (fid <- 0 until fieldCount) {
        val cv = cb.rootVectors(fid)
        for (n <- 0 until cv.valueCount) {
          assert(cv.readDouble(n) == fid * valueCount + n * 0.5)
        }
      }
    }
    cr.close()
  }

  /**
    * better set -Xmx bigger
    */
  @Test
  def testFrameFork(): Unit = {
    val fieldCount = 100000
    val rowCount =  1// total value count = rowCount * fbCount * fieldCount
    val schema = testAssets.getDoubleSchema(fieldCount)
    var start = System.currentTimeMillis()
    val rootSchema = new FrameSchema(schema)

    println(s"create shema time = ${System.currentTimeMillis() - start} ms")
    start = System.currentTimeMillis()
    val fb = new FrameBatch(rootSchema, rowCount)

    println(s"new FrameBatch time = ${System.currentTimeMillis() - start} ms")
    start = System.currentTimeMillis()
    for {x <- 0 until fieldCount
         y <- 0 until rowCount} {
      fb.writeDouble(x, y, 1)
    }
    println(s"set value time = ${System.currentTimeMillis() - start} ms\n")
    start = System.currentTimeMillis()
    val fb1 = FrameUtils.copy(fb)
    println(s"copy fb1 time= ${System.currentTimeMillis() - start} ms")
    start = System.currentTimeMillis()
    val fb2 = FrameUtils.copy(fb)
    println(s"copy fb2 time = ${System.currentTimeMillis() - start} ms\n")
    start = System.currentTimeMillis()
    val fb3 = FrameUtils.fork(fb)
    println(s"fork fb3 time = ${System.currentTimeMillis() - start} ms")
    start = System.currentTimeMillis()
    val fb4 = FrameUtils.fork(fb)
    println(s"fork fb4 time = ${System.currentTimeMillis() - start} ms")


    val ads = fb.rootSchema.arrowSchema.getVector(0).getDataBufferAddress
    val ads1 = fb1.rootSchema.arrowSchema.getVector(0).getDataBufferAddress
    val ads2 = fb2.rootSchema.arrowSchema.getVector(0).getDataBufferAddress
    val ads3 = fb3.rootSchema.arrowSchema.getVector(0).getDataBufferAddress
    val ads4 = fb4.rootSchema.arrowSchema.getVector(0).getDataBufferAddress

    assert((ads != ads1) && (ads != ads2) && (ads != ads3))
    assert(fb.readDouble(1,0)==1)
    assert(fb1.readDouble(2,0)==1)
    assert(fb2.readDouble(3,0)==1)
    assert(fb3.readDouble(4,0)==1)
    assert(fb4.readDouble(5,0)==1)
  }
}
