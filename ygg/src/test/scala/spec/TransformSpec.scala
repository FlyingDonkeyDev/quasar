package ygg.tests

import ygg.common._
import scalaz._, Scalaz._
import TestSupport._
import ygg.table._
import ygg.json._

import scala.util.Random

trait TransformSpec extends TableQspec {
  import CValueGenerators._
  import SampleData._
  import trans._

  def checkTransformLeaf = checkSpecDefault(Fn.source)(identity)
  def checkMap1          = checkSpecDefault(Fn.valueIsEven("value"))(_ map (_ \ "value") collect { case JNum(x) => JBool(x % 2 == 0) })
  def checkMetaDeref     = checkSpecDefault(DerefMetadataStatic(Fn.source, CPathMeta("foo")))(_ => Nil)
  def checkTrueFilter    = checkSpecDefault(Fn.constantTrue)(identity)

  def testMap1IntLeaf: Prop = checkSpecData(
    spec     = Map1(Fn.source, F1Expr.negate),
    data     = -10 to 10 map (n => json"$n"),
    expected = -10 to 10 map (n => json"${-n}")
  )
  def testMap1ArrayObject: Prop = checkSpecData(
    spec = Map1(root.value, F1Expr.negate),
    data = jsonMany"""
      {"key":[1],"value":{"foo":12}}
      {"key":[1],"value":[30]}
      {"key":[1],"value":20}
    """,
    expected = Seq(json"-20")
  )

  def testDeepMap1CoerceToDouble: Prop = checkSpecData(
    spec = DeepMap1(root.value, F1Expr.coerceToDouble),
    data = jsonMany"""
      {"key":[1],"value":12}
      {"key":[2],"value":34.5}
      {"key":[3],"value":31.9}
      {"key":[3],"value":{"baz":31}}
      {"key":[3],"value":"foo"}
      {"key":[4],"value":20}
    """,
    expected = json"""[ 12, 34.5, 31.9, { "baz": 31 }, 20 ]""".asArray.elements
  )

  def testMap1CoerceToDouble: Prop = checkSpecData(
    spec = Map1(root.value, F1Expr.coerceToDouble),
    data = jsonMany"""
      {"key":[1],"value":12}
      {"key":[2],"value":34.5}
      {"key":[3],"value":31.9}
      {"key":[3],"value":{"baz":31}}
      {"key":[3],"value":"foo"}
      {"key":[4],"value":20}
    """,
    expected = json"""[ 12, 34.5, 31.9, 20 ]""".asArray.elements
  )

  def checkFilter = {
    val spec = Filter(Fn.source, Fn.valueIsEven("value"))
    checkSpecDefault(spec)(_ map (_ \ "value") filter { case JNum(x) => x % 2 == 0 ; case _ => false })
  }

  def testMod2Filter = checkSpecDataId(
    spec = Filter(Fn.source, Fn.valueIsEven("value")),
    data = jsonMany"""
      { "value":-6.846973248137671E+307, "key":[7.0] }
      { "value":-4611686018427387904, "key":[5.0] }
    """
  )

  def checkObjectDeref: Prop = {
    implicit val gen = sample(objectSchema(_, 3))
    TableProp(sd =>
      TableTest(
        fromSample(sd),
        select(sd.fieldHeadName),
        sd.data map (_ apply sd.fieldHead) filter (_.isDefined)
      )
    ).check()
  }

  def checkArrayDeref: Prop = {
    implicit val gen = sample(arraySchema(_, 3))
    TableProp(sd =>
      TableTest(
        fromSample(sd),
        DerefArrayStatic(Fn.source, CPathIndex(sd.fieldHeadIndex)),
        sd.data map (_ apply sd.fieldHeadIndex) filter (_.isDefined)
      )
    ).check()
  }

  def checkMap2Eq: Prop = {
    implicit val gen = sample(_ => Seq(JPath("value1") -> CDouble, JPath("value2") -> CLong))
    TableProp(sd =>
      TableTest(
        fromSample(sd),
        Map2(root.value.value1, root.value.value2, lookupF2(Nil, "eq")),
        sd.data flatMap { jv =>
          ((jv \ "value" \ "value1"), (jv \ "value" \ "value2")) match {
            case (JNum(x), JNum(y)) => Some(JBool(x == y))
            case _                  => None
          }
        }
      )
    ).check()
  }

  def checkMap2Add = {
    implicit val gen = sample(_ => Seq(JPath("value1") -> CLong, JPath("value2") -> CLong))
    prop { (sample: SampleData) =>
      val table = fromSample(sample)
      val results = toJson(table.transform {
        Map2(
          root.value.value1,
          root.value.value2,
          lookupF2(Nil, "add")
        )
      })

      val expected = sample.data flatMap { jv =>
        ((jv \ "value" \ "value1"), (jv \ "value" \ "value2")) match {
          case (JNum(x), JNum(y)) => Some(JNum(x + y))
          case _                  => None
        }
      }

      results.copoint must_== expected
    }
  }

  def testMap2ArrayObject = checkSpecData(
    spec = Map2(root.value1, root.value2, lookupF2(Nil, "add")),
    data = jsonMany"""
      {"value1":{"foo":12},"value2":[1]}
      {"value1":[30],"value2":[1]}
      {"value1":20,"value2":[1]}
      {"value1":{"foo":-188},"value2":77}
      {"value1":3,"value2":77}
    """,
    expected = Seq(JNum(80))
  )

  def checkEqualSelf = {
    implicit val gen = defaultASD
    prop { (sample: SampleData) =>
      val table    = fromSample(sample)
      val results  = toJson(table transform Equal(Fn.source, Fn.source))
      val expected = Stream.fill(sample.data.size)(JBool(true))

      results.copoint must_=== expected
    }
  }

  def checkEqualSelfArray = {
    val data: Seq[JValue]  = json"""[[9,10,11]]""".asArray.elements map (k => json"""{ "key": [0], "value": $k }""")
    val data2: Seq[JValue] = List(json"""{"key":[],"value":[9,10,11]}""")

    val table   = fromJson(data)
    val table2  = fromJson(data2)

    val leftIdentitySpec    = select(Leaf(SourceLeft), "key")
    val rightIdentitySpec   = select(Leaf(SourceRight), "key")
    val newIdentitySpec     = OuterArrayConcat(leftIdentitySpec, rightIdentitySpec)
    val wrappedIdentitySpec = trans.WrapObject(newIdentitySpec, "key")
    val leftValueSpec       = select(Leaf(SourceLeft), "value")
    val rightValueSpec      = select(Leaf(SourceRight), "value")
    val wrappedValueSpec    = trans.WrapObject(Equal(leftValueSpec, rightValueSpec), "value")

    val results = toJson(table.cross(table2)(InnerObjectConcat(wrappedIdentitySpec, wrappedValueSpec)))
    val expected = data map {
      case jo @ JObject(fields) => jo.set("value", JBool(fields("value") == json"[ 9, 10, 11 ]"))
      case x                    => abort(s"$x")
    }

    results.copoint must_=== expected.toStream
  }

  def testSimpleEqual = {
    val array: JValue = json"""
      [{
        "value":{
          "value2":-2874857152017741205
        },
        "key":[2.0,1.0,2.0]
      },
      {
        "value":{
          "value1":2354405013357379940,
          "value2":2354405013357379940
        },
        "key":[2.0,2.0,1.0]
    }]"""

    val data: Stream[JValue] = array.asArray.elements.toStream
    val sample               = SampleData(data)
    val table                = fromSample(sample)
    val results              = toJson(table transform Equal(root.value.value1, root.value.value2))

    val expected = data flatMap { jv =>
      ((jv \ "value" \ "value1"), (jv \ "value" \ "value2")) match {
        case (_, JUndefined) => None
        case (JUndefined, _) => None
        case (x, y)          => Some(JBool(x == y))
      }
    }

    results.copoint mustEqual expected
  }

  def testAnotherSimpleEqual = {
    val array: JValue = json"""
      [{
        "value":{
          "value2":-2874857152017741205
        },
        "key":[2.0,1.0,2.0]
      },
      {
        "value":null,
        "key":[2.0,2.0,2.0]
      }]"""

    val data: Stream[JValue] = array.asArray.elements.toStream
    val sample               = SampleData(data)
    val table                = fromSample(sample)
    val results              = toJson(table transform Equal(root.value.value1, root.value.value2))

    val expected = data flatMap { jv =>
      ((jv \ "value" \ "value1"), (jv \ "value" \ "value2")) match {
        case (_, JUndefined) => None
        case (JUndefined, _) => None
        case (x, y)          => Some(JBool(x == y))
      }
    }

    results.copoint mustEqual expected
  }

  def testYetAnotherSimpleEqual = {
    val array: JValue = json"""
      [{
        "value":{
          "value1":-1380814338912438254,
          "value2":-1380814338912438254
        },
        "key":[2.0,1.0]
      },
      {
        "value":{
          "value1":1
        },
        "key":[2.0,2.0]
      }]"""

    val data: Stream[JValue] = array.asArray.elements.toStream
    val sample               = SampleData(data)
    val table                = fromSample(sample)

    val results = toJson(table.transform {
      Equal(
        root.value.value1,
        root.value.value2
      )
    })

    val expected = data flatMap { jv =>
      ((jv \ "value" \ "value1"), (jv \ "value" \ "value2")) match {
        case (_, JUndefined) => None
        case (JUndefined, _) => None
        case (x, y)          => Some(JBool(x == y))
      }
    }

    results.copoint mustEqual expected
  }

  def testASimpleNonEqual = {
    val array: JValue = json"""
      [{
        "value":{
          "value1":-1380814338912438254,
          "value2":1380814338912438254
        },
        "key":[2.0,1.0]
      }]"""

    val data: Stream[JValue] = array.asArray.elements.toStream
    val sample               = SampleData(data)
    val table                = fromSample(sample)

    val results = toJson(table.transform {
      Equal(
        root.value.value1,
        root.value.value2
      )
    })

    val expected = data flatMap { jv =>
      ((jv \ "value" \ "value1"), (jv \ "value" \ "value2")) match {
        case (_, JUndefined) => None
        case (JUndefined, _) => None
        case (x, y)          => Some(JBool(x == y))
      }
    }

    results.copoint mustEqual expected
  }

  def testEqual(sample: SampleData) = {
    val table = fromSample(sample)
    val results = toJson(table.transform {
      Equal(
        root.value.value1,
        root.value.value2
      )
    })

    val expected = sample.data flatMap { jv =>
      ((jv \ "value" \ "value1"), (jv \ "value" \ "value2")) match {
        case (JUndefined, JUndefined) => None
        case (x, y)                   => Some(JBool(x == y))
      }
    }

    results.copoint must_== expected
  }

  def checkEqual = {
    def hasVal1Val2(jv: JValue): Boolean = (jv \? ".value.value1").nonEmpty && (jv \? ".value.value2").nonEmpty

    val genBase: Gen[SampleData] = sample(_ => Seq(JPath("value1") -> CLong, JPath("value2") -> CLong)).arbitrary
    implicit val gen: Arbitrary[SampleData] = Arbitrary {
      genBase map { sd =>
        SampleData(
          sd.data.zipWithIndex map {
            case (jv, i) if i % 2 == 0 =>
              if (hasVal1Val2(jv)) {
                jv.set(JPath(JPathField("value"), JPathField("value1")), jv(JPath(JPathField("value"), JPathField("value2"))))
              } else {
                jv
              }

            case (jv, i) if i % 5 == 0 =>
              if (hasVal1Val2(jv)) {
                jv.set(JPath(JPathField("value"), JPathField("value1")), JUndefined)
              } else {
                jv
              }

            case (jv, i) if i % 5 == 3 =>
              if (hasVal1Val2(jv)) {
                jv.set(JPath(JPathField("value"), JPathField("value2")), JUndefined)
              } else {
                jv
              }

            case (jv, _) => jv
          }
        )
      }
    }

    prop(testEqual _)
  }

  def testEqual1 = {
    val JArray(elements) = json"""[
      {
        "value":{
          "value1":-1503074360046022108,
          "value2":-1503074360046022108
        },
        "key":[1.0]
      },
      {
        "value":[[-1],[],["p",-3.875484961198970156E-18930]],
        "key":[2.0]
      },
      {
        "value":{
          "value1":4611686018427387903,
          "value2":4611686018427387903
        },
        "key":[3.0]
      }
    ]"""

    testEqual(SampleData(elements.toStream))
  }

  def checkEqualLiteral = {
    implicit val gen: Arbitrary[SampleData] = sample(_ => Seq(JPath("value1") -> CLong)) ^^ (sd =>
      SampleData(
        sd.data.zipWithIndex map {
          case (jv, i) if i % 2 == 0 =>
            if ((jv \? ".value.value1").nonEmpty) {
              jv.set(JPath(JPathField("value"), JPathField("value1")), JNum(0))
            } else {
              jv
            }

          case (jv, i) if i % 5 == 0 =>
            if ((jv \? ".value.value1").nonEmpty) {
              jv.set(JPath(JPathField("value"), JPathField("value1")), JUndefined)
            } else {
              jv
            }

          case (jv, _) => jv
        }
      )
    )

    prop { (sample: SampleData) =>
      val table    = fromSample(sample)
      val Trans    = EqualLiteral(root.value.value1, CLong(0), invert = false)
      val results  = toJson(table transform Trans)
      val expected = sample.data map (_ \ "value" \ "value1") filter (_.isDefined) map (x => JBool(x == JNum(0)))

      results.copoint must_=== expected
    }
  }
  def checkNotEqualLiteral = {
    val genBase: Gen[SampleData] = sample(_ => Seq(JPath("value1") -> CLong)).arbitrary
    implicit val gen: Arbitrary[SampleData] = Arbitrary {
      genBase map { sd =>
        SampleData(
          sd.data.zipWithIndex map {
            case (jv, i) if i % 2 == 0 =>
              if ((jv \? ".value.value1").nonEmpty) {
                jv.set(JPath(JPathField("value"), JPathField("value1")), JNum(0))
              } else {
                jv
              }

            case (jv, i) if i % 5 == 0 =>
              if ((jv \? ".value.value1").nonEmpty) {
                jv.set(JPath(JPathField("value"), JPathField("value1")), JUndefined)
              } else {
                jv
              }

            case (jv, _) => jv
          }
        )
      }
    }

    prop { (sample: SampleData) =>
      val table = fromSample(sample)
      val results = toJson(table.transform {
        EqualLiteral(root.value.value1, CLong(0), true)
      })

      val expected = sample.data flatMap { jv =>
        jv \ "value" \ "value1" match {
          case JUndefined => None
          case x          => Some(JBool(x != JNum(0)))
        }
      }

      results.copoint must_== expected
    }
  }

  def checkWrapObject = checkSpec(WrapObject(Leaf(Source), "foo"))(_ map (jv =>  jobject("foo" -> jv)))(defaultASD)

  def checkObjectConcatSelf = {
    implicit val gen = defaultASD
    checkSpec(InnerObjectConcat(root, root))(identity)
    checkSpec(OuterObjectConcat(root, root))(identity)
  }

  def testObjectConcatSingletonNonObject = {
    val table        = fromJson(Seq(JTrue))
    val resultsInner = toJsonSeq(table transform InnerObjectConcat(root))
    val resultsOuter = toJsonSeq(table transform OuterObjectConcat(root))

    resultsInner must beEmpty
    resultsOuter must beEmpty
  }

  def testObjectConcatTrivial = {
    val table        = fromJson(Seq(JTrue, jobject()))
    val resultsInner = toJsonSeq(table transform InnerObjectConcat(root))
    val resultsOuter = toJsonSeq(table transform OuterObjectConcat(root))

    resultsInner must_=== Seq(jobject())
    resultsOuter must_=== Seq(jobject())
  }

  private def testConcatEmptyObject(spec: TransSpec1) = {
    val data = jsonMany"""
      {"bar":{"ack":12},"foo":{}}
      {"bar":{"ack":12,"bak":13},"foo":{}}
      {"bar":{},"foo":{"ook":99}}
      {"bar":{"ack":100,"bak":101},"foo":{"ook":99}}
      {"bar":{},"foo":{"ick":100,"ook":99}}
      {"bar":{"ack":102},"foo":{"ick":100,"ook":99}}
      {"bar":{},"foo":{}}
      {"foo":{"ook":88}}
      {"foo":{}}
      {"bar":{}}
      {"bar":{"ook":77}}
      {"bar":{},"baz":24,"foo":{"ook":7}}
      {"bar":{"ack":9},"baz":18,"foo":{"ook":3}}
      {"bar":{"ack":0},"baz":18,"foo":{}}
    """.toStream

    val sample = SampleData(data)
    val table  = fromSample(sample)
    toJson(table transform spec).copoint
  }


  def testInnerObjectConcatEmptyObject = {
    val result = testConcatEmptyObject(InnerObjectConcat(root.foo, root.bar))
    val expected = jsonMany"""
      {"ack":12}
      {"ack":12,"bak":13}
      {"ook":99}
      {"ack":100,"bak":101,"ook":99}
      {"ick":100,"ook":99}
      {"ack":102,"ick":100,"ook":99}
      {}
      {"ook":7}
      {"ack":9,"ook":3}
      {"ack":0}
    """.toStream

    result must_=== expected
  }

  def testOuterObjectConcatEmptyObject = {
    val result = testConcatEmptyObject(OuterObjectConcat(root.foo, root.bar))
    val expected: Stream[JValue] = jsonMany"""
      {"ack":12}
      {"ack":12,"bak":13}
      {"ook":99}
      {"ack":100,"bak":101,"ook":99}
      {"ick":100,"ook":99}
      {"ack":102,"ick":100,"ook":99}
      {}
      {"ook":88}
      {}
      {}
      {"ook":77}
      {"ook":7}
      {"ack":9,"ook":3}
      {"ack":0}
    """.toStream

    result must_=== expected
  }

  def testInnerObjectConcatUndefined = {
    val data = jsonMany"""
      {"foo": {"baz": 4}, "bar": {"ack": 12}}
      {"foo": {"baz": 5}}
      {"bar": {"ack": 45}}
      {"foo": {"baz": 7}, "bar" : {"ack": 23}, "baz": {"foobar": 24}}
    """.toStream

    val table    = fromSample(SampleData(data))
    val spec     = InnerObjectConcat(root.foo, root.bar)
    val results  = toJson(table transform spec)
    val expected = jsonMany"""
      {"ack":12,"baz":4}
      {"ack":23,"baz":7}
    """.toStream

    results.copoint must_=== expected
  }

  def testOuterObjectConcatUndefined = {
    val data = jsonMany"""
      {"foo": {"baz": 4}, "bar": {"ack": 12}}
      {"foo": {"baz": 5}}
      {"bar": {"ack": 45}}
      {"foo": {"baz": 7}, "bar" : {"ack": 23}, "baz": {"foobar": 24}}
    """.toStream

    val table   = fromSample(SampleData(data))
    val spec    = OuterObjectConcat(root.foo, root.bar)
    val results = toJson(table transform spec)

    val expected = jsonMany"""
      {"ack":12,"baz":4}
      {"baz":5}
      {"ack":45}
      {"ack":23,"baz":7}
    """.toStream

    results.copoint must_=== expected
  }

  def testInnerObjectConcatLeftEmpty = {
    val JArray(elements) = json"""[
      {"foo": 4, "bar": 12},
      {"foo": 5},
      {"bar": 45},
      {"foo": 7, "bar" :23, "baz": 24}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val spec = InnerObjectConcat(root.foobar, WrapObject(root.bar, "ack"))

    val results = toJson(table.transform(spec))

    val expected: Stream[JValue] = Stream()

    results.copoint mustEqual expected
  }

  def testOuterObjectConcatLeftEmpty = {
    val data = jsonMany"""
      {"foo": 4, "bar": 12}
      {"foo": 5}
      {"bar": 45}
      {"foo": 7, "bar" :23, "baz": 24}
    """
    val expected = jsonMany"""
      { "ack": 12 }
      { "ack": 45 }
      { "ack": 23 }
    """

    val table = fromJson(data)
    val spec  = OuterObjectConcat(root.foobar, WrapObject(root.bar, "ack"))

    toJsonSeq(table transform spec) must_=== expected
  }

  def checkObjectConcat = {
    implicit val gen = sample(_ => Seq(JPath("value1") -> CLong, JPath("value2") -> CLong))
    prop { (sample: SampleData) =>
      val table = fromSample(sample)
      val resultsInner = toJson(table.transform {
        InnerObjectConcat(
          WrapObject(WrapObject(root.value.value1, "value1"), "value"),
          WrapObject(WrapObject(root.value.value2, "value2"), "value")
        )
      })

      val resultsOuter = toJson(table.transform {
        OuterObjectConcat(
          WrapObject(WrapObject(root.value.value1, "value1"), "value"),
          WrapObject(WrapObject(root.value.value2, "value2"), "value")
        )
      })

      def isOk(results: Need[Stream[JValue]]) =
        results.copoint must_== (sample.data flatMap {
          case JObject(fields) => {
            val back = JObject(fields filter { case (name, value) => name == "value" && value.isInstanceOf[JObject] })
            if (back \ "value" \ "value1" == JUndefined || back \ "value" \ "value2" == JUndefined)
              None
            else
              Some(back)
          }

          case _ => None
        })

      isOk(resultsInner)
      isOk(resultsOuter)
    }
  }

  def checkObjectConcatOverwrite = {
    implicit val gen = sample(_ => Seq(JPath("value1") -> CLong, JPath("value2") -> CLong))
    prop { (sample: SampleData) =>
      val table = fromSample(sample)
      val resultsInner = toJson(table.transform {
        InnerObjectConcat(
          WrapObject(root.value.value1, "value1"),
          WrapObject(root.value.value2, "value1")
        )
      })

      val resultsOuter = toJson(table.transform {
        OuterObjectConcat(
          WrapObject(root.value.value1, "value1"),
          WrapObject(root.value.value2, "value1")
        )
      })

      def isOk(results: Need[Stream[JValue]]) =
        results.copoint must_== (sample.data map { _ \ "value" } collect {
          case v if (v \ "value1") != JUndefined && (v \ "value2") != JUndefined =>
            JObject(JField("value1", v \ "value2") :: Nil)
        })

      isOk(resultsOuter)
      isOk(resultsInner)
    }
  }

  def testInnerObjectConcatJoinSemantics = {
    val data   = Stream(JObject(Map("a" -> JNum(42))))
    val sample = SampleData(data)
    val table  = fromSample(sample)

    val spec = InnerObjectConcat(
      Leaf(Source),
      WrapObject(
        Filter(root.a, ConstLiteral(CBoolean(false), Leaf(Source))), // undefined
        "b"))

    val results = toJson(table transform spec)

    results.copoint mustEqual Stream()
  }

  def checkArrayConcat = {
    implicit val gen = sample(_ => Seq(JPath("[0]") -> CLong, JPath("[1]") -> CLong))
    prop { (sample0: SampleData) =>
      /***
      important note:
      `sample` is excluding the cases when we have JArrays of size 1
      this is because then the concat array would return
      array.concat(undefined) = array
      which is incorrect but is what the code currently does
        */
      val sample = SampleData(sample0.data flatMap { jv =>
        (jv \ "value") match {
          case JArray(Seq(x)) => None
          case z              => Some(z)
        }
      })
      val table = fromSample(sample)
      val innerSpec = WrapObject(
        InnerArrayConcat(WrapArray(root value 0), WrapArray(root value 1)),
        "value"
      )
      val outerSpec = WrapObject(
        OuterArrayConcat(WrapArray(root value 0), WrapArray(root value 1)),
        "value"
      )
      def isOk(results: Need[Stream[JValue]]) = {
        val found = sample.data collect {
          case obj @ JObject(fields) =>
            obj \ "value" match {
              case JArray(inner) if inner.length >= 2 => Some(jobject("value" -> JArray(inner take 2)))
              case _                                  => None
            }
        }
        results.copoint must_=== found.flatten
      }
      isOk(toJson(table transform innerSpec))
      isOk(toJson(table transform outerSpec))
    }
  }

  def testInnerArrayConcatUndefined = {
    val elements = jsonMany"""
      {"foo": 4, "bar": 12}
      {"foo": 5}
      {"bar": 45}
      {"foo": 7, "bar" :23, "baz": 24}
    """

    val sample   = SampleData(elements.toStream)
    val table    = fromSample(sample)
    val spec     = InnerArrayConcat(WrapArray(root.foo), WrapArray(root.bar))
    val results  = toJson(table transform spec)
    val expected = Stream(JArray(JNum(4) :: JNum(12) :: Nil), JArray(JNum(7) :: JNum(23) :: Nil))

    results.copoint must_=== expected
  }

  def testOuterArrayConcatUndefined = {
    val data = jsonMany"""
      {"foo": 4, "bar": 12}
      {"foo": 5}
      {"bar": 45}
      {"foo": 7, "bar" :23, "baz": 24}
    """
    val expected = Seq(
      jarray(JNum(4), JNum(12)),
      jarray(JNum(5)),
      jarray(JUndefined, JNum(45)),
      jarray(JNum(7), JNum(23))
    )
    val table = fromJson(data)
    val spec  = OuterArrayConcat(WrapArray(root.foo), WrapArray(root.bar))

    toJsonSeq(table transform spec) mustEqual expected
  }

  def testInnerArrayConcatEmptyArray = {
    val JArray(elements) = json"""[
      {"foo": [], "bar": [12]},
      {"foo": [], "bar": [12, 13]},
      {"foo": [99], "bar": []},
      {"foo": [99], "bar": [100, 101]},
      {"foo": [99, 100], "bar": []},
      {"foo": [99, 100], "bar": [102]},
      {"foo": [], "bar": []},
      {"foo": [88]},
      {"foo": []},
      {"bar": []},
      {"bar": [77]},
      {"foo": [7], "bar": [], "baz": 24},
      {"foo": [3], "bar": [9], "baz": 18},
      {"foo": [], "bar": [0], "baz": 18}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val spec = InnerArrayConcat(root.foo, root.bar)

    val results = toJson(table.transform(spec))

    // note: slice size is 10
    // the first index of the rhs array is one after the max of the ones seen
    // in the current slice on the lhs
    val expected: Stream[JValue] = Stream(
      JArray(JUndefined :: JUndefined :: JNum(12) :: Nil),
      JArray(JUndefined :: JUndefined :: JNum(12) :: JNum(13) :: Nil),
      JArray(JNum(99) :: Nil),
      JArray(JNum(99) :: JUndefined :: JNum(100) :: JNum(101) :: Nil),
      JArray(JNum(99) :: JNum(100) :: Nil),
      JArray(JNum(99) :: JNum(100) :: JNum(102) :: Nil),
      jarray(),
      JArray(JNum(7) :: Nil),
      JArray(JNum(3) :: JNum(9) :: Nil),
      JArray(JUndefined :: JNum(0) :: Nil))

    results.copoint mustEqual expected
  }

  def testOuterArrayConcatEmptyArray = {
    val JArray(elements) = json"""[
      {"foo": [], "bar": [12]},
      {"foo": [], "bar": [12, 13]},
      {"foo": [99], "bar": []},
      {"foo": [99], "bar": [100, 101]},
      {"foo": [99, 100], "bar": []},
      {"foo": [99, 100], "bar": [102]},
      {"foo": [], "bar": []},
      {"foo": [88]},
      {"foo": []},
      {"bar": []},
      {"bar": [77]},
      {"foo": [7], "bar": [], "baz": 24},
      {"foo": [3], "bar": [9], "baz": 18},
      {"foo": [], "bar": [0], "baz": 18}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val spec = OuterArrayConcat(root.foo, root.bar)

    val results = toJson(table.transform(spec))

    // note: slice size is 10
    // the first index of the rhs array is one after the max of the ones seen
    // in the current slice on the lhs
    val expected: Stream[JValue] = Stream(
      JArray(JUndefined :: JUndefined :: JNum(12) :: Nil),
      JArray(JUndefined :: JUndefined :: JNum(12) :: JNum(13) :: Nil),
      JArray(JNum(99) :: Nil),
      JArray(JNum(99) :: JUndefined :: JNum(100) :: JNum(101) :: Nil),
      JArray(JNum(99) :: JNum(100) :: Nil),
      JArray(JNum(99) :: JNum(100) :: JNum(102) :: Nil),
      jarray(),
      JArray(JNum(88) :: Nil),
      jarray(),
      jarray(),
      JArray(JUndefined :: JNum(77) :: Nil),
      JArray(JNum(7) :: Nil),
      JArray(JNum(3) :: JNum(9) :: Nil),
      JArray(JUndefined :: JNum(0) :: Nil))

    results.copoint mustEqual expected
  }

  def testInnerArrayConcatLeftEmpty = {
    val JArray(elements) = json"""[
      {"foo": 4, "bar": 12},
      {"foo": 5},
      {"bar": 45},
      {"foo": 7, "bar" :23, "baz": 24}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val spec = InnerArrayConcat(root.foobar, WrapArray(root.bar))

    val results = toJson(table.transform(spec))

    val expected: Stream[JValue] = Stream()

    results.copoint mustEqual expected
  }

  def testOuterArrayConcatLeftEmpty = {
    val JArray(elements) = json"""[
      {"foo": 4, "bar": 12},
      {"foo": 5},
      {"bar": 45},
      {"foo": 7, "bar" :23, "baz": 24}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val spec = OuterArrayConcat(root.foobar, WrapArray(root.bar))

    val results = toJson(table.transform(spec))

    val expected: Stream[JValue] = Stream(JArray(JNum(12) :: Nil), JArray(JNum(45) :: Nil), JArray(JNum(23) :: Nil))

    results.copoint mustEqual expected
  }

  def checkObjectDeleteWithoutRemovingArray = checkSpecData(
    spec = ObjectDelete(Leaf(Source), Set(CPathField("foo"))),
    data = jsonMany"""
      {"foo": 4, "bar": 12}
      {"foo": 5}
      {"bar": 45}
      {}
      {"foo": 7, "bar" :23, "baz": 24}
    """,
    expected = jsonMany"""
      {"bar": 12}
      {}
      {"bar": 45}
      {}
      {"bar" :23, "baz": 24}
    """
  )

  def checkObjectDelete = {
    implicit val gen = sample(objectSchema(_, 3))

    def randomDeletionMask(schema: CValueGenerators.JSchema): Option[JPathField] = {
      Random.shuffle(schema).headOption.map({ case (JPath((x @ JPathField(_)) :: _), _) => x })
    }

    prop { (sample: SampleData) =>
      val toDelete = sample.schema.flatMap({ case (_, schema) => randomDeletionMask(schema) })

      toDelete.isDefined ==> {
        val table       = fromSample(sample)
        val Some(field) = toDelete
        val spec        = root.value delete CPathField(field.name)
        val result      = toJson(table transform spec)
        val expected    = sample.data flatMap (jv => jv \ "value" delete JPath(field))

        result.copoint must_=== expected
      }
    }
  }

  def testIsTypeNumeric = checkSpecData(
    spec     = root isType JNumberT,
    expected = Seq(JFalse, JTrue, JFalse, JFalse, JFalse, JTrue, JTrue, JFalse, JFalse, JFalse, JTrue),
    data     = jsonMany"""
      {"key":[1], "value": "value1"}
      45
      true
      {"value":"foobaz"}
      [234]
      233.4
      29292.3
      null
      [{"bar": 12}]
      {"baz": 34.3}
      23
    """
  )

  def testIsTypeUnionTrivial = checkSpecData(
    spec     = root isType JUnionT(JNumberT, JNullT),
    expected = Seq(JFalse, JTrue, JFalse, JFalse, JFalse, JTrue, JTrue, JTrue, JFalse, JFalse, JTrue),
    data     = jsonMany"""
      {"key":[1], "value": "value1"}
      45
      true
      {"value":"foobaz"}
      [234]
      233.4
      29292.3
      null
      [{"bar": 12}]
      {"baz": 34.3}
      23
    """
  )

  def testIsTypeUnion = {
    val jtpe = JType.Object(
      "value" -> JType.Object(
        "foo" -> JUnionT(JNumberT, JTextT),
        "bar" -> JObjectUnfixedT
      ),
      "key" -> JArrayUnfixedT
    )
    checkSpecData(
      spec     = root isType jtpe,
      expected = Seq(JFalse, JTrue, JTrue, JTrue, JFalse, JFalse, JTrue, JFalse, JTrue, JFalse, JFalse),
      data     = jsonMany"""
        {"key":[1], "value": 23}
        {"key":[1, "bax"], "value": {"foo":4, "bar":{}}}
        {"key":[null, "bax", 4], "value": {"foo":4.4, "bar":{"a": false}}}
        {"key":[], "value": {"foo":34, "bar":{"a": null}}}
        {"key":[2], "value": {"foo": "dd"}}
        {"key":[3]}
        {"key":[2], "value": {"foo": -1.1, "bar": {"a": 4, "b": 5}}}
        {"key":[2], "value": {"foo": "dd", "bar": [{"a":6}]}}
        {"key":[44], "value": {"foo": "x", "bar": {"a": 4, "b": 5}}}
        {"value":"foobaz"}
        {}
      """
    )
  }

  def testIsTypeUnfixed = {
    val jtpe = JType.Object(
      "value" -> JType.Object("foo" -> JNumberT, "bar" -> JObjectUnfixedT),
      "key"   -> JArrayUnfixedT
    )
    checkSpecData(
      spec     = root isType jtpe,
      expected = Seq(JFalse, JTrue, JTrue, JTrue, JFalse, JFalse, JTrue, JFalse, JFalse, JFalse, JFalse),
      data     = jsonMany"""
        {"key":[1], "value": 23}
        {"key":[1, "bax"], "value": {"foo":4, "bar":{}}}
        {"key":[null, "bax", 4], "value": {"foo":4.4, "bar":{"a": false}}}
        {"key":[], "value": {"foo":34, "bar":{"a": null}}}
        {"key":[2], "value": {"foo": "dd"}}
        {"key":[3]}
        {"key":[2], "value": {"foo": -1.1, "bar": {"a": 4, "b": 5}}}
        {"key":[2], "value": {"foo": "dd", "bar": [{"a":6}]}}
        {"key":[44], "value": {"foo": "x", "bar": {"a": 4, "b": 5}}}
        {"value":"foobaz"}
        {}
      """
    )
  }

  def testIsTypeObject = {
    val jtpe = JType.Object("value" -> JNumberT, "key" -> JArrayUnfixedT)
    checkSpecData(
      spec     = root isType jtpe,
      expected = Seq(JTrue, JTrue, JFalse, JFalse, JFalse, JFalse, JFalse, JTrue, JTrue, JFalse),
      data     = jsonMany"""
        {"key":[1], "value": 23}
        {"key":[1, "bax"], "value": 24}
        {"key":[2], "value": "foo"}
        15
        {"key":[3]}
        {"value":"foobaz"}
        {"notkey":[3]}
        {"key":[3], "value": 18, "baz": true}
        {"key":["foo"], "value": 18.6, "baz": true}
        {"key":[3, 5], "value": [34], "baz": 33}
      """
    )
  }

  def testIsTypeObjectEmpty = {
    val JArray(elements) = json"""[
      [],
      1,
      {},
      null,
      {"a": 1},
      [6.2, -6],
      {"b": [9]}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val jtpe = JObjectFixedT(Map())
    val results = toJson(table.transform {
      IsType(Leaf(Source), jtpe)
    })

    val expected = Stream(JFalse, JFalse, JTrue, JFalse, JFalse, JFalse, JFalse)

    results.copoint must_== expected
  }

  def testIsTypeArrayEmpty = {
    val JArray(elements) = json"""[
      [],
      1,
      {},
      null,
      {"a": 1},
      [6.2, -6],
      {"b": [9]}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val jtpe = JArrayFixedT(Map())
    val results = toJson(table.transform {
      IsType(Leaf(Source), jtpe)
    })

    val expected = Stream(JTrue, JFalse, JFalse, JFalse, JFalse, JFalse, JFalse)

    results.copoint must_== expected
  }

  def testIsTypeObjectUnfixed = {
    val JArray(elements) = json"""[
      [],
      1,
      {},
      null,
      {"a": 1},
      [6.2, -6],
      {"b": [9]}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val jtpe = JObjectUnfixedT
    val results = toJson(table.transform {
      IsType(Leaf(Source), jtpe)
    })

    val expected = Stream(JFalse, JFalse, JTrue, JFalse, JTrue, JFalse, JTrue)

    results.copoint must_== expected
  }

  def testIsTypeArrayUnfixed = {
    val JArray(elements) = json"""[
      [],
      1,
      {},
      null,
      [false, 3.2, "a"],
      [6.2, -6],
      {"b": [9]}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val jtpe = JArrayUnfixedT
    val results = toJson(table.transform {
      IsType(Leaf(Source), jtpe)
    })

    val expected = Stream(JTrue, JFalse, JFalse, JFalse, JTrue, JTrue, JFalse)

    results.copoint must_== expected
  }

  def testIsTypeTrivial = {
    val JArray(elements) = json"""[
      {"key":[2,1,1],"value":[]},
      {"key":[2,2,2],"value":{"dx":[8.342062585288287E+307]}}]
    """

    val sample = SampleData(elements.toStream, Some((3, Seq((NoJPath, CEmptyArray)))))

    testIsType(sample)
  }

  def testIsType(sample: SampleData) = {
    val (_, schema) = sample.schema.getOrElse(0 -> List())
    val cschema     = schema map { case (jpath, ctype) => ColumnRef(CPath(jpath), ctype) }

    // using a generator with heterogeneous data, we're just going to produce
    // the jtype that chooses all of the elements of the non-random data.
    val jtpe = JObjectFixedT(
      Map(
        "value" -> Schema.mkType(cschema).getOrElse(abort("Could not generate JType from schema " + cschema)),
        "key"   -> JArrayUnfixedT
      ))

    val table                           = fromSample(sample)
    val results                         = toJson(table.transform(IsType(Leaf(Source), jtpe)))
    val schemasSeq: Stream[Seq[JValue]] = toJson(table).copoint.map(Seq(_))
    val schemas0                        = schemasSeq map inferSchema
    val schemas                         = schemas0 map { _ map { case (jpath, ctype) => (CPath(jpath), ctype) } }
    val expected                        = schemas map (schema => JBool(Schema.subsumes(schema, jtpe)))

    results.copoint mustEqual expected
  }

  def checkIsType = {
    implicit val gen = defaultASD
    prop(testIsType _).set(minTestsOk = 10000)
  }

  def checkTypedTrivial = {
    implicit val gen = sample(_ => Seq(JPath("value1") -> CLong, JPath("value2") -> CBoolean, JPath("value3") -> CLong))
    prop { (sample: SampleData) =>
      val table = fromSample(sample)

      val results = toJson(table.transform {
        Typed(
          Leaf(Source),
          JObjectFixedT(
            Map("value"                  ->
              JObjectFixedT(Map("value1" -> JNumberT, "value3" -> JNumberT)))))
      })

      val expected = sample.data flatMap { jv =>
        val value1 = jv \ "value" \ "value1"
        val value3 = jv \ "value" \ "value3"

        if (value1.isInstanceOf[JNum] && value3.isInstanceOf[JNum]) {
          Some(
            JObject(
              JField(
                "value",
                JObject(JField("value1", jv \ "value" \ "value1") ::
                  JField("value3", jv \ "value" \ "value3") ::
                    Nil)) ::
                Nil))
        } else {
          None
        }
      }

      results.copoint must_== expected
    }
  }

  def testTyped(sample: SampleData) = {
    val (_, schema) = sample.schema.getOrElse(0 -> List())
    val cschema     = schema map { case (jpath, ctype) => ColumnRef(CPath(jpath), ctype) }

    // using a generator with heterogeneous data, we're just going to produce
    // the jtype that chooses all of the elements of the non-random data.
    val jtpe = JObjectFixedT(
      Map(
        "value" -> Schema.mkType(cschema).getOrElse(abort("Could not generate JType from schema " + cschema)),
        "key"   -> JArrayUnfixedT
      ))

    val table    = fromSample(sample)
    val results  = toJson(table.transform(Typed(Leaf(Source), jtpe)))
    val included = schema.groupBy(_._1).mapValues(_.map(_._2).toSet)
    val expected = expectedResult(sample.data, included)

    results.copoint must_== expected
  }

  def checkTyped = {
    implicit val gen = defaultASD
    prop(testTyped _)
  }

  def testTypedAtSliceBoundary = {
    val JArray(data) = json"""[
      { "value":{ "n":{ } }, "key":[1,1,1] },
      { "value":{ "lvf":2123699568254154891, "vbeu":false, "dAc":4611686018427387903 }, "key":[1,1,3] },
      { "value":{ "lvf":1, "vbeu":true, "dAc":0 }, "key":[2,1,1] },
      { "value":{ "lvf":1, "vbeu":true, "dAc":-1E-28506 }, "key":[2,2,1] },
      { "value":{ "n":{ } }, "key":[2,2,2] },
      { "value":{ "n":{ } }, "key":[2,3,2] },
      { "value":{ "n":{ } }, "key":[2,4,4] },
      { "value":{ "n":{ } }, "key":[3,1,3] },
      { "value":{ "n":{ } }, "key":[3,2,2] },
      { "value":{ "n":{ } }, "key":[4,3,1] },
      { "value":{ "lvf":-1, "vbeu":true, "dAc":0 }, "key":[4,3,4] }
    ]"""

    val sample = SampleData(data.toStream, Some((3, List((JPath(".n"), CEmptyObject)))))

    testTyped(sample)
  }

  def testTypedHeterogeneous = {
    val JArray(elements) = json"""[
      {"key":[1], "value":"value1"},
      {"key":[2], "value":23}
    ]"""

    val sample  = SampleData(elements.toStream)
    val table   = fromSample(sample)
    val jtpe    = JObjectFixedT(Map("value" -> JTextT, "key" -> JArrayUnfixedT))
    val results = toJson(table.transform(Typed(Leaf(Source), jtpe)))

    val JArray(expected) = json"""[
      {"key":[1], "value":"value1"},
      {"key":[2]}
    ]"""

    results.copoint must_== expected.toStream
  }

  def testTypedObject = {
    val JArray(elements) = json"""[
      {"key":[1, 3], "value": {"foo": 23}},
      {"key":[2, 4], "value": {}}
    ]"""

    val sample = SampleData(elements.toStream)
    val table  = fromSample(sample)

    val results = toJson(table.transform {
      Typed(Leaf(Source), JObjectFixedT(Map("value" -> JObjectFixedT(Map("foo" -> JNumberT)), "key" -> JArrayUnfixedT)))
    })

    val JArray(expected) = json"""[
      {"key":[1, 3], "value": {"foo": 23}},
      {"key":[2, 4]}
    ]"""

    results.copoint must_== expected.toStream
  }

  def testTypedObject2 = {
    val data: Stream[JValue] =
      Stream(JObject(List(JField("value", JObject(List(JField("foo", JBool(true)), JField("bar", JNum(77))))), JField("key", JArray(List(JNum(1)))))))
    val sample = SampleData(data)
    val table  = fromSample(sample)

    val results = toJson(table.transform {
      Typed(Leaf(Source), JObjectFixedT(Map("value" -> JObjectFixedT(Map("bar" -> JNumberT)), "key" -> JArrayUnfixedT)))
    })

    val expected = Stream(JObject(List(JField("value", JObject(List(JField("bar", JNum(77))))), JField("key", JArray(List(JNum(1)))))))
    results.copoint must_== expected
  }

  def testTypedObjectUnfixed = {
    val data: Stream[JValue] =
      Stream(JObject(List(JField("value", JArray(List(JNum(2), JBool(true)))))), JObject(List(JField("value", JObject(List())))))
    val sample = SampleData(data)
    val table  = fromSample(sample)

    val results = toJson(table.transform {
      Typed(Leaf(Source), JObjectUnfixedT)
    })

    val resultStream = results.copoint
    resultStream must_== data
  }

  def testTypedArray = {
    val JArray(data) = json"""[
      {"key": [1, 2], "value": [2, true] },
      {"key": [3, 4], "value": {} }
    ]"""
    val sample       = SampleData(data.toStream)
    val table        = fromSample(sample)

    val results = toJson(table.transform {
      Typed(Leaf(Source), JObjectFixedT(Map("value" -> JArrayFixedT(Map(0 -> JNumberT, 1 -> JBooleanT)), "key" -> JArrayUnfixedT)))
    })

    val JArray(expected) = json"""[
      {"key": [1, 2], "value": [2, true] },
      {"key": [3, 4] }
    ]"""

    results.copoint must_== expected.toStream
  }

  def testTypedArray2 = {
    val JArray(data) = json"""[
      {"key": [1], "value": [2, true] }
    ]"""

    val sample = SampleData(data.toStream)
    val table  = fromSample(sample)
    val jtpe   = JObjectFixedT(Map("value" -> JArrayFixedT(Map(1 -> JBooleanT)), "key" -> JArrayUnfixedT))

    val results = toJson(table.transform {
      Typed(Leaf(Source), jtpe)
    })

    val expected = Stream(
      JObject(
        JField("key", JArray(JNum(1) :: Nil)) ::
          JField("value", JArray(JUndefined :: JBool(true) :: Nil)) ::
            Nil))

    results.copoint must_== expected
  }

  def testTypedArray3 = {
    val data: Stream[JValue] =
      Stream(
        JObject(List(JField("value", JArray(List(JArray(List()), JNum(23), JNull))), JField("key", JArray(List(JNum(1)))))),
        JObject(List(JField("value", JArray(List(JArray(List()), JArray(List()), JNull))), JField("key", JArray(List(JNum(2)))))))
    val sample = SampleData(data)
    val table  = fromSample(sample)

    val jtpe = JObjectFixedT(Map("value" -> JArrayFixedT(Map(0 -> JArrayFixedT(Map()), 1 -> JArrayFixedT(Map()), 2 -> JNullT)), "key" -> JArrayUnfixedT))

    val results = toJson(table.transform {
      Typed(Leaf(Source), jtpe)
    })

    val included: Map[JPath, Set[CType]] =
      Map(JPath(List(JPathIndex(0))) -> Set(CEmptyArray), JPath(List(JPathIndex(1))) -> Set(CEmptyArray), JPath(List(JPathIndex(2))) -> Set(CNull))

    results.copoint must_== expectedResult(data, included)
  }

  def testTypedArray4 = {
    val data: Stream[JValue] =
      Stream(
        JObject(List(JField("value", JArray(List(JNum(2.4), JNum(12), JBool(true), JArray(List())))), JField("key", JArray(List(JNum(1)))))),
        JObject(List(JField("value", JArray(List(JNum(3.5), JNull, JBool(false)))), JField("key", JArray(List(JNum(2)))))))

    val sample = SampleData(data)
    val table  = fromSample(sample)
    val jtpe = JObjectFixedT(
      Map("value" -> JArrayFixedT(Map(0 -> JNumberT, 1 -> JNumberT, 2 -> JBooleanT, 3 -> JArrayFixedT(Map()))), "key" -> JArrayUnfixedT))
    val results = toJson(table.transform(Typed(Leaf(Source), jtpe)))

    val included: Map[JPath, Set[CType]] = Map(
      JPath(List(JPathIndex(0))) -> Set(CNum),
      JPath(List(JPathIndex(1))) -> Set(CNum),
      JPath(List(JPathIndex(2))) -> Set(CBoolean),
      JPath(List(JPathIndex(3))) -> Set(CEmptyArray))

    results.copoint must_== expectedResult(data, included)
  }

  def testTypedNumber = {
    val JArray(data) = json"""[
      {"key": [1, 2], "value": 23 },
      {"key": [3, 4], "value": "foo" }
    ]"""

    val sample = SampleData(data.toStream)
    val table  = fromSample(sample)

    val results = toJson(table.transform {
      Typed(Leaf(Source), JObjectFixedT(Map("value" -> JNumberT, "key" -> JArrayUnfixedT)))
    })

    val JArray(expected) = json"""[
      {"key": [1, 2], "value": 23 },
      {"key": [3, 4] }
    ]"""

    results.copoint must_== expected.toStream
  }

  def testTypedNumber2 = {
    val data: Stream[JValue] =
      Stream(
        JObject(List(JField("value", JNum(23)), JField("key", JArray(List(JNum(1), JNum(3)))))),
        JObject(List(JField("value", JNum(12.5)), JField("key", JArray(List(JNum(2), JNum(4)))))))
    val sample = SampleData(data)
    val table  = fromSample(sample)

    val results = toJson(table.transform {
      Typed(Leaf(Source), JObjectFixedT(Map("value" -> JNumberT, "key" -> JArrayUnfixedT)))
    })

    val expected = data

    results.copoint must_== expected
  }

  def testTypedEmpty = {
    val JArray(data) = json"""[ {"key":[1], "value":{"foo":[]}} ]"""
    val sample       = SampleData(data.toStream)
    val table        = fromSample(sample)

    val results = toJson(table.transform {
      Typed(Leaf(Source), JObjectFixedT(Map("value" -> JArrayFixedT(Map()), "key" -> JArrayUnfixedT)))
    })

    val JArray(expected) = json"""[ {"key":[1] } ]"""

    results.copoint must_== expected.toStream
  }

  def testTrivialScan = {
    val data = JObject(JField("value", JNum(BigDecimal("2705009941739170689"))) :: JField("key", JArray(JNum(1) :: Nil)) :: Nil) #::
        JObject(JField("value", JString("")) :: JField("key", JArray(JNum(2) :: Nil)) :: Nil) #::
          Stream.empty

    val sample = SampleData(data)
    val table  = fromSample(sample)
    val results = toJson(table.transform {
      Scan(root.value, lookupScanner(Nil, "sum"))
    })

    val (_, expected) = sample.data.foldLeft((BigDecimal(0), Vector.empty[JValue])) {
      case ((a, s), jv) => {
        (jv \ "value") match {
          case JNum(i) => (a + i, s :+ JNum(a + i))
          case _       => (a, s)
        }
      }
    }

    results.copoint must_== expected.toStream
  }

  def testHetScan = {
    val data = JObject(JField("value", JNum(12)) :: JField("key", JArray(JNum(1) :: Nil)) :: Nil) #::
        JObject(JField("value", JNum(10)) :: JField("key", JArray(JNum(2) :: Nil)) :: Nil) #::
          JObject(JField("value", JArray(JNum(13) :: Nil)) :: JField("key", JArray(JNum(3) :: Nil)) :: Nil) #::
            Stream.empty

    val sample = SampleData(data)
    val table  = fromSample(sample)
    val results = toJson(table.transform {
      Scan(root.value, lookupScanner(Nil, "sum"))
    })

    val (_, expected) = sample.data.foldLeft((BigDecimal(0), Vector.empty[JValue])) {
      case ((a, s), jv) => {
        (jv \ "value") match {
          case JNum(i) => (a + i, s :+ JNum(a + i))
          case _       => (a, s)
        }
      }
    }

    results.copoint must_== expected.toStream
  }

  def checkScan = {
    implicit val gen = sample(_ => Seq(NoJPath -> CLong))
    prop { (sample: SampleData) =>
      val table = fromSample(sample)
      val results = toJson(table.transform {
        Scan(root.value, lookupScanner(Nil, "sum"))
      })

      val (_, expected) = sample.data.foldLeft((BigDecimal(0), Vector.empty[JValue])) {
        case ((a, s), jv) => {
          (jv \ "value") match {
            case JNum(i) => (a + i, s :+ JNum(a + i))
            case _       => (a, s)
          }
        }
      }

      results.copoint must_== expected.toStream
    }
  }

  def testDerefObjectDynamic = {
    val data = JObject(JField("foo", JNum(1)) :: JField("ref", JString("foo")) :: Nil) #::
        JObject(JField("bar", JNum(2)) :: JField("ref", JString("bar")) :: Nil) #::
          JObject(JField("baz", JNum(3)) :: JField("ref", JString("baz")) :: Nil) #:: Stream.empty[JValue]

    val table    = fromSample(SampleData(data))
    val results  = toJson(table transform DerefObjectDynamic(Leaf(Source), root.ref))
    val expected = JNum(1) #:: JNum(2) #:: JNum(3) #:: Stream.empty[JValue]

    results.copoint must_== expected
  }

  def checkArraySwap = {
    implicit val gen = sample(arraySchema(_, 3))
    prop { (sample0: SampleData) =>
      /***
      important note:
      `sample` is excluding the cases when we have JArrays of sizes 1 and 2
      this is because then the array swap would go out of bounds of the index
      and insert an undefined in to the array
      this will never happen in the real system
      so the test ignores this case
        */
      val sample = SampleData(sample0.data flatMap { jv =>
        (jv \ "value") match {
          case JArray(Seq(x))    => None
          case JArray(Seq(x, y)) => None
          case z                 => Some(z)
        }
      })
      val table = fromSample(sample)
      val results = toJson(table.transform {
        ArraySwap(root.value, 2)
      })

      val expected = sample.data flatMap { jv =>
        (jv \ "value") match {
          case JArray(x +: y +: z +: xs) => Some(JArray(z +: y +: x +: xs))
          case _                         => None
        }
      }

      results.copoint must_== expected
    }
  }

  def checkConst = {
    implicit val gen = undefineRowsForColumn(
      sample(_ => Seq(JPath("field") -> CLong)),
      JPath("value") \ "field"
    )

    prop { (sample: SampleData) =>
      val table   = fromSample(sample)
      val results = toJson(table transform ConstLiteral(CString("foo"), root.value.field))

      val expected = sample.data flatMap {
        case jv if jv \ "value" \ "field" == JUndefined => None
        case _                                          => Some(JString("foo"))
      }

      results.copoint must_== expected
    }
  }

  def checkCond = {
    implicit val gen = sample(_ => Gen.const(Seq(NoJPath -> CLong)))
    prop { (sample: SampleData) =>
      val table = fromSample(sample)
      val results = toJson(table transform {
        Cond(
          Map1(root.value, lookupF2(Nil, "mod").applyr(CLong(2)) andThen lookupF2(Nil, "eq").applyr(CLong(0))),
          root.value,
          ConstLiteral(CBoolean(false), Leaf(Source)))
      })

      val expected = sample.data flatMap { jv =>
        (jv \ "value") match {
          case jv @ JNum(x) => Some(if (x % 2 == 0) jv else JBool(false))
          case _            => None
        }
      }

      results.copoint must_== expected
    }.set(minTestsOk = 200)
  }

  def expectedResult(data: Stream[JValue], included: Map[JPath, Set[CType]]): Stream[JValue] = {
    data map { jv =>
      val filtered = jv.flattenWithPath filter {
        case (JPath(JPathField("value") :: tail), leaf) =>
          included get JPath(tail) exists { ctpes =>
            // if an object or array is nonempty, then leaf is a nonempty object and
            // consequently can't conform to any leaf type.
            leaf match {
              case JBool(_)    => ctpes.contains(CBoolean)
              case JString(_)  => ctpes.contains(CString)
              case JNum(_)     => ctpes.contains(CLong) || ctpes.contains(CDouble) || ctpes.contains(CNum)
              case JNull       => ctpes.contains(CNull)
              case JObject(xs) => xs.isEmpty && ctpes.contains(CEmptyObject)
              case JArray(xs)  => xs.isEmpty && ctpes.contains(CEmptyArray)
            }
          }

        case (JPath(JPathField("key") :: _), _) => true
        case _                                  => abort("Unexpected JValue schema for " + jv)
      }

      unflatten(filtered)
    }
  }
}
