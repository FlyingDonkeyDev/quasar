package ygg.tests

import ygg.common._
import scalaz._, Scalaz._
import JsonTestSupport._
import ygg.json._

trait TakeRangeSpec extends ColumnarTableQspec {
  import SampleData._

  private def jsonFourValues = jsonMany"""
    {"key":[1],"value":"foo"}
    {"key":[2],"value":12}
    {"key":[3],"value":{"baz":true}}
    {"key":[4],"value":"ack"}
  """
  private def jsonEightValues = (jsonFourValues ++ jsonMany"""
    {"key":[5],"value":"ack2"}
    {"key":[6],"value":"ack3"}
    {"key":[7],"value":"ack4"}
    {"key":[8],"value":"ack5"}
  """).toStream

  def checkTakeRange = {
    implicit val gen: Arbitrary[SampleData] = sample(schema)

    prop { (sample: SampleData) =>

      val table          = fromSample(sample)
      val size           = sample.data.size
      val start          = choose(-7, size + 7).sample.get
      val count          = choose(start, size + 7).sample.get
      val takeRangeTable = table.takeRange(start.toLong, count.toLong)
      val result         = toJson(takeRangeTable).copoint

      val expected =
        if (start < 0) Stream()
        else sample.data.toSeq.drop(start).take(count)

      result must_== expected
    }
  }

  def testTakeRangeCommutes: Prop = {
    implicit val arbRange     = Arbitrary(genOffsetAndLen)
    implicit val arbJValueSeq = Arbitrary(genJValueSeq)

    prop { (xs: Seq[JValue], offlen: Int -> Int) =>
      val (start, length) = offlen
      def doSlice(xs: Seq[JValue]): Seq[JValue] =
        if (start < 0 || length <= 0) Seq() else xs drop start take length

      checkCommutes(
        doSlice,
        _.takeRange(start.toLong, length.toLong),
        genJValueSeq
      )
    }
  }

  def testTakeRange = checkTableFun(
    fun      = _.takeRange(1, 2),
    data     = jsonFourValues,
    expected = jsonFourValues.slice(1, 3)
  )
  def testTakeRangeNegStart = checkTableFun(
    fun      = _.takeRange(-1, 5),
    data     = jsonFourValues,
    expected = Seq()
  )
  def testTakeRangeNegTake = checkTableFun(
    fun      = _.takeRange(2, -3),
    data     = jsonFourValues,
    expected = Seq()
  )
  def testTakeRangeLarger = checkTableFun(
    fun      = _.takeRange(2, 17),
    data     = jsonFourValues,
    expected = jsonFourValues.slice(2, 4)
  )
  def testTakeRangeEmpty = checkTableFun(
    fun      = _.takeRange(6, 17),
    data     = jsonFourValues,
    expected = Seq()
  )
  def testTakeRangeAcrossSlices = checkTableFun(
    fun      = _.takeRange(1, 6),
    table    = fromSample(SampleData(jsonEightValues), Some(5)),
    expected = jsonEightValues.slice(1, 7)
  )
  def testTakeRangeSecondSlice = checkTableFun(
    fun      = _.takeRange(5, 2),
    table    = fromSample(SampleData(jsonEightValues), Some(5)),
    expected = jsonEightValues.slice(5, 7)
  )
  def testTakeRangeFirstSliceOnly = checkTableFun(
    fun      = _.takeRange(0, 5),
    table    = fromSample(SampleData(jsonEightValues), Some(5)),
    expected = jsonEightValues.slice(0, 5)
  )
}
