package peregin.gpv.model

import java.io.{InputStream, File}
import scala.xml.{Node, XML}
import generated.GpxType
import peregin.gpv.util.{Logging, Timed}
import org.jdesktop.swingx.mapviewer.GeoPosition
import org.joda.time.DateTime
import javax.xml.datatype.XMLGregorianCalendar
import scala.language.implicitConversions


object Telemetry extends Timed with Logging {

  def load(file: File): Telemetry = loadWith(XML.loadFile(file))

  def load(is: InputStream): Telemetry = loadWith(XML.load(is))

  def loadWith(loadFunc: => Node): Telemetry = timed("load telemetry") {
    val node = loadFunc
    val binding = scalaxb.fromXML[GpxType](node)
    val points = binding.trk.head.trkseg.head.trkpt.map{wyp =>
      val extension = wyp.extensions.flatMap(_.any.map(_.value).headOption.map(_.toString)).
        map(GarminExtension.parse).getOrElse(GarminExtension.empty)
      TrackPoint(
        new GeoPosition(wyp.lat.toDouble, wyp.lon.toDouble), wyp.ele.map(_.toDouble).getOrElse(0d),
        wyp.time, extension
      )
    }
    log.info(s"found ${points.size} track points")
    val data = new Telemetry(points)
    data.analyze()
    log.info(s"elevation boundary ${data.elevationBoundary}")
    data
  }

  implicit def toJoda(xml: Option[XMLGregorianCalendar]): DateTime = {
    xml.map(x => new DateTime(x.toGregorianCalendar.getTime)).getOrElse(sys.error("unable to process data without timestamps"))
  }

  def empty = new Telemetry(Seq.empty)
}

case class Telemetry(track: Seq[TrackPoint]) extends Timed with Logging {

  val elevationBoundary = MinMax.extreme
  val latitudeBoundary = MinMax.extreme
  val longitudeBoundary = MinMax.extreme
  val speedBoundary = MinMax.extreme
  val gradeBoundary = MinMax.extreme
  val cadenceBoundary = MinMax.extreme
  val temperatureBoundary = MinMax.extreme
  val heartRateBoundary = MinMax.extreme

  private var centerPosition = TrackPoint.centerPosition

  def analyze() = timed("analyze GPS data") {
    val n = track.size
    for (i <- 0 until n) {
      val point = track(i)
      elevationBoundary.sample(point.elevation)
      latitudeBoundary.sample(point.position.getLatitude)
      longitudeBoundary.sample(point.position.getLongitude)
      point.extension.cadence.foreach(cadenceBoundary.sample)
      point.extension.temperature.foreach(temperatureBoundary.sample)
      point.extension.heartRate.foreach(heartRateBoundary.sample)
      if (i < n - 1) {
        val nextPoint = track(i + 1)
        point.analyze(nextPoint)
        speedBoundary.sample(point.speed)
        gradeBoundary.sample(point.grade)
      }
    }
    centerPosition = new GeoPosition(latitudeBoundary.mean, longitudeBoundary.mean)
  }

  def centerGeoPosition = centerPosition

  def minTime = track.head.time
  def maxTime = track.last.time

  def totalDistance = track.last.distance
  
  def distanceForProgress(progressInPerc: Double): Option[Double] = {
    if (progressInPerc <= 0d) track.headOption.map(_.distance)
    else if (progressInPerc >= 100) track.lastOption.map(_.distance)
    else (track.headOption, track.lastOption) match {
      case (Some(first), Some(last)) =>
        val dist = interpolate(progressInPerc, first.distance, last.distance)
        Some(dist)
      case _ => None
    }
  }
  
  /**
   * retrieves the interpolated time for the given progress
   * @param progressInPerc is defined between 0 and 100
   */
  def timeForProgress(progressInPerc: Double): Option[DateTime] = {
    if (progressInPerc <= 0d) track.headOption.map(_.time)
    else if (progressInPerc >= 100d) track.lastOption.map(_.time)
    else (track.headOption, track.lastOption) match {
      case (Some(first), Some(last)) =>
        val millis = interpolate(progressInPerc, first.time.getMillis, last.time.getMillis)
        Some(new DateTime(millis.toLong))
      case _ => None
    }
  }

  /**
   * retrieves a progress between 0 and 100
   */
  def progressForTime(t: DateTime): Double = (track.headOption, track.lastOption) match {
    case (Some(first), Some(last)) => progressForTime(t, first.time, last.time)
    case _ => 0d
  }

  // 0 - 100
  def progressForTime(t: DateTime, first: DateTime, last: DateTime): Double = {
    val millis = t.getMillis
    val firstMillis = first.getMillis
    val lastMillis = last.getMillis
    if (millis <= firstMillis) 0d
    else if (millis >= lastMillis) 100d
    else (millis - firstMillis) * 100 / (lastMillis - firstMillis)
  }

  def sonda(relativeTsInMillis: Long): Option[Sonda] = {
    if (track.isEmpty) None
    else Some(sonda(track.head.time.plusMillis(relativeTsInMillis.toInt)))
  }

  def sonda(t: DateTime): Sonda = {
    val tn = track.size
    if (tn < 2) Sonda.zeroAt(t)
    else {
      // find the closest track point with a simple binary search
      // eventually to improve the performance by searching on percentage of time between the endpoints of the list
      def findNearestIndex(list: Seq[TrackPoint], t: DateTime, ix: Int): Int = {
        val n = list.size
        if (n < 2) ix
        else {
          val c = n / 2
          val tp = list(c)
          if (t.isBefore(tp.time)) findNearestIndex(list.slice(0, c), t, ix)
          else findNearestIndex(list.slice(c, n), t, ix + c)
        }
      }
      val ix = findNearestIndex(track, t, 0)
      val tr = track(ix)
      val (left, right) = ix match {
        case 0 => (tr, track(1))
        case last if last >= tn - 1 => (track(tn - 2), tr)
        case _ if t.isBefore(tr.time) => (tr, track(ix + 1))
        case _ => (track(ix - 1), tr)
      }
      interpolate(t, left, right).withTrackIndex(ix)
    }
  }

  def sonda(gp: GeoPosition): Option[Sonda] = {
    if (track.size < 3) None
    else {
      // drop first and last where the distance is incorrect
      val dList = track.map(t => (t.haversineDistanceTo(gp), t))
      val tp = dList.minBy(_._1)._2
      Some(sonda(tp.time))
    }
  }

  def interpolate(t: DateTime, left: TrackPoint, right: TrackPoint): Sonda = {
    val f = progressForTime(t, left.time, right.time)
    val elevation = interpolate(f, left.elevation, right.elevation)
    val distance = left.distance + interpolate(f, 0, left.segment)
    val location = new GeoPosition(
      interpolate(f, left.position.getLatitude, right.position.getLatitude),
      interpolate(f, left.position.getLongitude, right.position.getLongitude)
    )
    val cadence = interpolate(f, left.extension.cadence, right.extension.cadence)
    val heartRate = interpolate(f, left.extension.heartRate, right.extension.heartRate)
    val firstTs = track.head.time.getMillis
    Sonda(t, InputValue(t.getMillis - firstTs, MinMax(0, track.last.time.getMillis - firstTs)),
      location,
      InputValue(elevation, elevationBoundary), InputValue(left.grade, gradeBoundary),
      InputValue(distance, MinMax(0, totalDistance)), InputValue(left.speed, speedBoundary),
      cadence.map(InputValue(_, cadenceBoundary)), heartRate.map(InputValue(_, heartRateBoundary))
    )
  }

  def interpolate(f: Double, left: Double, right: Double): Double = left + f * (right - left) / 100

  def interpolate(f: Double, oLeft: Option[Double], oRight: Option[Double]): Option[Double] = (oLeft, oRight) match {
    case (Some(l), Some(r)) => Some(interpolate(f, l, r))
    case _ => None
  }
}
