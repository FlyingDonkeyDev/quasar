/*
 * Copyright 2014–2016 SlamData Inc.
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
 */

package ygg.macros

import quasar.Predef._
import scala.collection.{ mutable => scm }
import scala.reflect.macros.blackbox._
import jawn._
import java.util.UUID
import scala.Any
import scalaz.{ Tree => _, _ }, Scalaz._

class JawnFacadeMacros(val c: Context) {
  import c.universe._

  def singleImpl[A: c.WeakTypeTag](args: c.Expr[Any]*): Tree = JsonMacroSingle[A](args: _*)
  def manyImpl[A: c.WeakTypeTag](args: c.Expr[Any]*): Tree   = JsonMacroMany[A](args: _*)

  type MacroFacade = Facade[Tree]
  type JMap        = Map[UUID, Tree]

  def maybeUuid(k: String): Option[UUID] = scala.util.Try(UUID fromString k).toOption

  class TreeFacade(prefix: Tree, keyMap: JMap, valueMap: JMap) extends Facade[Tree] {
    def keyOrLiteral(k: String): Tree   = maybeUuid(k) flatMap keyMap.get getOrElse q"$k"
    def valueOrLiteral(x: String): Tree = maybeUuid(x) flatMap valueMap.get getOrElse jstring(x)

    def jtrue(): Tree                 = q"$prefix.jtrue"
    def jfalse(): Tree                = q"$prefix.jfalse"
    def jnull: Tree                   = q"$prefix.jnull"
    def jnum(s: String): Tree         = q"$prefix.jnum($s)"
    def jint(s: String): Tree         = q"$prefix.jint($s)"
    def jstring(s: String): Tree      = q"$prefix.jstring($s)"

    def singleContext(): FContext[Tree] = new FContext[Tree] {
      var value: Tree = _

      def add(v: Tree): Unit   = value = v
      def add(s: String): Unit = add(valueOrLiteral(s))
      def finish: Tree         = value
      def isObj: Boolean       = false
    }
    def arrayContext(): FContext[Tree] = new FContext[Tree] {
      val buf = List.newBuilder[Tree]

      def add(v: Tree): Unit   = discard(buf += v)
      def add(s: String): Unit = add(valueOrLiteral(s))
      def isObj: Boolean       = false
      def finish: Tree         = q"""{
        val fcontext = $prefix.arrayContext()
        ${buf.result} foreach (fcontext add _)
        fcontext.finish
      }"""
    }
    def objectContext(): FContext[Tree] = new FContext[Tree] {
      var key: String = null
      val buf         = List.newBuilder[(Tree, Tree)]

      private def pair(l: Tree, r: Tree): (Tree, Tree) = l -> r
      private def clearKey[A](body: A): Unit           = discard(key = null)

      def add(arg: String): Unit = key match {
        case null => key = arg
        case _    => clearKey(buf += (keyOrLiteral(key) -> valueOrLiteral(arg)))
      }
      def add(arg: Tree): Unit = clearKey(buf += (keyOrLiteral(key) -> arg))
      def isObj: Boolean       = true
      def finish: Tree         = q"""{
        val fcontext = $prefix.objectContext()
        for ((k, v) <- ${buf.result}) {
          fcontext add k
          fcontext add v
        }
        fcontext.finish
      }"""
    }
  }

  private def fail(msg: String): Nothing  = c.abort(c.enclosingPosition, msg)
  private def fail(t: Throwable): Nothing = fail("Exception during json interpolation: " + t.getMessage)

  object Args {
    def extract(parts: Seq[Tree]): Seq[String] = parts map {
      case Literal(Constant(part: String)) => part
      case _                               => fail("A StringContext part for the json interpolator is not a string")
    }

    def unapply(t: Tree) = t match {
      case Apply(Apply(_, Apply(_, parts) :: Nil), implicitArg :: Nil) => Some((extract(parts), implicitArg))
      case Apply(qual, Apply(_, parts) :: Nil)                         => Some((extract(parts), q"$qual.facade"))
      case _                                                           => None
    }
  }

  abstract class JsonMacroBase[M[_]] {
    def parse[A: c.WeakTypeTag](json: String, facade: MacroFacade): scala.util.Try[Tree]

    def apply[A: c.WeakTypeTag](args: c.Expr[Any]*): Tree = c.prefix.tree match {
      case Args(stringParts, facade) =>
        val A: Type = weakTypeOf[A]
        var uuids   = Vector[UUID]()
        val keys    = scm.Map[UUID, Tree]()
        val values  = scm.Map[UUID, Tree]()

        args foreach { arg =>
          val tpe      = c.typecheck(arg.tree).tpe
          val uuid     = UUID.randomUUID
          uuids        = uuids :+ uuid
          values(uuid) = q"quasar.JEncoder.lift[$tpe, $A]($arg)"

          if (tpe <:< typeOf[String])
            keys(uuid) = q"$arg"
        }

        if (stringParts.size != uuids.size + 1)
          fail("Invalid arguments to json interpolator")

        val buf = new java.lang.StringBuilder
        for ((part, uuid) <- stringParts zip uuids) {
          buf append part
          buf append '"'
          buf append uuid.toString
          buf append '"'
        }
        buf append stringParts.last

        parse(buf.toString, new TreeFacade(facade, keys.toMap, values.toMap)).fold(fail, t => t)

      case tree =>
        fail("Unexpected tree shape for json interpolation macro: " + tree.getClass + "\n" + showRaw(tree))
    }
  }

  object JsonMacroSingle extends JsonMacroBase[Id.Id] {
    def parse[A: c.WeakTypeTag](json: String, facade: MacroFacade) =
      JParser.parse(json)(facade)
  }
  object JsonMacroMany extends JsonMacroBase[Vector] {
    def parse[A: c.WeakTypeTag](json: String, facade: MacroFacade) =
      JParser.parseMany(json + "\n")(facade) map (x => q"${x.toVector}")
  }
}