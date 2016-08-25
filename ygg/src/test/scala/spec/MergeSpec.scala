package ygg.tests

import ygg.common._
import scalaz._, Scalaz._
import ygg.table._
import ygg.json._

class MergeSpec extends quasar.Qspec with ColumnarTableModuleTestSupport with IndicesModule {
  import trans._
  import TableModule._

  implicit val fid = NaturalTransformation.refl[Need]

  class Table(slices: StreamT[Need, Slice], size: TableSize) extends ColumnarTable(slices, size) {
    import trans._
    def load(apiKey: APIKey, jtpe: JType)                                       = ???
    def sort(sortKey: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean) = Need(this)
    def groupByN(groupKeys: Seq[TransSpec1], valueSpec: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean): Need[Seq[Table]] =
      ???
  }

  trait TableCompanion extends ColumnarTableCompanion {
    def apply(slices: StreamT[Need, Slice], size: TableSize)                                                           = new Table(slices, size)
    def singleton(slice: Slice)                                                                                        = new Table(slice :: StreamT.empty[Need, Slice], ExactSize(1))
    def align(sourceLeft: Table, alignOnL: TransSpec1, sourceRight: Table, alignOnR: TransSpec1): Need[Table -> Table] = abort("not implemented here")
  }

  object Table extends TableCompanion

  "merge" should {
    "avoid crosses in trivial cases" in {
      val foo = fromJson(jsonMany"""
        {"key":[5908438637678328470],"value":{"a":0,"b":4}}
        {"key":[5908438637678328471],"value":{"a":1,"b":5}}
        {"key":[5908438637678328472],"value":{"a":2,"b":6}}
        {"key":[5908438637678328473],"value":{"a":3,"b":7}}
      """.toStream)

      val bar = fromJson(jsonMany"""
        {"key":[5908438637678328576],"value":{"a":-1,"c":8,"b":-1}}
        {"key":[5908438637678328577],"value":{"a":1,"c":9,"b":-1}}
        {"key":[5908438637678328578],"value":{"a":-1,"c":10,"b":6}}
        {"key":[5908438637678328579],"value":{"a":3,"c":11,"b":7}}
        {"key":[5908438637678328580],"value":{"a":0,"c":12,"b":-1}}
        {"key":[5908438637678328581],"value":{"a":0,"c":13,"b":-1}}
      """.toStream)

      val resultJson = jsonMany"""
        {"key":[5908438637678328470,5908438637678328580],"value":{"b":4,"c":12,"a":0,"fa":{"b":4,"a":0}}}
        {"key":[5908438637678328470,5908438637678328581],"value":{"b":4,"c":13,"a":0,"fa":{"b":4,"a":0}}}
        {"key":[5908438637678328471,5908438637678328577],"value":{"b":5,"c":9,"a":1,"fa":{"b":5,"a":1}}}
        {"key":[5908438637678328472,5908438637678328578],"value":{"b":6,"c":10,"a":2,"fa":{"b":6,"a":2}}}
        {"key":[5908438637678328473,5908438637678328579],"value":{"b":7,"c":11,"a":3,"fa":{"b":7,"a":3}}}
      """

      val keyField   = CPathField("key")
      val valueField = CPathField("value")
      val aField     = CPathField("a")
      val bField     = CPathField("b")
      val cField     = CPathField("c")
      val oneField   = CPathField("1")
      val twoField   = CPathField("2")

      val grouping =
        GroupingAlignment(
          TransSpec1.Id,
          TransSpec1.Id,
          GroupingSource(
            bar,
            root select keyField.name,
            Some(
              InnerObjectConcat(
                ObjectDelete(root, Set(valueField)),
                WrapObject(DerefObjectStatic(DerefObjectStatic(root, valueField), cField), "value"))),
            0,
            GroupKeySpecOr(
              GroupKeySpecSource(oneField, DerefObjectStatic(DerefObjectStatic(root, valueField), aField)),
              GroupKeySpecSource(twoField, DerefObjectStatic(DerefObjectStatic(root, valueField), bField)))
          ),
          GroupingSource(
            foo,
            root select keyField.name,
            Some(InnerObjectConcat(ObjectDelete(root, Set(valueField)), WrapObject(DerefObjectStatic(root, valueField), "value"))),
            3,
            GroupKeySpecAnd(
              GroupKeySpecSource(oneField, DerefObjectStatic(DerefObjectStatic(root, valueField), aField)),
              GroupKeySpecSource(twoField, DerefObjectStatic(DerefObjectStatic(root, valueField), bField)))
          ),
          GroupingSpec.Intersection)

      def evaluator(key: RValue, partition: GroupId => Need[Table]) = {
        val K0 = RValue.fromJValue(json"""{"1":0,"2":4}""")
        val K1 = RValue.fromJValue(json"""{"1":1,"2":5}""")
        val K2 = RValue.fromJValue(json"""{"1":2,"2":6}""")
        val K3 = RValue.fromJValue(json"""{"1":3,"2":7}""")

        val r0 = fromJson(jsonMany"""
          {"key":[5908438637678328470,5908438637678328580],"value":{"b":4,"c":12,"a":0,"fa":{"b":4,"a":0}}}
          {"key":[5908438637678328470,5908438637678328581],"value":{"b":4,"c":13,"a":0,"fa":{"b":4,"a":0}}}
        """.toStream)

        val r1 = fromJson(jsonMany"""
          {"key":[5908438637678328471,5908438637678328577],"value":{"b":5,"c":9,"a":1,"fa":{"b":5,"a":1}}}
        """.toStream)

        val r2 = fromJson(jsonMany"""
          {"key":[5908438637678328472,5908438637678328578],"value":{"b":6,"c":10,"a":2,"fa":{"b":6,"a":2}}}
        """.toStream)

        val r3 = fromJson(jsonMany"""
          {"key":[5908438637678328473,5908438637678328579],"value":{"b":7,"c":11,"a":3,"fa":{"b":7,"a":3}}}
        """.toStream)

        Need {
          key match {
            case K0 => r0
            case K1 => r1
            case K2 => r2
            case K3 => r3
            case _  => abort("Unexpected group key")
          }
        }
      }

      val result = Table.merge(grouping)(evaluator)
      result.flatMap(_.toJson).copoint.toSet must_== resultJson.toSet
    }

    "execute the medals query without a cross" in {
      val medals = fromJson(jsonMany"""
        {"key":[5908438637678314371],"value":{"Edition":"2000","Gender":"Men"}}
        {"key":[5908438637678314372],"value":{"Edition":"1996","Gender":"Men"}}
        {"key":[5908438637678314373],"value":{"Edition":"2008","Gender":"Men"}}
        {"key":[5908438637678314374],"value":{"Edition":"2004","Gender":"Women"}}
        {"key":[5908438637678314375],"value":{"Edition":"2000","Gender":"Women"}}
        {"key":[5908438637678314376],"value":{"Edition":"1996","Gender":"Women"}}
        {"key":[5908438637678314377],"value":{"Edition":"2008","Gender":"Men"}}
        {"key":[5908438637678314378],"value":{"Edition":"2004","Gender":"Men"}}
        {"key":[5908438637678314379],"value":{"Edition":"1996","Gender":"Men"}}
        {"key":[5908438637678314380],"value":{"Edition":"2008","Gender":"Women"}}
      """.toStream)

      val resultJson = jsonMany"""
        {"key":[],"value":{"year":"1996","ratio":139.0}}
        {"key":[],"value":{"year":"2000","ratio":126.0}}
        {"key":[],"value":{"year":"2004","ratio":122.0}}
        {"key":[],"value":{"year":"2008","ratio":119.0}}
      """.toStream

      val keyField     = CPathField("key")
      val valueField   = CPathField("value")
      val genderField  = CPathField("Gender")
      val editionField = CPathField("Edition")
      val extra0Field  = CPathField("extra0")
      val extra1Field  = CPathField("extra1")
      val oneField     = CPathField("1")

      val grouping =
        GroupingAlignment(
          TransSpec1.Id,
          TransSpec1.Id,
          GroupingSource(
            medals,
            root select keyField.name,
            Some(
              InnerObjectConcat(
                ObjectDelete(root, Set(valueField)),
                WrapObject(DerefObjectStatic(DerefObjectStatic(root, valueField), genderField), "value"))),
            0,
            GroupKeySpecAnd(
              GroupKeySpecSource(
                extra0Field,
                Filter(
                  EqualLiteral(DerefObjectStatic(DerefObjectStatic(root, valueField), genderField), CString("Men"), false),
                  EqualLiteral(DerefObjectStatic(DerefObjectStatic(root, valueField), genderField), CString("Men"), false))),
              GroupKeySpecSource(oneField, DerefObjectStatic(DerefObjectStatic(root, valueField), editionField)))
          ),
          GroupingSource(
            medals,
            root select keyField.name,
            Some(
              InnerObjectConcat(
                ObjectDelete(root, Set(valueField)),
                WrapObject(DerefObjectStatic(DerefObjectStatic(root, valueField), genderField), "value"))),
            2,
            GroupKeySpecAnd(
              GroupKeySpecSource(
                extra1Field,
                Filter(
                  EqualLiteral(DerefObjectStatic(DerefObjectStatic(root, valueField), genderField), CString("Women"), false),
                  EqualLiteral(DerefObjectStatic(DerefObjectStatic(root, valueField), genderField), CString("Women"), false))),
              GroupKeySpecSource(oneField, DerefObjectStatic(DerefObjectStatic(root, valueField), editionField)))
          ),
          GroupingSpec.Intersection)

      def evaluator(key: RValue, partition: GroupId => Need[Table]) = {
        val K0 = RValue.fromJValue(json"""{"1":"1996","extra0":true,"extra1":true}""")
        val K1 = RValue.fromJValue(json"""{"1":"2000","extra0":true,"extra1":true}""")
        val K2 = RValue.fromJValue(json"""{"1":"2004","extra0":true,"extra1":true}""")
        val K3 = RValue.fromJValue(json"""{"1":"2008","extra0":true,"extra1":true}""")

        val r0 = fromJson(jsonMany"""{"key":[],"value":{"year":"1996","ratio":139.0}}""".toStream)
        val r1 = fromJson(jsonMany"""{"key":[],"value":{"year":"2000","ratio":126.0}}""".toStream)
        val r2 = fromJson(jsonMany"""{"key":[],"value":{"year":"2004","ratio":122.0}}""".toStream)
        val r3 = fromJson(jsonMany"""{"key":[],"value":{"year":"2008","ratio":119.0}}""".toStream)

        Need {
          key match {
            case K0 => r0
            case K1 => r1
            case K2 => r2
            case K3 => r3
            case _  => abort("Unexpected group key")
          }
        }
      }

      val result = Table.merge(grouping)(evaluator)
      result.flatMap(_.toJson).copoint.toSet must_== resultJson.toSet
    }
  }
}
