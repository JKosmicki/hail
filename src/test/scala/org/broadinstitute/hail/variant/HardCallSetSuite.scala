package org.broadinstitute.hail.variant

import breeze.linalg.DenseVector
import org.broadinstitute.hail.SparkSuite
import org.testng.annotations.Test

import scala.util.Random

class HardCallSetSuite extends SparkSuite {
  @Test def test() {

    def randGtArray(n: Int, pNonRef: Double): Array[Int] = {
      val a = Array.ofDim[Int](n)
      for (i <- 0 until n)
        a(i) =
          if (Random.nextDouble < pNonRef)
            Random.nextInt(3) + 1
          else 0
      a
    }

    val gtsList = List(
      Array(0),
      Array(0,1),
      Array(0,1,2),
      Array(0,1,2,3),
      Array(0,1,2,3,0),
      Array(0,1,2,3,1),
      Array(0,1,2,3,1,2),
      Array(0,1,2,3,0,0,0,0,0,0,0,0,0,0,1,2,3),
      randGtArray(100, .1),
      randGtArray(101, .1),
      randGtArray(102, .1),
      randGtArray(103, .1),
      randGtArray(1000, .01),
      randGtArray(1001, .01),
      randGtArray(1002, .01),
      randGtArray(1003, .01),
      randGtArray(10000, .001),
      randGtArray(10001, .001),
      randGtArray(10002, .001),
      randGtArray(10003, .001),
      randGtArray(100000, .0001),
      randGtArray(100001, .0001),
      randGtArray(100002, .0001),
      randGtArray(100003, .0001),
      randGtArray(1000001, .00001),
      randGtArray(1000002, .00001),
      randGtArray(1000003, .00001),
      randGtArray(1000004, .00001)
    )

    import CallStream._

    for (gts <- gtsList) {
      val n = gts.size

      val dcs = denseCallStreamFromGtStream(gts, n)
      val scs = sparseCallStreamFromGtStream(gts, n)

      val y = DenseVector.fill[Double](n)(Random.nextInt(2))

      val ds = dcs.hardStats(y,n)
      val ss = scs.hardStats(y,n)

      assert(ds == ss)

      println(scs)
      println(ss)
      println()

    }
  }
}