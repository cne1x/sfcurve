package org.locationtech.sfcurve.benchmarks

import java.io.{FileOutputStream, FileWriter, PrintStream}

import org.locationtech.sfcurve.Utilities.CartesianProductIterable

import scala.collection.mutable.{Map => MutableMap}
import org.locationtech.sfcurve.hilbert.HilbertCurve2D
import org.locationtech.sfcurve.zorder.TriN.{Degrees, Point}
import org.locationtech.sfcurve.zorder.{TriN, Triangle, ZCurve2D}
import org.locationtech.sfcurve.{Dimensions, IndexRange, RangeComputeHints, SpaceFillingCurve2D}

object Locality extends App {
  type Curve = {
    def name: String
    def cardinality: Long;
    def toIndex(x: Double, y: Double): Long;
    def toPoint(index: Long): (Double, Double);
    def toRanges(xmin: Double, ymin: Double, xmax: Double, ymax: Double, hints: Option[RangeComputeHints]): Seq[IndexRange]
  }

  def toBinaryString(index: Long, bits: Int): String =
    index.toBinaryString.reverse.padTo(bits, "0").reverse.mkString("")

  def dBitstring(idxA: String, idxB: String, verbose: Boolean = false): Double = {
    val result: Double = idxA.zip(idxB).zipWithIndex.map {
      case ((bitA, bitB), pos) if bitA != bitB =>
        //println(s"  bits mismatch:  $bitA, $bitB")
        Math.pow(0.5, pos + 1)
      case ((bitA, bitB), pos) if bitA == bitB =>
        //println(s"  bits MATCH:  $bitA, $bitB")
        0.0
    }.sum

    if (verbose) {
      println(s"dBitString($idxA, $idxB) = $result")
    }

    result
  }

  case class Points(a: Point, b: Point, verbose: Boolean = false) {
    val maxDPlane = Math.sqrt(360.0*360.0 + 180.0*180.0)
    def dPlane(verbose: Boolean = false): Double = {
      val dx = a.x.degrees - b.x.degrees
      val dy = a.y.degrees - b.y.degrees
      val result = Math.sqrt(dx * dx + dy * dy) / maxDPlane
      if (verbose) {
        println(s"dPlane:  ${a.wkt} to ${b.wkt} = $result")
      }
      result
    }

    def dSphere(verbose: Boolean = false): Double = {
      val lambda1 = a.x.radians
      val phi1 = a.y.radians
      val lambda2 = b.x.radians
      val phi2 = b.y.radians

      val dLambda = Math.abs(lambda1 - lambda2)
      val dPhi = Math.abs(phi1 - phi2)

      val numerator = Math.sqrt(Math.pow(Math.cos(phi2) * Math.sin(dLambda), 2.0) + Math.pow(Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda), 2.0))
      val denominator = Math.sin(phi1) * Math.sin(phi2) + Math.cos(phi1) * Math.cos(phi2) * Math.cos(dLambda)
      val rads = Math.atan2(numerator, denominator)
      val prads = 0.5 * rads / Math.PI

      //println(s"l1 $lambda1, p1 $phi1, l2 $lambda2, p2 $phi2, numerator $numerator, denominator $denominator, rads $rads, prads $prads")
      val result = prads
      if (verbose) {
        println(s"dSphere:  ${a.wkt} to ${b.wkt} = $result")
      }
      assert(!result.isNaN, s"dSphere value is NaN for ${a.wkt} to ${b.wkt}")
      result
    }

    implicit def deg2dec(d: Degrees): Double = d.degrees

    implicit def dec2deg(d: Double): Degrees = Degrees(d)

    def dIndex(curve: SpaceFillingCurve2D, verbose: Boolean = false): Double = {
      val bitsPrecision: Int = Dimensions.bitsFromCardinality(curve.cardinality).toInt
      val idxA: String = toBinaryString(curve.toIndex(a.x, a.y), bitsPrecision)
      val idxB: String = toBinaryString(curve.toIndex(b.x, b.y), bitsPrecision)
      dBitstring(idxA, idxB, verbose = verbose)
    }

    def dTriangle(depth: Int, verbose: Boolean = false): Double = {
      val t0 = TriN.getTriangle(a.x.degrees, a.y.degrees, depth)
      val t1 = TriN.getTriangle(b.x.degrees, b.y.degrees, depth)
      dBitstring(t0.bitString, t1.bitString, verbose = verbose)
    }
  }

  // utility class for testing, reporting locality
  case class Distances(name: String, curve: {def toIndex(x: Double, y: Double): Long; def toPoint(i: Long): (Double, Double)}) {
    val series: Seq[String] = Seq("plane", "sphere", "index")
    val data: Map[String, MutableMap[String, Int]] = series.map(s => s -> MutableMap.empty[String, Int]).toMap

    private def acc(label: String, distance: Double): Unit = {
      require(data.contains(label))
      val submap: MutableMap[String, Int] = data(label)
      val dist = f"${distance}%1.5f"
      submap.put(dist, submap.getOrElse(dist, 0) + 1)
    }

    def curvePointFromIndex(index: Long): Point = {
      val (x: Double, y: Double) = curve.toPoint(index)
      Point(Degrees(x), Degrees(y))
    }

    def cellPoints(points: Points): Points = {
      val ai2: Long = curve.toIndex(points.a.x.degrees, points.a.y.degrees)
      val a2: Point = curvePointFromIndex(ai2)
      val bi2: Long = curve.toIndex(points.b.x.degrees, points.b.y.degrees)
      val b2: Point = curvePointFromIndex(bi2)

      Points(a2, b2)
    }

    def accumulate(rawPoints: Points, verbose: Boolean = false): Unit = {
      val points: Points = cellPoints(rawPoints)
      acc("plane", points.dPlane(verbose = verbose))
      acc("sphere", points.dSphere(verbose = verbose))
      val indexDistance = curve match {
        case sfc: SpaceFillingCurve2D => points.dIndex(sfc, verbose = verbose)
        case tri: Triangle => points.dTriangle(tri.depth, verbose = verbose)
      }
      acc("index", indexDistance)
    }

    def csvLine(rawPoints: Points, verbose: Boolean = false): String = {
      val points: Points = cellPoints(rawPoints)
      val dplane = points.dPlane(verbose = verbose)
      val dsphere = points.dSphere(verbose = verbose)
      val dindex = curve match {
        case tri: Triangle => points.dTriangle(tri.depth, verbose = verbose)
        case sfc: SpaceFillingCurve2D => points.dIndex(sfc, verbose = verbose)
      }
      s"$dplane,$dsphere,$dindex"
    }

    def summarize(ps: PrintStream): Unit =
    {
      println(s"\n$name distances...")
      series.foreach(label => {
        println(s"  '$label':")
        val dists: MutableMap[String, Int] = data(label)
        val nTransitions = dists.values.sum
        println(s"    Number of cell-to-cell transitions:  $nTransitions")
        val totalDist = dists.map {
          case (k, v) => k.toDouble * v
        }.sum
        println(s"    Mean inter-cell distance:  ${totalDist / nTransitions.toDouble}")
      })
    }

  }

  def generateLongitude: Degrees = Degrees(0.5 + Math.random() * 359.0 - 180.0)

  def generateLatitude: Degrees = Degrees(0.5 + Math.random() * 179.0 - 90.0)

  def generatePoint: Point = Point(generateLongitude, generateLatitude)

  def generatePoints(sizeDegreesOpt: Option[Double] = None): Points = sizeDegreesOpt match {
    case Some(sizeDegrees) =>
      val x0: Double = 0.5 + Math.random() * (359.0 - sizeDegrees) - 180.0
      val y0: Double = 0.5 + Math.random() * (179.0 - sizeDegrees) - 90.0
      val x1: Double = x0 + sizeDegrees
      val y1: Double = y0 + sizeDegrees
      Points(Point(Degrees(x0), Degrees(y0)), Point(Degrees(x1), Degrees(y1)))
    case None =>
      Points(generatePoint, generatePoint)
  }

  trait Sampler extends Iterator[Points]

  case class CellIterator(name: String, curve: Curve) extends Sampler {
    val cardinality: Long = curve.cardinality

    var lastState: Any = _
    var state: Any = _
    var counter: Int = 0

    state = curve match {
      case sfc: SpaceFillingCurve2D => 0
      case tri: Triangle => tri
    }
    var lastPoint: Point = getPoint
    counter = 1
    lastState = state
    state = curve match {
      case sfc: SpaceFillingCurve2D => 1
      case tri: Triangle => tri.next.orNull
    }
    var point: Point = getPoint

    private def getPoint: Point = curve match {
      case sfc: SpaceFillingCurve2D =>
        val p = sfc.toPoint(counter)
        Point(Degrees(p._1), Degrees(p._2))
      case tri: Triangle =>
        require(state != null, s"'state' was <NULL> unexpectedly for counter $counter (< cardinality $cardinality)")
        val p = tri.toPoint(tri.index)
        Point(Degrees(p._1), Degrees(p._2))
    }

    def hasNext: Boolean = counter < cardinality

    def next(): Points = {
      val result = Points(lastPoint, point)
      //println(s"next:  result = $result")
      lastPoint = point
      lastState = state
      counter = counter + 1
      if (hasNext) {
        state = curve match {
          case sfc: SpaceFillingCurve2D => counter
          case tri: Triangle => state.asInstanceOf[Triangle].next.orNull
        }
        point = getPoint
      }
      result
    }
  }

  case class FixedSample(input: Seq[Points]) extends Sampler {
    val itr: Iterator[Points] = input.iterator
    def hasNext: Boolean = itr.hasNext
    def next(): Points = itr.next()
  }

  case class RandomSample(numPoints: Long) extends Sampler {
    var counter: Long = 0
    def hasNext: Boolean = counter < numPoints
    def next(): Points = { counter = counter + 1; generatePoints() }
  }

  case class QuerySampler(numPoints: Long, sizeDegrees: Double) extends Sampler {
    var counter: Long = 0
    def hasNext: Boolean = counter < numPoints
    def next(): Points = {
      counter = counter + 1
      generatePoints(Option(sizeDegrees))
    }
  }

  abstract class Aggregator(sampler: Sampler, verbose: Boolean, curves: Curve*) {
    val distances: Map[Curve,Distances] = curves.map(curve => curve -> Distances(curve.name, curve)).toMap

  }

  case class Summary(sampler: Sampler, verbose: Boolean, curves: Curve*) extends Aggregator(sampler, verbose, curves:_*) {
    def exhaust(ps: PrintStream): Unit = {
      while (sampler.hasNext) {
        val sample = sampler.next()
        distances.map {
          case (_, dists) =>
            dists.accumulate(sample, verbose = verbose)
        }
      }
      distances.foreach { case (_, dists) => dists.summarize(ps) }
    }
  }

  def numQueryRanges(curve: Curve, query: Points): String = {
    val x0: Double = Math.min(query.a.x.degrees, query.b.x.degrees)
    val y0: Double = Math.min(query.a.y.degrees, query.b.y.degrees)
    val x1: Double = Math.max(query.a.x.degrees, query.b.x.degrees)
    val y1: Double = Math.max(query.a.y.degrees, query.b.y.degrees)
    val ranges = curve.toRanges(x0, y0, x1, y1, None)
    val numRanges = ranges.size
    val numCells = ranges.map(_.size).sum
    assert(numRanges > 0, s"Curve ${curve.name} cardinality ${curve.cardinality} has an empty range over X($x0, $x1) Y($y0, $y1)!")
    //if (numRanges <= 0) println(s"[WARNING] Curve ${curve.name} cardinality ${curve.cardinality} has an empty range over X($x0, $x1) Y($y0, $y1)!")
    s"$numRanges,$numCells"
  }

  case class Table(sampler: Sampler, verbose: Boolean, curves: Curve*) extends Aggregator(sampler, verbose, curves:_*) {
    val columns = Seq("plane", "sphere", "index", "nqr", "nqc")
    val allcols: Seq[String] = CartesianProductIterable(Seq(columns, curves.map(_.name))).iterator.toSeq.map(_.mkString("_"))
    def exhaust(ps: PrintStream): Unit = {
      ps.println("from_wkt,to_wkt,plane,sphere," + allcols.mkString(","))
      var n: Long = 0L
      while (sampler.hasNext) {
        val sample = sampler.next()
        if ((n % 100) == 0) println(s"  Processing sample $n:  ${sample.a.wkt}, ${sample.b.wkt}...")
        n += 1L
        ps.print(s"${sample.a.wkt},${sample.b.wkt},${sample.dPlane()},${sample.dSphere()}")
        curves.foreach { curve => {
          val dists = distances(curve)
          ps.print("," + dists.csvLine(sample, verbose = verbose))
          ps.print("," + numQueryRanges(curve, sample))
        }}
        ps.println()
      }
    }
  }

  // set up
  println("Setting up...")
  val bitsPrecision: Long = 24
  //require((bitsPrecision % 2) == 0, "bitsPrecision must be divisible by 2 for Z2, H2")
  require((bitsPrecision % 3) == 0, "bitsPrecision must be divisible by 3 for T2")
  require(bitsPrecision < 64, "Must not have more bits than fit in a Long")
  val quadCardinality = 1L << bitsPrecision
  val cardPerDim = 1L << (bitsPrecision >> 1L)
  println(s"  Bits precision $bitsPrecision, quad-tree cardinality $quadCardinality")
  val z2 = new ZCurve2D(cardPerDim.toInt)
  println(s"  Z2 cardinality ${z2.cardinality}")
  val h2 = new HilbertCurve2D(cardPerDim.toInt)
  println(s"  H2 cardinality ${h2.cardinality}")
  val TriangleDepth = bitsPrecision.toInt / 3
  val t2 = TriN.createLowestIndex(TriangleDepth)
  println(s"  Triangle depth:  ${TriangleDepth}")
  println(s"  Triangle cardinality:  ${t2.cardinality}")
  println(s"    Quad equivalent bit-string length:  ${(Math.log(t2.cardinality) / Math.log(2.0)) - 0.0}")
  val t33 = TriN.createLowestIndex(11)
  val t36 = TriN.createLowestIndex(12)

  // unit testing
  println("\nUnit test results...")
  val point = Point(Degrees(-78.0), Degrees(38.0))
  val points = Points(point, point)
  val dIndexZ2 = points.dIndex(z2)
  println(s"  dIndex(z2):  $dIndexZ2")
  assert(dIndexZ2 == 0.0)
  val dIndexH2 = points.dIndex(h2)
  println(s"  dIndex(h2):  $dIndexH2")
  assert(dIndexH2 == 0.0)
  val dSphere = points.dSphere()
  println(s"  dSphere(z2):  $dSphere")
  assert(dSphere >= 0.0 && dSphere <= 0.00001)
  assert(dBitstring("100", "100") == 0.0)
  assert(dBitstring("110", "100") == 0.25)
  assert(dBitstring("100", "101") == 0.125)
  assert(TriN.createLowestIndex(1).cardinality == 8)
  assert(TriN.createLowestIndex(2).cardinality == 32)
  assert(TriN.createLowestIndex(3).cardinality == 128)
  assert(TriN.createLowestIndex(4).cardinality == 512)

  // more unit tests
  {
    println(s"\nMore triangle unit tests...")

    val depth = 21
    val idx = TriN.index(-78.495150, 38.075776, depth)
    println(s"  Index CCRi:  $idx decimal, ${TriN.indexOctalString(idx, depth)}")
    val t = TriN.invIndex(idx, depth)
    val point = t.bounds.geoCenter

    println(s"  From index:  $t")
    println(s"    centroid:  $point")

    assert(Math.abs(point._1 - -78.495150) <= Math.pow(10.0, 2-(TriangleDepth >> 2)))
    assert(Math.abs(point._2 - 38.075776) <= Math.pow(10.0, 2-(TriangleDepth >> 2)))

  }

  // validate that the compact indexes work as expected
  def testCompactIndex(fullIndexBitString: String, expectedCompactIndex: Long): Unit = {
    require(fullIndexBitString.length % 3 == 0)
    val depth: Int = fullIndexBitString.length / 3
    val t: Triangle = TriN.invIndex(java.lang.Long.parseLong(fullIndexBitString, 2), depth)
    val compact: Long = t.compactIndex
    assert(compact == expectedCompactIndex, s"The compact index failed for $t:  expected $expectedCompactIndex, but got $compact")
  }
  testCompactIndex("000", 0)
  testCompactIndex("001", 1)
  testCompactIndex("010", 2)
  testCompactIndex("011", 3)
  testCompactIndex("100", 4)
  testCompactIndex("101", 5)
  testCompactIndex("110", 6)
  testCompactIndex("111", 7)
  testCompactIndex("111000001001010100010000", java.lang.Long.parseLong("11100010110111000", 2))
  val t0: Triangle = TriN.getTriangle(-78.5, 38.1, 8)
  val range0: IndexRange = t0.getChildIndexRange(9)
  assert(range0.size == 4, s"Range size is wrong:  expected 4, but got $range0 -> ${range0.size}")
  val range1: IndexRange = t0.getChildIndexRange(12)
  assert(range1.size == 256, s"Range size is wrong:  expected 64, but got $range1 -> ${range1.size}")

  println("\nRunning...")

  //var ps: PrintStream = System.out
  var ps: PrintStream = new PrintStream(new FileOutputStream("test-distance.csv"))

  val exhaustive = Seq[Sampler](
    CellIterator("Z2", z2),
    CellIterator("H2", h2),
    CellIterator("T2", t2)
  )

  val numRandomPoints: Long = 10
  val random = Seq(RandomSample(numRandomPoints))

  val numRandomQueries: Long = 7000
  val querySizeDegrees: Double = 1.0
  val queries = Seq(QuerySampler(numRandomQueries, querySizeDegrees))

  val smallQueries = Seq(QuerySampler(10, querySizeDegrees))

  val CharlottesvillePoints = Seq[Points](
    Points(
      Point(Degrees(-78.495150), Degrees(38.075776)),  // CCRi
      Point(Degrees(-78.688256), Degrees(38.054444))  // Eichelberger
    )
  )
  val cville = Seq(FixedSample(CharlottesvillePoints))

  val debugQueries = Seq(FixedSample(Seq(
    /*
    Points(
      Point(Degrees(68.62082281207503), Degrees(39.77771938277874)),
      Point(Degrees(69.62082281207503), Degrees(40.77771938277874))
    ),
     */
    Points(
      //POINT(178.0929491294956 -75.64062932319783), POINT(179.0929491294956 -74.64062932319783)
      //TriBounds.overlaps(R(X 141.875, 142.525 Y -75.641, -74.641), T(X 90.000, 180.000 Y -90.000 0.000 apex 135.000, up false):  yProbe -75.641, 2.000 * (142.525 - 180.000) + 0.000 = -74.950, inside false
      Point(Degrees(178.0929491294956), Degrees(-75.64062932319783)),
      Point(Degrees(179.0929491294956), Degrees(-74.64062932319783))
    )
  )))

  val samplers: Seq[Sampler] = queries

  for (sampler <- samplers) {
    Table(sampler, verbose = false, z2, h2, t2, t33, t36).exhaust(ps)
    //Table(sampler, verbose = false, t2, t36).exhaust(ps)
  }

  ps.close()
}
