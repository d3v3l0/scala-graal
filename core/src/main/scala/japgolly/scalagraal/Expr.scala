package japgolly.scalagraal

import java.time.Duration
import org.graalvm.polyglot._
import scala.collection.generic.CanBuildFrom
import scala.reflect.ClassTag
import scala.runtime.AbstractFunction1

final class Expr[A] private[Expr] (private[Expr] val run: Context => A) extends AbstractFunction1[Context, Expr.Result[A]] {

  override def apply(context: Context): Expr.Result[A] =
    try {
      Right(run(context))
    } catch {
      case t: ExprError => Left(t)
      case t: Throwable => throw t
    }

  def map[B](f: A => B): Expr[B] =
    new Expr(f compose run)

  def flatMap[B](f: A => Expr[B]): Expr[B] =
    new Expr(c => f(run(c)).run(c))

  @inline private def _as[B](f: Value => B)(implicit ev: Expr[A] =:= Expr[Value]): Expr[B] =
    ev(this).map(v => ExprError.InResult.capture(v, f))

  def asBoolean(implicit ev: Expr[A] =:= Expr[Value]): Expr[Boolean] = _as(_.asBoolean())
  def asByte   (implicit ev: Expr[A] =:= Expr[Value]): Expr[Byte   ] = _as(_.asByte())
  def asDouble (implicit ev: Expr[A] =:= Expr[Value]): Expr[Double ] = _as(_.asDouble())
  def asFloat  (implicit ev: Expr[A] =:= Expr[Value]): Expr[Float  ] = _as(_.asFloat())
  def asInt    (implicit ev: Expr[A] =:= Expr[Value]): Expr[Int    ] = _as(_.asInt())
  def asLong   (implicit ev: Expr[A] =:= Expr[Value]): Expr[Long   ] = _as(_.asLong())
  def asShort  (implicit ev: Expr[A] =:= Expr[Value]): Expr[Short  ] = _as(_.asShort())
  def asString (implicit ev: Expr[A] =:= Expr[Value]): Expr[String ] = _as(_.asString())

  def as[T](t: TypeLiteral[T])(implicit ev: Expr[A] =:= Expr[Value]): Expr[T] =
    _as(_.as(t))

  def as[T](implicit ev: Expr[A] =:= Expr[Value], ct: ClassTag[T]): Expr[T] = {
    val t = ct.runtimeClass.asInstanceOf[Class[T]]
    _as(_.as(t))
  }

  def asOption[F, B](f: Expr[Value] => Expr[B])(implicit ev: Expr[A] =:= Expr[Value]): Expr[Option[B]] = {
    val self = ev(this)
    new Expr(c => {
      val v = self.run(c)
      if (ExprError.InResult.capture(v, _.isNull))
        None
      else
        Some(f(Expr.const(v)).run(c))
    })
  }

  def timed: Expr[(Duration, A)] =
    new Expr(ctx => {
      val start = System.nanoTime()
      val a = run(ctx)
      val end = System.nanoTime()
      val dur = Duration.ofNanos(end - start)
      (dur, a)
    })
}

object Expr extends ExprBoilerplate {
  type Result[A] = Either[ExprError, A]

  def apply(source: CharSequence)(implicit language: Language): Expr[Value] =
    apply(Source.create(language.name, source))

  def apply(source: Source): Expr[Value] =
    new Expr(c => ExprError.InEval.capture(c.eval(source)))

  def lift[A](f: Context => A): Expr[A] =
    new Expr(f)

  def const[A](a: A): Expr[A] =
    new Expr(_ => a)

  def point[A](a: => A): Expr[A] =
    new Expr(_ => a)

  def stdlibDist[F[x] <: Traversable[x], A, B](fa: F[A])(f: A => Expr[B])
                                              (implicit cbf: CanBuildFrom[F[A], B, F[B]]): Expr[F[B]] =
    lift(c => {
      val b = cbf(fa)
      fa.foreach(a => b += f(a).run(c))
      b.result()
    })

  def stdlibCosequence[F[x] <: Traversable[x], A](fea: F[Expr[A]])
                                                 (implicit cbf: CanBuildFrom[F[Expr[A]], A, F[A]]): Expr[F[A]] =
    stdlibDist[F, Expr[A], A](fea)(identity)

  // TODO Remove
  final class Interpolation(private val sc: StringContext) extends AnyVal {

    def js(args: Any*): Expr[Value] =
      build(Language.JS, args: _*)

    private def build(lang: Language, args: Any*): Expr[Value] =
      if (args.isEmpty)
        Expr(sc.parts.head)(lang)
      else {
        val argArray: Array[Any] = args.map(lang.translateValue)(collection.breakOut)
        val iParts = sc.parts.iterator
        var i = 0
        val sb = new StringBuilder(iParts.next())
        while (iParts.hasNext) {
          sb.append(lang.argBinding.localValue)
          sb.append('[')
          sb.append(i)
          sb.append(']')
          sb.append(iParts.next)
          i += 1
        }
        val body = sb.toString()
        val bodySrc = Source.create(lang.name, body)
        val eval = lang.argBinder(bodySrc)
        lift{ctx =>
          lang.argBinding.set(ctx, argArray)
          eval(ctx)
        }
      }

  }

  // TODO Should include language in type
//  trait CommonArgs {
//    implicit val jsArgInt: Arg[Int] = Arg(ArgValue.Polyglot)
//    implicit val jsArgLong: Arg[Long] = Arg(ArgValue.Polyglot)
//    implicit val jsArgString: Arg[String] = Arg(ArgValue.Polyglot)
//    implicit val jsArgBoolean: Arg[Boolean] = Arg(ArgValue.Polyglot)
//    implicit val jsArgShort: Arg[Short] = Arg(ArgValue.Polyglot)
//    implicit val jsArgFloat: Arg[Float] = Arg(ArgValue.Polyglot)
//    implicit val jsArgDouble: Arg[Double] = Arg(ArgValue.Polyglot)
//  }
//
//  trait JsArgs {
//    implicit val jsArgUnit: Arg[Unit] = Arg.const(ArgValue.Literal("undefined"))
//  }

  override protected def genericOpt[Z](params: Array[ExprParam[X]],
                                       mkExprStr: Array[String] => String,
                                       post: Expr[Value] => Z)
                                      (implicit l: Language): Array[X] => Z = {
    val arity = params.length
    val indices = params.indices

    def mkRun(args: Array[X], usesBindings: Boolean): Context => Value = {
      val tokens = new Array[String](arity)
      for (i <- indices) {
        val token: String = params(i) match {
          case ExprParam.Const(t) => t
          case ExprParam.Polyglot(_) => l.argElement(i)
          case ExprParam.Custom(_) => l.argElement(i)
          case ExprParam.Literal(f) => f(args(i))
        }
        tokens(i) = token
      }
      val es = mkExprStr(tokens)
      val src = Source.create(l.name, es)
      if (usesBindings)
        l.argBinder(src)
      else
        _.eval(src)
    }

    def mkArrayP(args: Array[X]): Array[Any] = {
      val data = new Array[Any](arity)
      for (i <- indices) {
        params(i) match {
          case ExprParam.Polyglot(f) => data(i) = f(args(i))
          case ExprParam.Custom(_) | ExprParam.Const(_) | ExprParam.Literal(_) => ()
        }
      }
      data
    }

    def mkSetValuesPC(args: Array[X]): List[(Array[Any], Context) => Unit] = {
      var setValues = List.empty[(Array[Any], Context) => Unit]
      for (i <- indices) {
        params(i) match {
          case ExprParam.Polyglot(f) =>
            val v = f(args(i))
            setValues ::= ((tgt, _) => tgt(i) = v)
          case ExprParam.Custom(f) =>
            val g = f(args(i))
            setValues ::= ((tgt, ctx) => tgt(i) = g(ctx))
          case ExprParam.Const(_) | ExprParam.Literal(_) => ()
        }
      }
      setValues
    }

    def mkExprWithBindings(run: Context => Value, args: Array[X], hasCustom: Boolean): Z =
      post(
        if (hasCustom) {
          val setValues = mkSetValuesPC(args)
          lift { ctx =>
            val data = new Array[Any](arity)
            setValues.foreach(_ (data, ctx))
            l.argBinding.set(ctx, data)
            run(ctx)
          }
        } else {
          val data = mkArrayP(args)
          lift { ctx =>
            l.argBinding.set(ctx, data)
            run(ctx)
          }
        }
      )

    var hasLiteral, hasPolyglot, hasCustom = false
    params.foreach {
      case _: ExprParam.Const[X] => ()
      case _: ExprParam.Literal[X] =>  hasLiteral = true
      case _: ExprParam.Polyglot[X] =>  hasPolyglot = true
      case _: ExprParam.Custom[X] =>  hasCustom = true
    }
    val usesBindings = hasPolyglot || hasCustom

    if (usesBindings) {
      if (hasLiteral) {
        // usesBindings, hasLiteral
        args => {
          val run = mkRun(args, usesBindings = usesBindings)
          mkExprWithBindings(run, args, hasCustom = hasCustom)
        }
      } else {
        // usesBindings, !hasLiteral
        val run = mkRun(null, usesBindings = usesBindings)
        args => mkExprWithBindings(run, args, hasCustom = hasCustom)
      }
    } else {
      if (hasLiteral) {
        // !usesBindings, hasLiteral
        args => post(lift(mkRun(args, usesBindings = usesBindings)))
      } else {
        // !usesBindings, !hasLiteral
        val expr = post(lift(mkRun(null, usesBindings = usesBindings)))
        _ => expr
      }
    }
  }
}
