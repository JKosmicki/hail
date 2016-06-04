package org.broadinstitute.hail.rest

import collection.mutable
import org.apache.spark.sql.DataFrame
import org.broadinstitute.hail.methods.LinearRegression
import org.broadinstitute.hail.variant._
import org.broadinstitute.hail.Utils._
import breeze.linalg.{DenseMatrix, DenseVector}

import org.http4s.headers.`Content-Type`
import org.http4s._
import org.http4s.MediaType._
import org.http4s.dsl._
import org.http4s.server._
import scala.concurrent.ExecutionContext

import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}

case class VariantFilter(operand: String,
  operator: String,
  value: String,
  operand_type: String) {

  def filterDf(df: DataFrame, blockWidth: Int): DataFrame = {
    operand match {
      case "chrom" =>
        df.filter(df("contig") === "chr" + value)
      case "pos" =>
        val v = value.toInt // FIXME: if not int, throw error
        val vblock = v / blockWidth
        operator match {
          case "eq" =>
            df.filter(df("block") === vblock)
              .filter(df("start") === v)
          case "gte" =>
            df.filter(df("block") >= vblock)
              .filter(df("start") >= v)
          case "gt" =>
            df.filter(df("block") >= vblock)
              .filter(df("start") > v)
          case "lte" =>
            df.filter(df("block") <= vblock)
              .filter(df("start") <= v)
          case "lt" =>
            df.filter(df("block") <= vblock)
              .filter(df("start") < v)
        }
    }
  }
}


case class Covariate(`type`: String,
  name: Option[String],
  chrom: Option[String],
  pos: Option[Int],
  ref: Option[String],
  alt: Option[String])

case class GetStatsRequest(passback: Option[String],
  md_version: Option[String],
  api_version: Int,
  phenotype: Option[String],
  covariates: Option[Array[Covariate]],
  variant_filters: Option[Array[VariantFilter]],
  limit: Option[Int],
  count: Option[Boolean],
  sort_by: Option[Array[String]])

case class Stat(chrom: String,
  pos: Int,
  ref: String,
  alt: String,
  `p-value`: Option[Double])

case class GetStatsResult(is_error: Boolean,
  error_message: Option[String],
  passback: Option[String],
  stats: Option[Array[Stat]],
  count: Option[Int])

class RESTFailure(message: String) extends Exception(message)

class T2DService(hcs: HardCallSet, hcs1Mb: HardCallSet, hcs10Mb: HardCallSet, covMap: Map[String, IndexedSeq[Option[Double]]], defaultMinMAC: Int = 0) {

  def getStats(req: GetStatsRequest): GetStatsResult = {
    req.md_version.foreach { md_version =>
      if (md_version != "mdv1")
        throw new RESTFailure(s"Unknown md_version `$md_version'. Available md_versions: mdv1")
    }

    if (req.api_version != 1)
      throw new RESTFailure(s"Unsupported API version `${req.api_version}'. Supported API versions: 1")

    val MaxWidthForHcs = 600000
    val MaxWidthForHcs1Mb = 10000000
    val HardLimit = 100000 // max is around 16k for T2D

    val limit = req.limit.getOrElse(HardLimit)
    if (limit < 0)
      throw new RESTFailure(s"limit must be non-negative: got $limit")

//    val minMAC = req.min_mac.getOrElse(DefaultMinMAC)
//    if (minMAC < 0)
//      throw new RESTFailure(s"min_mac must be non-negative, default is $DefaultMinMAC: got $minMAC")

    val pheno = req.phenotype.getOrElse("T2D")
    val phenoCovs = mutable.Set[String]()
    val variantCovs = new mutable.ArrayBuffer[Variant]()

    req.covariates.foreach { covariates =>
      for (c <- covariates)
        c.`type` match {
          case "phenotype" =>
            c.name match {
              case Some(name) =>
                if (covMap.keySet(name))
                  phenoCovs += name
                else
                  throw new RESTFailure(s"${c.name} is not a valid covariate name")
              case None =>
                throw new RESTFailure("Covariate of type 'phenotype' must include 'name' field in request")
            }
          case "variant" =>
            (c.chrom, c.pos, c.ref, c.alt) match {
              case (Some(chrom), Some(pos), Some(ref), Some(alt)) =>
                variantCovs += Variant(chrom, pos, ref, alt)
              case missingFields =>
                throw new RESTFailure("Covariate of type 'variant' must include 'chrom', 'pos', 'ref', and 'alt' fields in request")
            }
          case other =>
            throw new RESTFailure(s"Supported covariate types are phenotype and variant: got $other")
        }
    }

    if (phenoCovs(pheno))
      throw new RESTFailure(s"$pheno appears as both the response phenotype and a covariate phenotype")

    // FIXME: finish subsetting

    val reqCovMap = covMap.filterKeys(c => phenoCovs(c) || c == pheno)

    //println(reqCovMap)

    // val completeSamples = hcs.sampleIds.filter(s => reqCovMap(s).forall(_.isDefined))

    val sampleFilter: Array[Boolean] = hcs.sampleIds.indices.map(si => reqCovMap.valuesIterator.forall(_(si).isDefined)).toArray

    //println(sampleFilter.mkString(","))

    val n = hcs.nSamples

    val reqSampleIndex: Array[Int] = (0 until n).filter(sampleFilter).toArray

    //println(s"reqSampleIndex = ${reqSampleIndex.mkString(",")}")

    val n0 = reqSampleIndex.size

    val reduceSampleIndex: Array[Int] = Array.ofDim[Int](n)
    (0 until n0).foreach(i => reduceSampleIndex(reqSampleIndex(i)) = i) // FIXME: Improve this

    //println(s"reduceSampleIndex = $reduceSampleIndex")

    val y: DenseVector[Double] =
      covMap.get(pheno) match {
        case Some(a) => DenseVector(reqSampleIndex.flatMap(a(_)))
        case None => throw new RESTFailure(s"$pheno is not a valid phenotype name")
      }

    //println(s"y = $y")

    val nCov = phenoCovs.size + variantCovs.size // FIXME: pass in sample filter to variantGts
    val covArray = phenoCovs.toArray.flatMap(c => reqSampleIndex.flatMap(covMap(c)(_))) ++ variantCovs.toArray.flatMap(v => hcs.variantGts(v, n0, sampleFilter, reduceSampleIndex))
    val cov: Option[DenseMatrix[Double]] =
      if (nCov > 0)
        Some(new DenseMatrix[Double](n0, nCov, covArray))
      else
        None

    //println(s"cov = $cov")

    var minPos = 0
    var maxPos = Int.MaxValue // 2,147,483,647 is greater than length of longest chromosome

    var minMAC = 0
    var maxMAC = Int.MaxValue

    val chromFilters = mutable.Set[VariantFilter]()
    val posFilters = mutable.Set[VariantFilter]()
    val macFilters = mutable.Set[VariantFilter]()

    var isSingleVariant = false
    var useDefaultMinMAC = true

    req.variant_filters.foreach(_.foreach { f =>
      f.operand match {
        case "chrom" =>
          if (!(f.operator == "eq" && f.operand_type == "string"))
            throw new RESTFailure(s"chrom filter operator must be 'eq' and operand_type must be 'string': got '${f.operator}' and '${f.operand_type}'")
          chromFilters += f
        case "pos" =>
          if (f.operand_type != "integer")
            throw new RESTFailure(s"pos filter operand_type must be 'integer': got '${f.operand_type}'")
          f.operator match {
            case "gte" => minPos = minPos max f.value.toInt
            case "gt" => minPos = minPos max (f.value.toInt + 1)
            case "lte" => maxPos = maxPos min f.value.toInt
            case "lt" => maxPos = maxPos min (f.value.toInt - 1)
            case "eq" => isSingleVariant = true
            case other =>
              throw new RESTFailure(s"pos filter operator must be 'gte', 'gt', 'lte', 'lt', or 'eq': got '$other'")
          }
          posFilters += f
        case "mac" =>
          if (f.operand_type != "integer")
            throw new RESTFailure(s"mac filter operand_type must be 'integer': got '${f.operand_type}'")
          f.operator match {
            case "gte" => minMAC = minMAC max f.value.toInt
            case "gt" => minMAC = minMAC max (f.value.toInt + 1)
            case "lte" => maxMAC = maxMAC min f.value.toInt
            case "lt" => maxMAC = maxMAC min (f.value.toInt - 1)
            case other =>
              throw new RESTFailure(s"mac filter operator must be 'gte', 'gt', 'lte', 'lt': got '$other'")
          }
          useDefaultMinMAC = false
        case other => throw new RESTFailure(s"Filter operand must be 'chrom' or 'pos': got '$other'")
      }
    })

    if (chromFilters.isEmpty)
      chromFilters += VariantFilter("chrom", "eq", "1", "string")

    val width =
      if (isSingleVariant)
        1
      else
        maxPos - minPos

    // val widthMAC = maxMAC - minMAC

    val hcsToUse =
      if (width <= MaxWidthForHcs)
        hcs
      else if (width <= MaxWidthForHcs1Mb)
        hcs1Mb
      else
        hcs10Mb

    var df = hcsToUse.df
    val blockWidth = hcsToUse.blockWidth

    chromFilters.foreach(f => df = f.filterDf(df, blockWidth))
    posFilters.foreach(f => df = f.filterDf(df, blockWidth))

    if (useDefaultMinMAC)
      minMAC = defaultMinMAC

    var stats = LinearRegression(hcsToUse.copy(df = df), y, cov, sampleFilter, reduceSampleIndex, minMAC, maxMAC)
      .rdd
      .map { case (v, olrs) => Stat(v.contig, v.start, v.ref, v.alt, olrs.map(_.p)) }
      .take(limit)

    // FIXME: test timing with .take(limit), if slow consider this:
    // if (stats.length > limit)
    //  stats = stats.take(limit)

    req.sort_by.foreach { a =>
      if (! a.areDistinct())
        throw new RESTFailure("sort_by arguments must be distinct")

      var fields = a.toList

      // Default sort order is [pos, ref, alt] and sortBy is stable
      if (fields.nonEmpty && fields.head == "pos") {
        fields = fields.tail
        if (fields.nonEmpty && fields.head == "ref") {
          fields = fields.tail
          if (fields.nonEmpty && fields.head == "alt")
            fields = fields.tail
        }
      }

      fields.reverse.foreach { f =>
        stats = f match {
          case "pos" => stats.sortBy(_.pos)
          case "ref" => stats.sortBy(_.ref)
          case "alt" => stats.sortBy(_.alt)
          case "p-value" => stats.sortBy(_.`p-value`.getOrElse(2d))
          case _ => throw new RESTFailure(s"Valid sort_by arguments are `pos', `ref', `alt', and `p-value': got $f")
        }
      }
    }
    
    if (req.count.getOrElse(false))
      GetStatsResult(is_error = false, None, req.passback, None, Some(stats.size))
    else
      GetStatsResult(is_error = false, None, req.passback, Some(stats), Some(stats.size))
  }

  def service(implicit executionContext: ExecutionContext = ExecutionContext.global): HttpService = Router(
    "" -> rootService)

  def rootService(implicit executionContext: ExecutionContext) = HttpService {
    case _ -> Root =>
      // The default route result is NotFound. Sometimes MethodNotAllowed is more appropriate.
      MethodNotAllowed()

    case req@POST -> Root / "getStats" =>
      println("in getStats")

      req.decode[String] { text =>
        info("request: " + text)

        implicit val formats = Serialization.formats(NoTypeHints)

        var passback: Option[String] = None
        try {
          val getStatsReq = read[GetStatsRequest](text)
          passback = getStatsReq.passback
          val result = getStats(getStatsReq)
          Ok(write(result))
            .putHeaders(`Content-Type`(`application/json`))
        } catch {
          case e: Exception =>
            val result = GetStatsResult(is_error = true, Some(e.getMessage), passback, None, None)
            BadRequest(write(result))
              .putHeaders(`Content-Type`(`application/json`))
        }
      }
  }
}
