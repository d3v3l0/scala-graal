package japgolly.scalagraal

import utest._
import TestUtil._

object ExprTest extends TestSuite {

  val paramTypes = Vector[Int => ExprParam[Int]](
    i => ExprParam.SourceConst(i.toString),
    _ => ExprParam.SourceFn(_.toString),
    _ => ExprParam.ValueFn(identity),
    _ => ExprParam.CtxValueFn(i => _.eval(graalLanguage.name, i.toString)))

  trait X

  override def tests = Tests {

    'args {

      "1" - {
        val a = 666

        for {
          pa <- paramTypes.map(_(a))
        } {
          val fn = Expr.compile1(identity)(_.asInt)(graalLanguage, pa)
          val expr = fn(a)
          val result = sync.eval(expr)
          assertEvalResult(result, a)
        }
      }

      "4" - {
        val mkExpr: (String, String, String, String) => String =
          (a, b, c, d) => s"1000*$a + 100*$b + 10*$c + $d"

        val (a, b, c, d) = (5, 4, 3, 2)
        val expect = 5432

        for {
          pa <- paramTypes.map(_(a))
          pb <- paramTypes.map(_(b))
          pc <- paramTypes.map(_(c))
          pd <- paramTypes.map(_(d))
        } {
          val fn = Expr.compile4(mkExpr)(_.asInt)(graalLanguage, pa, pb, pc, pd)
          val expr = fn(a, b, c, d)
          val result = sync.eval(expr)
          assertEvalResult(result, expect)
        }
      }

      "any" - {
        compileError(""" Expr.compile1(a => a)(_.asInt) """)
        ()
      }

      "X" - {
        compileError(""" Expr.compile1[X](a => a)(_.asInt) """)
        ()
      }
    }

    'results {
      'option {
        val a = Option.empty[Int]
        val b = Some(456)
        val expr = Expr.apply2((a, b) => s"($a == null) ? $b : $a", a, b).asOption(_.asInt)
        assertEvalResult(sync.eval(expr), b)
      }
    }

  }
}