package com.twitter.algebird.macros

import scala.language.experimental.{ macros => sMacros }
import scala.reflect.macros.Context
import scala.reflect.runtime.universe._

/**
 * Object that "cubes" a case class or tuple, i.e. for a tuple of type
 * (T1, T2, ... , TN) generates all 2^N possible combinations of type
 * (Option[T1], Option[T2], ... , Option[TN]).
 *
 * This is useful for comparing some metric across all possible subsets.
 * For example, suppose we have a set of people represented as
 * case class Person(gender: String, age: Int, height: Double)
 * and we want to know the average height of
 *  - people, grouped by gender and age
 *  - people, grouped by only gender
 *  - people, grouped by only age
 *  - all people
 *
 * Then we could do
 * > import com.twitter.algebird.macros.Cuber.cuber
 * > val people: List[People]
 * > val averageHeights: Map[(Option[String], Option[Int]), Double] =
 * >   people.flatMap { p => (cuber((p.gender, p.age)), p) }
 * >     .groupBy(_._1)
 * >     .mapValues { xs => val heights = xs.map(_.height); heights.sum / heights.length }
 */
trait Cuber[I] {
  type K
  def apply(in: I): TraversableOnce[K]
}

/**
 * Object that hierarchically "rolls up" a case class or tuple,
 * i.e. for a tuple (x1, x2, ... , xN) of type (T1, T2, ... , TN)
 * generates a TraversableOnce[(Option[T1], Option[T2], ... , Option[TN]) that contains
 *   (None, None, None, ..., None)
 *   (Some(x1), None, None, ... , None)
 *   (Some(x1), Some(x2), None, ... , None)
 *   (Some(x1), Some(x2), Some(x3), ... , None)
 *      ...
 *   (Some(x1), Some(x2), Some(x3), ... , Some(xN))
 *
 * This is useful for comparing some metric across multiple layers of
 * some hierarchy.
 * For example, suppose we have some climate data represented as
 * case class Data(continent: String, country: String, city: String, temperature: Double)
 * and we want to know the average temperatures of
 *   - each continent
 *   - each (continent, country) pair
 *   - each (continent, country, city) triple
 *
 * Here we desire the (continent, country) and (continent, country, city)
 * pair because, for example, if we grouped by city instead of by
 * (continent, country, city), we would accidentally combine the results for
 * Paris, Texas and Paris, France.
 *
 * Then we could do
 * > import com.twitter.algebird.macros.Roller.roller
 * > val data: List[Data]
 * > val averageTemps: Map[(Option[String], Option[String], Option[String]), Double] =
 * > data.flatMap { d => (cuber((d.continent, d.country, d.city)), d) }
 * >   .groupBy(_._1)
 * >   .mapValues { xs => val temps = xs.map(_.temperature); temps.sum / temps.length }
 */
trait Roller[I] {
  type K
  def apply(in: I): TraversableOnce[K]
}

object Cuber {
  def cuber[T]: Cuber[T] = macro cuberImpl[T]

  def cuberImpl[T](c: Context)(implicit T: c.WeakTypeTag[T]): c.Expr[Cuber[T]] = {
    import c.universe._

    ensureCaseClass(c)

    val params = getParams(c)
    if (params.length > 22)
      c.abort(c.enclosingPosition, s"Cannot create Cuber for $T because it has more than 22 parameters.")
    if (params.length == 0)
      c.abort(c.enclosingPosition, s"Cannot create Cuber for $T because it has no parameters.")

    val tupleName = newTypeName(s"Tuple${params.length}")
    val types = params.map { param => tq"_root_.scala.Option[${param.returnType}]" }

    val fors = params.map { param =>
      fq"""${param.name.asInstanceOf[c.TermName]} <- _root_.scala.Seq(_root_.scala.Some(in.${param}), _root_.scala.None)"""
    }
    val names = params.map { param => param.name.asInstanceOf[c.TermName] }

    val cuber = q"""
    new _root_.com.twitter.algebird.macros.Cuber[${T}] {
      type K = $tupleName[..$types]
      def apply(in: ${T}): _root_.scala.Seq[K] = for (..$fors) yield new K(..$names)
    }
    """
    c.Expr[Cuber[T]](cuber)
  }
}

object Roller {
  def roller[T]: Roller[T] = macro rollerImpl[T]

  def rollerImpl[T](c: Context)(implicit T: c.WeakTypeTag[T]): c.Expr[Roller[T]] = {
    import c.universe._

    ensureCaseClass(c)

    val params = getParams(c)
    if (params.length > 22)
      c.abort(c.enclosingPosition, s"Cannot create Roller for $T because it has more than 22 parameters.")
    if (params.length == 0)
      c.abort(c.enclosingPosition, s"Cannot create Roller for $T because it has no parameters.")

    val tupleName = newTypeName(s"Tuple${params.length}")
    val types = params.map { param => tq"_root_.scala.Option[${param.returnType}]" }

    // params.head is safe because the case class has at least one member
    val firstFor = fq"""${params.head.name.asInstanceOf[c.TermName]} <- _root_.scala.Seq(_root_.scala.Some(in.${params.head}), _root_.scala.None)"""
    val restOfFors = params.tail.zip(params).map {
      case (param, prevParam) =>
        fq"""${param.name.asInstanceOf[c.TermName]} <- if (${prevParam.name.asInstanceOf[c.TermName]}.isDefined) _root_.scala.Seq(_root_.scala.Some(in.${param}), _root_.scala.None) else _root_.scala.Seq(_root_.scala.None)"""
    }
    val fors = firstFor :: restOfFors

    val names = params.map { param => param.name.asInstanceOf[c.TermName] }

    val cuber = q"""
    new _root_.com.twitter.algebird.macros.Roller[${T}] {
      type K = $tupleName[..$types]
      def apply(in: ${T}): _root_.scala.Seq[K] = for (..$fors) yield new K(..$names)
    }
    """
    c.Expr[Roller[T]](cuber)
  }
}
