// Copyright 2014,2015 Commonwealth Bank of Australia
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package au.com.cba.omnia.grimlock.spark

import au.com.cba.omnia.grimlock.framework.{
  Cell,
  Default,
  ExpandableMatrix => BaseExpandableMatrix,
  ExtractWithDimension,
  ExtractWithKey,
  Locate,
  Matrix => BaseMatrix,
  Matrixable => BaseMatrixable,
  MatrixWithParseErrors,
  NoParameters,
  Predicateable => BasePredicateable,
  ReduceableMatrix => BaseReduceableMatrix,
  Reducers,
  Sequence2,
  Tuner,
  TunerParameters,
  Type
}
import au.com.cba.omnia.grimlock.framework.aggregate._
import au.com.cba.omnia.grimlock.framework.content._
import au.com.cba.omnia.grimlock.framework.content.metadata._
import au.com.cba.omnia.grimlock.framework.encoding._
import au.com.cba.omnia.grimlock.framework.pairwise._
import au.com.cba.omnia.grimlock.framework.partition._
import au.com.cba.omnia.grimlock.framework.position._
import au.com.cba.omnia.grimlock.framework.sample._
import au.com.cba.omnia.grimlock.framework.squash._
import au.com.cba.omnia.grimlock.framework.transform._
import au.com.cba.omnia.grimlock.framework.utility._
import au.com.cba.omnia.grimlock.framework.utility.OneOf._
import au.com.cba.omnia.grimlock.framework.window._

import au.com.cba.omnia.grimlock.spark.Matrix._

import java.io.File
import java.nio.file.{ Files, Paths }

import org.apache.hadoop.io.Writable

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.collection.immutable.HashSet
import scala.reflect.ClassTag

private[spark] object SparkImplicits {
  implicit class RDDTuner[T](rdd: RDD[T]) {
    def tunedDistinct(parameters: TunerParameters)(implicit ev: Ordering[T]): RDD[T] = {
      parameters match {
        case Reducers(reducers) => rdd.distinct(reducers)(ev)
        case _ => rdd.distinct()
      }
    }
  }

  implicit class PairRDDTuner[K <: Position, V](rdd: RDD[(K, V)])(implicit kt: ClassTag[K], vt: ClassTag[V]) {
    def tunedJoin[W](parameters: TunerParameters, other: RDD[(K, W)]): RDD[(K, (V, W))] = {
      parameters match {
        case Reducers(reducers) => rdd.join(other, reducers)
        case _ => rdd.join(other)
      }
    }

    def tunedLeftJoin[W](parameters: TunerParameters, other: RDD[(K, W)]): RDD[(K, (V, Option[W]))] = {
      parameters match {
        case Reducers(reducers) => rdd.leftOuterJoin(other, reducers)
        case _ => rdd.leftOuterJoin(other)
      }
    }

    def tunedOuterJoin[W](parameters: TunerParameters, other: RDD[(K, W)]): RDD[(K, (Option[V], Option[W]))] = {
      parameters match {
        case Reducers(reducers) => rdd.fullOuterJoin(other, reducers)
        case _ => rdd.fullOuterJoin(other)
      }
    }

    def tunedReduce(parameters: TunerParameters, reduction: (V, V) => V): RDD[(K, V)] = {
      parameters match {
        case Reducers(reducers) => rdd.reduceByKey(reduction, reducers)
        case _ => rdd.reduceByKey(reduction)
      }
    }
  }
}

/** Base trait for matrix operations using a `RDD[Cell[P]]`. */
trait Matrix[P <: Position] extends BaseMatrix[P] with Persist[Cell[P]] {
  type U[A] = RDD[A]
  type E[B] = B
  type S = Matrix[P]

  import SparkImplicits._

  type ChangeTuners = TP2
  def change[I, T <: Tuner](slice: Slice[P], positions: I, schema: Schema, tuner: T = Default())(
    implicit ev1: PositionDistributable[I, slice.S, RDD], ev2: ClassTag[slice.S],
      ev3: ChangeTuners#V[T]): U[Cell[P]] = {
    data
      .keyBy { case c => slice.selected(c.position) }
      .tunedLeftJoin(tuner.parameters, ev1.convert(positions).keyBy { case p => p })
      .flatMap {
        case (_, (c, po)) => po match {
          case Some(_) => schema.decode(c.content.value.toShortString).map { case con => Cell(c.position, con) }
          case None => Some(c)
        }
      }
  }

  type CompactTuners = TP2
  def compact()(implicit ev: ClassTag[P]): E[Map[P, Content]] = {
    data
      .map { case c => (c.position, c.content) }
      .collectAsMap
      .toMap
  }

  def compact[T <: Tuner](slice: Slice[P], tuner: T = Default())(implicit ev1: slice.S =!= Position0D,
    ev2: ClassTag[slice.S], ev3: CompactTuners#V[T]): E[Map[slice.S, slice.C]] = {
    data
      .map { case c => (c.position, slice.toMap(c)) }
      .keyBy { case (p, m) => slice.selected(p) }
      .tunedReduce(tuner.parameters, (l: (P, Map[slice.S, slice.C]), r: (P, Map[slice.S, slice.C])) =>
        (l._1, slice.combineMaps(l._1, l._2, r._2)))
      .map { case (_, (_, m)) => m }
      .reduce { case (lm, rm) => lm ++ rm }
  }

  type DomainTuners = TP1

  type GetTuners = TP2
  def get[I, T <: Tuner](positions: I, tuner: T = Default())(implicit ev1: PositionDistributable[I, P, RDD],
    ev2: ClassTag[P], ev3: GetTuners#V[T]): U[Cell[P]] = {
    data
      .keyBy { case c => c.position }
      .tunedJoin(tuner.parameters, ev1.convert(positions).keyBy { case p => p })
      .map { case (_, (c, _)) => c }
  }

  type JoinTuners = TP3
  def join[T <: Tuner](slice: Slice[P], that: S, tuner: T = Default())(implicit ev1: P =!= Position1D,
    ev2: ClassTag[slice.S], ev3: JoinTuners#V[T]): U[Cell[P]] = {
    val (p1, p2) = tuner.parameters match {
      case Sequence2(f, s) => (f, s)
      case p => (NoParameters, p)
    }

    (data ++ that.data)
      .keyBy { case c => slice.selected(c.position) }
      .tunedJoin(p2, names(slice).map { case p => (p, ()) }.tunedJoin(p1, that.names(slice).map { case p => (p, ()) }))
      .map { case (_, (c, _)) => c }
  }

  type MaterialiseTuners = TP1
  def materialise[T <: Tuner](tuner: T = Default())(implicit ev: MaterialiseTuners#V[T]): List[Cell[P]] = {
    data.collect.toList
  }

  type NamesTuners = TP2
  def names[T <: Tuner](slice: Slice[P], tuner: T = Default())(implicit ev1: slice.S =!= Position0D,
    ev2: ClassTag[slice.S], ev3: NamesTuners#V[T]): U[slice.S] = {
    data.map { case c => slice.selected(c.position) }.tunedDistinct(tuner.parameters)(Position.Ordering[slice.S]())
  }

  type PairwiseTuners = OneOf6[Default[NoParameters.type],
                               Default[Reducers],
                               Default[Sequence2[Reducers, Reducers]],
                               Default[Sequence2[Reducers, Sequence2[Reducers, Reducers]]],
                               Default[Sequence2[Sequence2[Reducers, Reducers], Reducers]],
                               Default[Sequence2[Sequence2[Reducers, Reducers], Sequence2[Reducers, Reducers]]]]
  def pairwise[Q <: Position, T <: Tuner](slice: Slice[P], comparer: Comparer, operators: Operable[P, Q],
    tuner: T = Default())(implicit ev1: slice.S =!= Position0D, ev2: PosExpDep[slice.R, Q], ev3: ClassTag[slice.S],
      ev4: ClassTag[slice.R], ev5: PairwiseTuners#V[T]): U[Cell[Q]] = {
    val operator = operators()

    pairwiseTuples(slice, comparer, tuner)(data, data).flatMap { case (lc, rc) => operator.compute(lc, rc) }
  }

  def pairwiseWithValue[Q <: Position, W, T <: Tuner](slice: Slice[P], comparer: Comparer,
    operators: OperableWithValue[P, Q, W], value: E[W], tuner: T = Default())(implicit ev1: slice.S =!= Position0D,
      ev2: PosExpDep[slice.R, Q], ev3: ClassTag[slice.S], ev4: ClassTag[slice.R],
        ev5: PairwiseTuners#V[T]): U[Cell[Q]] = {
    val operator = operators()

    pairwiseTuples(slice, comparer, tuner)(data, data)
      .flatMap { case (lc, rc) => operator.computeWithValue(lc, rc, value) }
  }

  def pairwiseBetween[Q <: Position, T <: Tuner](slice: Slice[P], comparer: Comparer, that: S,
    operators: Operable[P, Q], tuner: T = Default())(implicit ev1: slice.S =!= Position0D, ev2: PosExpDep[slice.R, Q],
      ev3: ClassTag[slice.S], ev4: ClassTag[slice.R], ev5: PairwiseTuners#V[T]): U[Cell[Q]] = {
    val operator = operators()

    pairwiseTuples(slice, comparer, tuner)(data, that.data).flatMap { case (lc, rc) => operator.compute(lc, rc) }
  }

  def pairwiseBetweenWithValue[Q <: Position, W, T <: Tuner](slice: Slice[P], comparer: Comparer, that: S,
    operators: OperableWithValue[P, Q, W], value: E[W], tuner: T = Default())(implicit ev1: slice.S =!= Position0D,
      ev2: PosExpDep[slice.R, Q], ev3: ClassTag[slice.S], ev4: ClassTag[slice.R],
        ev5: PairwiseTuners#V[T]): U[Cell[Q]] = {
    val operator = operators()

    pairwiseTuples(slice, comparer, tuner)(data, that.data)
      .flatMap { case (lc, rc) => operator.computeWithValue(lc, rc, value) }
  }

  def rename(renamer: (Cell[P]) => Option[P]): U[Cell[P]] = {
    data.flatMap { case c => renamer(c).map { Cell(_, c.content) } }
  }

  def renameWithValue[W](renamer: (Cell[P], W) => Option[P], value: E[W]): U[Cell[P]] = {
    data.flatMap { case c => renamer(c, value).map { Cell(_, c.content) } }
  }

  def sample[F](samplers: F)(implicit ev: Sampleable[F, P]): U[Cell[P]] = {
    val sampler = ev.convert(samplers)

    data.filter { case c => sampler.select(c) }
  }

  def sampleWithValue[F, W](samplers: F, value: E[W])(implicit ev: SampleableWithValue[F, P, W]): U[Cell[P]] = {
    val sampler = ev.convert(samplers)

    data.filter { case c => sampler.selectWithValue(c, value) }
  }

  def saveAsText(file: String, writer: TextWriter = Cell.toString()): U[Cell[P]] = saveText(file, writer)

  type SetTuners = TP2
  def set[M, T <: Tuner](values: M, tuner: T = Default())(implicit ev1: BaseMatrixable[M, P, RDD], ev2: ClassTag[P],
    ev3: SetTuners#V[T]): U[Cell[P]] = {
    data
      .keyBy { case c => c.position }
      .tunedOuterJoin(tuner.parameters, ev1.convert(values).keyBy { case c => c.position })
      .map { case (_, (co, cn)) => cn.getOrElse(co.get) }
  }

  type ShapeTuners = TP2
  def shape[T <: Tuner](tuner: T = Default())(implicit ev: ShapeTuners#V[T]): U[Cell[Position1D]] = {
    data
      .flatMap { case c => c.position.coordinates.map(_.toString).zipWithIndex }
      .tunedDistinct(tuner.parameters)
      .groupBy { case (s, i) => i }
      .map {
        case (i, s) => Cell(Position1D(Dimension.All(i).toString), Content(DiscreteSchema(LongCodex), s.size))
      }
  }

  type SizeTuners = TP2
  def size[D <: Dimension, T <: Tuner](dim: D, distinct: Boolean, tuner: T = Default())(implicit ev1: PosDimDep[P, D],
    ev2: SizeTuners#V[T]): U[Cell[Position1D]] = {
    val coords = data.map { case c => c.position(dim) }
    val dist = if (distinct) { coords } else { coords.tunedDistinct(tuner.parameters)(Value.Ordering) }

    dist
      .context
      .parallelize(List(Cell(Position1D(dim.toString), Content(DiscreteSchema(LongCodex), dist.count))))
  }

  type SliceTuners = TP2
  def slice[I, T <: Tuner](slice: Slice[P], positions: I, keep: Boolean, tuner: T = Default())(
    implicit ev1: PositionDistributable[I, slice.S, RDD], ev2: ClassTag[slice.S],
      ev3: SliceTuners#V[T]): U[Cell[P]] = {
    data
      .keyBy { case c => slice.selected(c.position) }
      .tunedLeftJoin(tuner.parameters, ev1.convert(positions).map { case p => (p, ()) })
      .collect { case (_, (c, o)) if (o.isEmpty != keep) => c }
  }

  type SlideTuners = TP1
  def slide[Q <: Position, F, T <: Tuner](slice: Slice[P], windows: F, ascending: Boolean, tuner: T = Default())(
    implicit ev1: Windowable[F, slice.S, slice.R, Q], ev2: slice.R =!= Position0D, ev3: ClassTag[slice.S],
      ev4: ClassTag[slice.R], ev5: SlideTuners#V[T]): U[Cell[Q]] = {
    val window = ev1.convert(windows)

    data
      .map { case Cell(p, c) => (Cell(slice.selected(p), c), slice.remainder(p)) }
      .groupBy { case (c, r) => c.position }
      .flatMap {
        case (_, itr) => itr
          .toList
          .sortBy { case (c, r) => r }(Position.Ordering(ascending))
          .scanLeft(Option.empty[(window.T, TraversableOnce[Cell[Q]])]) {
            case (None, (c, r)) => Some(window.initialise(c, r))
            case (Some((t, _)), (c, r)) => Some(window.present(c, r, t))
          }
          .flatMap {
            case Some((t, c)) => c
            case _ => None
          }
      }
  }

  def slideWithValue[Q <: Position, F, W, T <: Tuner](slice: Slice[P], windows: F, value: E[W], ascending: Boolean,
    tuner: T = Default())(implicit ev1: WindowableWithValue[F, slice.S, slice.R, Q, W], ev2: slice.R =!= Position0D,
      ev3: ClassTag[slice.S], ev4: ClassTag[slice.R], ev5: SlideTuners#V[T]): U[Cell[Q]] = {
    val window = ev1.convert(windows)

    data
      .map { case Cell(p, c) => (Cell(slice.selected(p), c), slice.remainder(p)) }
      .groupBy { case (c, r) => c.position }
      .flatMap {
        case (_, itr) => itr
          .toList
          .sortBy { case (c, r) => r }(Position.Ordering(ascending))
          .scanLeft(Option.empty[(window.T, TraversableOnce[Cell[Q]])]) {
            case (None, (c, r)) => Some(window.initialiseWithValue(c, r, value))
            case (Some((t, _)), (c, r)) => Some(window.presentWithValue(c, r, value, t))
          }
          .flatMap {
            case Some((t, c)) => c
            case _ => None
          }
      }
  }

  def split[Q, F](partitioners: F)(implicit ev: Partitionable[F, P, Q]): U[(Q, Cell[P])] = {
    val partitioner = ev.convert(partitioners)

    data.flatMap { case c => partitioner.assign(c).map { case q => (q, c) } }
  }

  def splitWithValue[Q, F, W](partitioners: F, value: E[W])(
    implicit ev: PartitionableWithValue[F, P, Q, W]): U[(Q, Cell[P])] = {
    val partitioner = ev.convert(partitioners)

    data.flatMap { case c => partitioner.assignWithValue(c, value).map { case q => (q, c) } }
  }

  def stream[Q <: Position](command: String, files: List[String], writer: TextWriter,
    parser: BaseMatrix.TextParser[Q]): (U[Cell[Q]], U[String]) = {
    val result = data
      .flatMap(writer(_))
      .pipe(command)
      .flatMap(parser(_))

    (result.collect { case Left(c) => c }, result.collect { case Right(e) => e })
  }

  type SummariseTuners = TP2
  def summarise[Q <: Position, F, T <: Tuner](slice: Slice[P], aggregators: F, tuner: T = Default())(
    implicit ev1: Aggregatable[F, P, slice.S, Q], ev2: ClassTag[slice.S], ev3: SummariseTuners#V[T]): U[Cell[Q]] = {
    val aggregator = ev1.convert(aggregators)

    data
      .map { case c => (slice.selected(c.position), aggregator.map { case a => a.prepare(c) }) }
      .tunedReduce(tuner.parameters, (lt: List[Any], rt: List[Any]) =>
        (aggregator, lt, rt).zipped.map { case (a, l, r) => a.reduce(l.asInstanceOf[a.T], r.asInstanceOf[a.T]) })
      .flatMap {
        case (p, t) => (aggregator, t).zipped.flatMap { case (a, s) => a.present(p, s.asInstanceOf[a.T]) }
      }
  }

  def summariseWithValue[Q <: Position, F, W, T <: Tuner](slice: Slice[P], aggregators: F, value: E[W],
    tuner: T = Default())(implicit ev1: AggregatableWithValue[F, P, slice.S, Q, W], ev2: ClassTag[slice.S],
      ev3: SummariseTuners#V[T]): U[Cell[Q]] = {
    val aggregator = ev1.convert(aggregators)

    data
      .map { case c => (slice.selected(c.position), aggregator.map { case a => a.prepareWithValue(c, value) }) }
      .tunedReduce(tuner.parameters, (lt: List[Any], rt: List[Any]) =>
        (aggregator, lt, rt).zipped.map { case (a, l, r) => a.reduce(l.asInstanceOf[a.T], r.asInstanceOf[a.T]) })
      .flatMap {
        case (p, t) => (aggregator, t).zipped.flatMap {
          case (a, s) => a.presentWithValue(p, s.asInstanceOf[a.T], value)
        }
      }
  }

  def toSequence[K <: Writable, V <: Writable](writer: SequenceWriter[K, V]): U[(K, V)] = data.flatMap(writer(_))

  def toText(writer: TextWriter): U[String] = data.flatMap(writer(_))

  def transform[Q <: Position, F](transformers: F)(implicit ev: Transformable[F, P, Q]): U[Cell[Q]] = {
    val transformer = ev.convert(transformers)

    data.flatMap { case c => transformer.present(c) }
  }

  def transformWithValue[Q <: Position, F, W](transformers: F, value: E[W])(
    implicit ev: TransformableWithValue[F, P, Q, W]): U[Cell[Q]] = {
    val transformer = ev.convert(transformers)

    data.flatMap { case c => transformer.presentWithValue(c, value) }
  }

  type TypesTuners = TP2
  def types[T <: Tuner](slice: Slice[P], specific: Boolean, tuner: T = Default())(implicit ev1: slice.S =!= Position0D,
    ev2: ClassTag[slice.S], ev3: TypesTuners#V[T]): U[(slice.S, Type)] = {
    data
      .map { case Cell(p, c) => (slice.selected(p), c.schema.kind) }
      .tunedReduce(tuner.parameters, (lt: Type, rt: Type) => Type.getCommonType(lt, rt))
      .map { case (p, t) => (p, if (specific) t else t.getGeneralisation()) }
  }

  type UniqueTuners = TP2
  def unique[T <: Tuner](tuner: T = Default())(implicit ev: UniqueTuners#V[T]): U[Content] = {
    val ordering = new Ordering[Content] { def compare(l: Content, r: Content) = l.toString.compare(r.toString) }

    data
      .map { case c => c.content }
      .tunedDistinct(tuner.parameters)(ordering)
  }

  def uniqueByPositions[T <: Tuner](slice: Slice[P], tuner: T = Default())(implicit ev1: slice.S =!= Position0D,
    ev2: UniqueTuners#V[T]): U[(slice.S, Content)] = {
    val ordering = new Ordering[Cell[slice.S]] {
      def compare(l: Cell[slice.S], r: Cell[slice.S]) = l.toString().compare(r.toString)
    }

    data
      .map { case Cell(p, c) => Cell(slice.selected(p), c) }
      .tunedDistinct(tuner.parameters)(ordering)
      .map { case Cell(p, c) => (p, c) }
  }

  type WhichTuners = TP2
  def which(predicate: BaseMatrix.Predicate[P])(implicit ev: ClassTag[P]): U[P] = {
    data.collect { case c if predicate(c) => c.position }
  }

  def whichByPositions[I, T <: Tuner](slice: Slice[P], predicates: I, tuner: T = Default())(
    implicit ev1: BasePredicateable[I, P, slice.S, RDD], ev2: ClassTag[slice.S], ev3: ClassTag[P],
      ev4: WhichTuners#V[T]): U[P] = {
    val pp = ev1.convert(predicates)
      .map { case (pos, pred) => pos.map { case p => (p, pred) } }
      .reduce((l, r) => l ++ r)

    data
      .keyBy { case c => slice.selected(c.position) }
      .tunedJoin(tuner.parameters, pp.keyBy { case (p, pred) => p })
      .collect { case (_, (c, (_, predicate))) if predicate(c) => c.position }
  }

  val data: U[Cell[P]]

  protected def saveDictionary(slice: Slice[P], file: String, dictionary: String, separator: String)(
    implicit ev: ClassTag[slice.S]) = {
    val numbered = names(slice)
      .zipWithIndex

    numbered
      .map { case (p, i) => p.toShortString(separator) + separator + i }
      .saveAsTextFile(dictionary.format(file, slice.dimension.index))

    numbered
  }

  private def pairwiseTuples[T <: Tuner](slice: Slice[P], comparer: Comparer, tuner: T)(ldata: U[Cell[P]],
    rdata: U[Cell[P]])(implicit ev1: ClassTag[slice.S], ev2: ClassTag[slice.R]): U[(Cell[P], Cell[P])] = {
    val (rr, rj, lr, lj) = tuner.parameters match {
      case Sequence2(Sequence2(rr, rj), Sequence2(lr, lj)) => (rr, rj, lr, lj)
      case Sequence2(rj @ Reducers(_), Sequence2(lr, lj)) => (NoParameters, rj, lr, lj)
      case Sequence2(Sequence2(rr, rj), lj @ Reducers(_)) => (rr, rj, NoParameters, lj)
      case Sequence2(rj @ Reducers(_), lj @ Reducers(_)) => (NoParameters, rj, NoParameters, lj)
      case lj @ Reducers(_) => (NoParameters, NoParameters, NoParameters, lj)
      case _ => (NoParameters, NoParameters, NoParameters, NoParameters)
    }
    val ordering = Position.Ordering[slice.S]()

    ldata.map { case c => slice.selected(c.position) }.tunedDistinct(lr)(ordering)
      .cartesian(rdata.map { case c => slice.selected(c.position) }.tunedDistinct(rr)(ordering))
      .collect { case (l, r) if comparer.keep(l, r) => (l, r) }
      .keyBy { case (l, _) => l }
      .tunedJoin(lj, ldata.keyBy { case Cell(p, _) => slice.selected(p) })
      .keyBy { case (_, ((_, r),  _)) => r }
      .tunedJoin(rj, rdata.keyBy { case Cell(p, _) => slice.selected(p) })
      .map { case (_, ((_, (_, lc)), rc)) => (lc, rc) }
  }
}

/** Base trait for methods that reduce the number of dimensions or that can be filled using a `RDD[Cell[P]]`. */
trait ReduceableMatrix[P <: Position with ReduceablePosition] extends BaseReduceableMatrix[P] { self: Matrix[P] =>

  import SparkImplicits._

  type FillHeterogeneousTuners = TP3
  def fillHeterogeneous[Q <: Position, T <: Tuner](slice: Slice[P], values: U[Cell[Q]], tuner: T = Default())(
    implicit ev1: ClassTag[P], ev2: ClassTag[slice.S], ev3: slice.S =:= Q,
      ev4: FillHeterogeneousTuners#V[T]): U[Cell[P]] = {
    val (p1, p2) = tuner.parameters match {
      case Sequence2(f, s) => (f, s)
      case p => (NoParameters, p)
    }

    domain(Default())
      .keyBy { case p => slice.selected(p) }
      .tunedJoin(p1, values.keyBy { case c => c.position.asInstanceOf[slice.S] })
      .map { case (_, (p, c)) => (p, Cell(p, c.content)) }
      .tunedLeftJoin(p2, data.keyBy { case c => c.position })
      .map { case (_, (c, co)) => co.getOrElse(c) }
  }

  type FillHomogeneousTuners = TP2
  def fillHomogeneous[T <: Tuner](value: Content, tuner: T = Default())(implicit ev1: ClassTag[P],
    ev2: FillHomogeneousTuners#V[T]): U[Cell[P]] = {
    domain(Default())
      .keyBy { case p => p }
      .tunedLeftJoin(tuner.parameters, data.keyBy { case c => c.position })
      .map { case (p, (_, co)) => co.getOrElse(Cell(p, value)) }
  }

  def melt[D <: Dimension, G <: Dimension](dim: D, into: G, separator: String = ".")(implicit ev1: PosDimDep[P, D],
    ev2: PosDimDep[P, G], ne: D =!= G): U[Cell[P#L]] = {
    data.map { case Cell(p, c) => Cell(p.melt(dim, into, separator), c) }
  }

  type SquashTuners = TP2
  def squash[D <: Dimension, F, T <: Tuner](dim: D, squasher: F, tuner: T = Default())(implicit ev1: PosDimDep[P, D],
    ev2: Squashable[F, P], ev3: ClassTag[P#L], ev4: SquashTuners#V[T]): U[Cell[P#L]] = {
    val squash = ev2.convert(squasher)

    data
      .map[(P#L, Any)] { case c => (c.position.remove(dim), squash.prepare(c, dim)) }
      .tunedReduce(tuner.parameters, (lt, rt) => squash.reduce(lt.asInstanceOf[squash.T], rt.asInstanceOf[squash.T]))
      .flatMap { case (p, t) => squash.present(t.asInstanceOf[squash.T]).map { case c => Cell(p, c) } }
  }

  def squashWithValue[D <: Dimension, F, W, T <: Tuner](dim: D, squasher: F, value: E[W], tuner: T = Default())(
    implicit ev1: PosDimDep[P, D], ev2: SquashableWithValue[F, P, W], ev3: ClassTag[P#L],
      ev4: SquashTuners#V[T]): U[Cell[P#L]] = {
    val squash = ev2.convert(squasher)

    data
      .map[(P#L, Any)] { case c => (c.position.remove(dim), squash.prepareWithValue(c, dim, value)) }
      .tunedReduce(tuner.parameters, (lt, rt) => squash.reduce(lt.asInstanceOf[squash.T], rt.asInstanceOf[squash.T]))
      .flatMap { case (p, t) => squash.presentWithValue(t.asInstanceOf[squash.T], value).map { case c => Cell(p, c) } }
  }

  def toVector(separator: String): U[Cell[Position1D]] = {
    data.map { case Cell(p, c) => Cell(Position1D(p.coordinates.map(_.toShortString).mkString(separator)), c) }
  }
}

/** Base trait for methods that expand the number of dimension of a matrix using a `RDD[Cell[P]]`. */
trait ExpandableMatrix[P <: Position with ExpandablePosition] extends BaseExpandableMatrix[P] { self: Matrix[P] =>

  def expand[Q <: Position](expander: Cell[P] => TraversableOnce[Q])(implicit ev: PosExpDep[P, Q]): RDD[Cell[Q]] = {
    data.flatMap { case c => expander(c).map { Cell(_, c.content) } }
  }

  def expandWithValue[Q <: Position, W](expander: (Cell[P], W) => TraversableOnce[Q], value: W)(
    implicit ev: PosExpDep[P, Q]): RDD[Cell[Q]] = {
    data.flatMap { case c => expander(c, value).map { Cell(_, c.content) } }
  }
}

// TODO: Make this work on more than 2D matrices and share with Scalding
trait MatrixDistance { self: Matrix[Position2D] with ReduceableMatrix[Position2D] =>

  import au.com.cba.omnia.grimlock.library.aggregate._
  import au.com.cba.omnia.grimlock.library.pairwise._
  import au.com.cba.omnia.grimlock.library.transform._
  import au.com.cba.omnia.grimlock.library.window._

  /**
   * Compute correlations.
   *
   * @param slice  Encapsulates the dimension for which to compute correlations.
   * @param stuner The summarise tuner for the job.
   * @param ptuner The pairwise tuner for the job.
   *
   * @return A `U[Cell[Position1D]]` with all pairwise correlations.
   */
  def correlation[ST <: Tuner, PT <: Tuner](slice: Slice[Position2D], stuner: ST = Default(), ptuner: PT = Default())(
    implicit ev1: ClassTag[slice.S], ev2: ClassTag[slice.R], ev3: SummariseTuners#V[ST],
      ev4: PairwiseTuners#V[PT]): U[Cell[Position1D]] = {
    implicit def UP2DSC2M1D(data: U[Cell[slice.S]]): Matrix1D = new Matrix1D(data.asInstanceOf[U[Cell[Position1D]]])
    implicit def UP2DRMC2M2D(data: U[Cell[slice.R#M]]): Matrix2D = new Matrix2D(data.asInstanceOf[U[Cell[Position2D]]])

    val mean = data
      .summarise(slice, Mean[Position2D, slice.S](), stuner)
      .compact(Over(First))

    implicit object P2D extends PosDimDep[Position2D, slice.dimension.type]

    val centered = data
      .transformWithValue(Subtract(ExtractWithDimension[slice.dimension.type, Position2D, Content](slice.dimension)
        .andThenPresent(_.value.asDouble)), mean)

    val denom = centered
      .transform(Power[Position2D](2))
      .summarise(slice, Sum[Position2D, slice.S](), stuner)
      .pairwise(Over(First), Lower, Times(Locate.OperatorString[Position1D](Over(First), "(%1$s*%2$s)")), ptuner)
      .transform(SquareRoot[Position1D]())
      .compact(Over(First))

    centered
      .pairwise(slice, Lower, Times(Locate.OperatorString[Position2D](slice, "(%1$s*%2$s)")), ptuner)
      .summarise(Over(First), Sum[Position2D, Position1D](), stuner)
      .transformWithValue(Fraction(ExtractWithDimension[Dimension.First, Position1D, Content](First)
        .andThenPresent(_.value.asDouble)), denom)
  }

  /**
   * Compute mutual information.
   *
   * @param slice  Encapsulates the dimension for which to compute mutual information.
   * @param stuner The summarise tuner for the job.
   * @param ptuner The pairwise tuner for the job.
   *
   * @return A `U[Cell[Position1D]]` with all pairwise mutual information.
   */
  def mutualInformation[ST <: Tuner, PT <: Tuner](slice: Slice[Position2D], stuner: ST = Default(),
    ptuner: PT = Default())(implicit ev1: ClassTag[slice.S], ev2: ClassTag[slice.R], ev3: SummariseTuners#V[ST],
      ev4: PairwiseTuners#V[PT]): U[Cell[Position1D]] = {
    implicit def UP2DRMC2M2D(data: U[Cell[slice.R#M]]): Matrix2D = new Matrix2D(data.asInstanceOf[U[Cell[Position2D]]])

    val dim = slice match {
      case Over(First) => Second
      case Over(Second) => First
      case Along(d) => d
      case _ => throw new Exception("unexpected dimension")
    }

    implicit object P3D extends PosDimDep[Position3D, dim.type]

    type W = Map[Position1D, Content]

    val extractor = ExtractWithDimension[Dimension.First, Position2D, Content](First)
      .andThenPresent(_.value.asDouble)

    val mhist = data
      .expand(c => Some(c.position.append(c.content.value.toShortString)))
      .summarise(Along[Position3D, dim.type](dim), Count[Position3D, Position2D](), stuner)

    val mcount = mhist
      .summarise(Over(First), Sum[Position2D, Position1D](), stuner)
      .compact()

    val marginal = mhist
      .summariseWithValue(Over(First), Entropy[Position2D, Position1D, W](extractor)
        .andThenExpandWithValue((cell, _) => cell.position.append("marginal")), mcount, stuner)
      .pairwise(Over(First), Upper, Plus(Locate.OperatorString[Position2D](Over(First), "%s,%s")), ptuner)

    val jhist = data
      .pairwise(slice, Upper, Concatenate(Locate.OperatorString[Position2D](slice, "%s,%s")), ptuner)
      .expand(c => Some(c.position.append(c.content.value.toShortString)))
      .summarise(Along(Second), Count[Position3D, Position2D](), stuner)

    val jcount = jhist
      .summarise(Over(First), Sum[Position2D, Position1D](), stuner)
      .compact()

    val joint = jhist
      .summariseWithValue(Over(First), Entropy[Position2D, Position1D, W](extractor, negate = true)
        .andThenExpandWithValue((cell, _) => cell.position.append("joint")), jcount, stuner)

    (marginal ++ joint)
      .summarise(Over(First), Sum[Position2D, Position1D](), stuner)
  }

  /**
   * Compute Gini index.
   *
   * @param slice  Encapsulates the dimension for which to compute the Gini index.
   * @param stuner The summarise tuner for the job.
   * @param wtuner The window tuner for the job.
   * @param ptuner The pairwise tuner for the job.
   *
   * @return A `U[Cell[Position1D]]` with all pairwise Gini indices.
   */
  def gini[ST <: Tuner, WT <: Tuner, PT <: Tuner](slice: Slice[Position2D], stuner: ST = Default(),
    wtuner: WT = Default(), ptuner: PT = Default())(implicit ev1: ClassTag[slice.S], ev2: ClassTag[slice.R],
      ev3: SummariseTuners#V[ST], ev4: SlideTuners#V[WT], ev5: PairwiseTuners#V[PT]): U[Cell[Position1D]] = {
    implicit def UP2DSC2M1D(data: U[Cell[slice.S]]): Matrix1D = new Matrix1D(data.asInstanceOf[U[Cell[Position1D]]])
    implicit def UP2DSMC2M2D(data: U[Cell[slice.S#M]]): Matrix2D = new Matrix2D(data.asInstanceOf[U[Cell[Position2D]]])

    def isPositive = (cell: Cell[Position2D]) => cell.content.value.asDouble.map(_ > 0).getOrElse(false)
    def isNegative = (cell: Cell[Position2D]) => cell.content.value.asDouble.map(_ <= 0).getOrElse(false)

    val extractor = ExtractWithDimension[Dimension.First, Position2D, Content](First)
      .andThenPresent(_.value.asDouble)

    val pos = data
      .transform(Compare[Position2D](isPositive))
      .summarise(slice, Sum[Position2D, slice.S](), stuner)
      .compact(Over(First))

    val neg = data
      .transform(Compare[Position2D](isNegative))
      .summarise(slice, Sum[Position2D, slice.S](), stuner)
      .compact(Over(First))

    val tpr = data
      .transform(Compare[Position2D](isPositive))
      .slide(slice, CumulativeSum(Locate.WindowString[slice.S, slice.R]()), true, wtuner)
      .transformWithValue(Fraction(extractor), pos)
      .slide(Over(First), BinOp((l: Double, r: Double) => r + l,
        Locate.WindowPairwiseString[Position1D, Position1D]("%2$s.%1$s")), true, wtuner)

    val fpr = data
      .transform(Compare[Position2D](isNegative))
      .slide(slice, CumulativeSum(Locate.WindowString[slice.S, slice.R]()), true, wtuner)
      .transformWithValue(Fraction(extractor), neg)
      .slide(Over(First), BinOp((l: Double, r: Double) => r - l,
        Locate.WindowPairwiseString[Position1D, Position1D]("%2$s.%1$s")), true, wtuner)

    tpr
      .pairwiseBetween(Along(First), Diagonal, fpr,
        Times(Locate.OperatorString[Position2D](Along(First), "(%1$s*%2$s)")), ptuner)
      .summarise(Along(First), Sum[Position2D, Position1D](), stuner)
      .transformWithValue(Subtract(ExtractWithKey[Position1D, String, Double]("one"), true),
        Map(Position1D("one") -> 1.0))
  }
}

object Matrix {
  /**
   * Read column oriented, pipe separated matrix text data into a `RDD[Cell[P]]`.
   *
   * @param file   The text file to read from.
   * @param parser The parser that converts a single line to a cell.
   */
  def loadText[P <: Position](file: String, parser: BaseMatrix.TextParser[P])(
    implicit sc: SparkContext): (RDD[Cell[P]], RDD[String]) = {
    val rdd = sc.textFile(file).flatMap { parser(_) }

    (rdd.collect { case Left(c) => c }, rdd.collect { case Right(e) => e })
  }

  /**
   * Read binary key-value (sequence) matrix data into a `RDD[Cell[P]]`.
   *
   * @param file   The text file to read from.
   * @param parser The parser that converts a single key-value to a cell.
   */
  def loadSequence[K <: Writable, V <: Writable, P <: Position](file: String,
    parser: BaseMatrix.SequenceParser[K, V, P])(implicit sc: SparkContext, ev1: ClassTag[K],
      ev2: ClassTag[V]): (RDD[Cell[P]], RDD[String]) = {
    val pipe = sc.sequenceFile[K, V](file).flatMap { case (k, v) => parser(k, v) }

    (pipe.collect { case Left(c) => c }, pipe.collect { case Right(e) => e })
  }

  /** Conversion from `RDD[Cell[Position1D]]` to a Spark `Matrix1D`. */
  implicit def RDD2M1(data: RDD[Cell[Position1D]]): Matrix1D = new Matrix1D(data)
  /** Conversion from `RDD[Cell[Position2D]]` to a Spark `Matrix2D`. */
  implicit def RDD2M2(data: RDD[Cell[Position2D]]): Matrix2D = new Matrix2D(data)
  /** Conversion from `RDD[Cell[Position3D]]` to a Spark `Matrix3D`. */
  implicit def RDD2M3(data: RDD[Cell[Position3D]]): Matrix3D = new Matrix3D(data)
  /** Conversion from `RDD[Cell[Position4D]]` to a Spark `Matrix4D`. */
  implicit def RDD2M4(data: RDD[Cell[Position4D]]): Matrix4D = new Matrix4D(data)
  /** Conversion from `RDD[Cell[Position5D]]` to a Spark `Matrix5D`. */
  implicit def RDD2M5(data: RDD[Cell[Position5D]]): Matrix5D = new Matrix5D(data)
  /** Conversion from `RDD[Cell[Position6D]]` to a Spark `Matrix6D`. */
  implicit def RDD2M6(data: RDD[Cell[Position6D]]): Matrix6D = new Matrix6D(data)
  /** Conversion from `RDD[Cell[Position7D]]` to a Spark `Matrix7D`. */
  implicit def RDD2M7(data: RDD[Cell[Position7D]]): Matrix7D = new Matrix7D(data)
  /** Conversion from `RDD[Cell[Position8D]]` to a Spark `Matrix8D`. */
  implicit def RDD2M8(data: RDD[Cell[Position8D]]): Matrix8D = new Matrix8D(data)
  /** Conversion from `RDD[Cell[Position9D]]` to a Spark `Matrix9D`. */
  implicit def RDD2M9(data: RDD[Cell[Position9D]]): Matrix9D = new Matrix9D(data)

  /** Conversion from `List[Cell[Position1D]]` to a Spark `Matrix1D`. */
  implicit def L2RDDM1(data: List[Cell[Position1D]])(implicit sc: SparkContext): Matrix1D = {
    new Matrix1D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position2D]]` to a Spark `Matrix2D`. */
  implicit def L2RDDM2(data: List[Cell[Position2D]])(implicit sc: SparkContext): Matrix2D = {
    new Matrix2D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position3D]]` to a Spark `Matrix3D`. */
  implicit def L2RDDM3(data: List[Cell[Position3D]])(implicit sc: SparkContext): Matrix3D = {
    new Matrix3D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position4D]]` to a Spark `Matrix4D`. */
  implicit def L2RDDM4(data: List[Cell[Position4D]])(implicit sc: SparkContext): Matrix4D = {
    new Matrix4D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position5D]]` to a Spark `Matrix5D`. */
  implicit def L2RDDM5(data: List[Cell[Position5D]])(implicit sc: SparkContext): Matrix5D = {
    new Matrix5D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position6D]]` to a Spark `Matrix6D`. */
  implicit def L2RDDM6(data: List[Cell[Position6D]])(implicit sc: SparkContext): Matrix6D = {
    new Matrix6D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position7D]]` to a Spark `Matrix7D`. */
  implicit def L2RDDM7(data: List[Cell[Position7D]])(implicit sc: SparkContext): Matrix7D = {
    new Matrix7D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position8D]]` to a Spark `Matrix8D`. */
  implicit def L2RDDM8(data: List[Cell[Position8D]])(implicit sc: SparkContext): Matrix8D = {
    new Matrix8D(sc.parallelize(data))
  }
  /** Conversion from `List[Cell[Position9D]]` to a Spark `Matrix9D`. */
  implicit def L2RDDM9(data: List[Cell[Position9D]])(implicit sc: SparkContext): Matrix9D = {
    new Matrix9D(sc.parallelize(data))
  }

  /** Conversion from `List[(Valueable, Content)]` to a Spark `Matrix1D`. */
  implicit def LV1C2RDDM1[V: Valueable](list: List[(V, Content)])(implicit sc: SparkContext): Matrix1D = {
    new Matrix1D(sc.parallelize(list.map { case (v, c) => Cell(Position1D(v), c) }))
  }
  /** Conversion from `List[(Valueable, Valueable, Content)]` to a Spark `Matrix2D`. */
  implicit def LV2C2RDDM2[V: Valueable, W: Valueable](list: List[(V, W, Content)])(
    implicit sc: SparkContext): Matrix2D = {
    new Matrix2D(sc.parallelize(list.map { case (v, w, c) => Cell(Position2D(v, w), c) }))
  }
  /** Conversion from `List[(Valueable, Valueable, Valueable, Content)]` to a Spark `Matrix3D`. */
  implicit def LV3C2RDDM3[V: Valueable, W: Valueable, X: Valueable](
    list: List[(V, W, X, Content)])(implicit sc: SparkContext): Matrix3D = {
    new Matrix3D(sc.parallelize(list.map { case (v, w, x, c) => Cell(Position3D(v, w, x), c) }))
  }
  /** Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Content)]` to a Spark `Matrix4D`. */
  implicit def LV4C2RDDM4[V: Valueable, W: Valueable, X: Valueable, Y: Valueable](
    list: List[(V, W, X, Y, Content)])(implicit sc: SparkContext): Matrix4D = {
    new Matrix4D(sc.parallelize(list.map { case (v, w, x, y, c) => Cell(Position4D(v, w, x, y), c) }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Content)]` to a Spark `Matrix5D`.
   */
  implicit def LV5C2RDDM5[V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(V, W, X, Y, Z, Content)])(implicit sc: SparkContext): Matrix5D = {
    new Matrix5D(sc.parallelize(list.map { case (v, w, x, y, z, c) => Cell(Position5D(v, w, x, y, z), c) }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Content)]` to a
   * Spark `Matrix6D`.
   */
  implicit def LV6C2RDDM6[U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(U, V, W, X, Y, Z, Content)])(implicit sc: SparkContext): Matrix6D = {
    new Matrix6D(sc.parallelize(list.map { case (u, v, w, x, y, z, c) => Cell(Position6D(u, v, w, x, y, z), c) }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Content)]` to a
   * Spark `Matrix7D`.
   */
  implicit def LV7C2RDDM7[T: Valueable, U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(T, U, V, W, X, Y, Z, Content)])(implicit sc: SparkContext): Matrix7D = {
    new Matrix7D(sc.parallelize(list.map { case (t, u, v, w, x, y, z, c) => Cell(Position7D(t, u, v, w, x, y, z), c) }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable,
   * Content)]` to a Spark `Matrix8D`.
   */
  implicit def LV8C2RDDM8[S: Valueable, T: Valueable, U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(S, T, U, V, W, X, Y, Z, Content)])(implicit sc: SparkContext): Matrix8D = {
    new Matrix8D(sc.parallelize(list.map {
      case (s, t, u, v, w, x, y, z, c) => Cell(Position8D(s, t, u, v, w, x, y, z), c)
    }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable,
   * Valueable, Content)]` to a Spark `Matrix9D`.
   */
  implicit def LV9C2RDDM9[R: Valueable, S: Valueable, T: Valueable, U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(R, S, T, U, V, W, X, Y, Z, Content)])(implicit sc: SparkContext): Matrix9D = {
    new Matrix9D(sc.parallelize(list.map {
      case (r, s, t, u, v, w, x, y, z, c) => Cell(Position9D(r, s, t, u, v, w, x, y, z), c)
    }))
  }

  /** Conversion from matrix with errors tuple to `MatrixWithParseErrors`. */
  implicit def RDD2MWPE[P <: Position](t: (RDD[Cell[P]], RDD[String])): MatrixWithParseErrors[P, RDD] = {
    MatrixWithParseErrors(t._1, t._2)
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position1D]]`.
 *
 * @param data `RDD[Cell[Position1D]]`.
 */
class Matrix1D(val data: RDD[Cell[Position1D]]) extends Matrix[Position1D] with ExpandableMatrix[Position1D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position1D] = names(Over(First))

  /**
   * Persist a `Matrix1D` as sparse matrix file (index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position1D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position1D]] = {
    data
      .keyBy { case c => c.position }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .map { case (_, (c, i)) => i + separator + c.content.value.toShortString }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position2D]]`.
 *
 * @param data `RDD[Cell[Position2D]]`.
 */
class Matrix2D(val data: RDD[Cell[Position2D]]) extends Matrix[Position2D] with ReduceableMatrix[Position2D]
  with ExpandableMatrix[Position2D] with MatrixDistance {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position2D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .map { case (c1, c2) => Position2D(c1, c2) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   */
  def permute[D <: Dimension, F <: Dimension](first: D, second: F)(implicit ev1: PosDimDep[Position2D, D],
    ev2: PosDimDep[Position2D, F], ne: D =!= F): U[Cell[Position2D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second)), c) }
  }

  /**
   * Persist a `Matrix2D` as a CSV file.
   *
   * @param slice       Encapsulates the dimension that makes up the columns.
   * @param file        File to write to.
   * @param separator   Column separator to use.
   * @param escapee     The method for escaping the separator character.
   * @param writeHeader Indicator of the header should be written to a separate file.
   * @param header      Postfix for the header file name.
   * @param writeRowId  Indicator if row names should be written.
   * @param rowId       Column name of row names.
   *
   * @return A `RDD[Cell[Position2D]]`; that is it returns `data`.
   */
  def saveAsCSV(slice: Slice[Position2D], file: String, separator: String = "|", escapee: Escape = Quote("|"),
    writeHeader: Boolean = true, header: String = "%s.header", writeRowId: Boolean = true, rowId: String = "id")(
      implicit ev: ClassTag[slice.S]): U[Cell[Position2D]] = {
    val escape = (str: String) => escapee.escape(str)
    val columns = data
      .map { case c => ((), HashSet(escape(slice.remainder(c.position)(First).toShortString))) }
      .reduceByKey(_ ++ _)
      .map { case (_, set) => set.toList.sorted }

    if (writeHeader) {
      columns
        .map { case lst => (if (writeRowId) escape(rowId) + separator else "") + lst.mkString(separator) }
        .saveAsTextFile(header.format(file))
    }

    val cols = columns
      .first

    data
      .map {
        case Cell(p, c) => (slice.selected(p)(First).toShortString,
          Map(escape(slice.remainder(p)(First).toShortString) -> escape(c.value.toShortString)))
      }
      .reduceByKey(_ ++ _)
      .map {
        case (key, values) => (if (writeRowId) escape(key) + separator else "") +
          cols.map { case c => values.getOrElse(c, "") }.mkString(separator)
      }
      .saveAsTextFile(file)

    data
  }

  /**
   * Persist a `Matrix2D` as sparse matrix file (index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position2D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position2D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => i + separator + j + separator + c.content.value.toShortString }
      .saveAsTextFile(file)

    data
  }

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `RDD[Cell[Position2D]]`; that is it returns `data`.
   */
  def saveAsVW(slice: Slice[Position2D], file: String, dictionary: String = "%s.dict", tag: Boolean = false,
    separator: String = "|")(implicit ev: ClassTag[slice.S]): U[Cell[Position2D]] = {
    saveAsVW(slice, file, None, None, tag, dictionary, separator)
  }

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file with the provided labels.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param file       File to write to.
   * @param labels     The labels.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `RDD[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note The labels are joined to the data keeping only those examples for which data and a label are available.
   */
  def saveAsVWWithLabels(slice: Slice[Position2D], file: String, labels: U[Cell[Position1D]],
    dictionary: String = "%s.dict", tag: Boolean = false, separator: String = "|")(
      implicit ev: ClassTag[slice.S]): U[Cell[Position2D]] = {
    saveAsVW(slice, file, Some(labels), None, tag, dictionary, separator)
  }

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file with the provided importance weights.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param file       File to write to.
   * @param importance The importance weights.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `RDD[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note The weights are joined to the data keeping only those examples for which data and a weight are available.
   */
  def saveAsVWWithImportance(slice: Slice[Position2D], file: String, importance: U[Cell[Position1D]],
    dictionary: String = "%s.dict", tag: Boolean = false, separator: String = "|")(
      implicit ev: ClassTag[slice.S]): U[Cell[Position2D]] = {
    saveAsVW(slice, file, None, Some(importance), tag, dictionary, separator)
  }

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file with the provided labels and importance weights.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param file       File to write to.
   * @param labels     The labels.
   * @param importance The importance weights.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `RDD[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note The labels and weights are joined to the data keeping only those examples for which data and a label
   *       and weight are available.
   */
  def saveAsVWWithLabelsAndImportance(slice: Slice[Position2D], file: String, labels: U[Cell[Position1D]],
    importance: U[Cell[Position1D]], dictionary: String = "%s.dict", tag: Boolean = false, separator: String = "|")(
      implicit ev: ClassTag[slice.S]): U[Cell[Position2D]] = {
    saveAsVW(slice, file, Some(labels), Some(importance), tag, dictionary, separator)
  }

  private def saveAsVW(slice: Slice[Position2D], file: String, labels: Option[U[Cell[Position1D]]],
    importance: Option[U[Cell[Position1D]]], tag: Boolean, dictionary: String, separator: String)(
      implicit ev: ClassTag[slice.S]): U[Cell[Position2D]] = {
    val dict = data
      .map { c => slice.remainder(c.position)(First).toShortString }
      .distinct
      .zipWithIndex

    dict
      .map { case (s, i) => s + separator + i }
      .saveAsTextFile(dictionary.format(file))

    val features = data
      .keyBy { c => slice.remainder(c.position)(First).toShortString }
      .join(dict)
      .flatMap {
        case (_, (c, i)) => c.content.value.asDouble.map { case v => (slice.selected(c.position), (i, v)) }
      }
      .groupByKey
      .map {
        case (p, itr) => (p, itr
          .toList
          .sortBy { case (i, _) => i }
          .foldLeft("|") { case (b, (i, v)) => b + " " + i + ":" + v })
      }

    val tagged = tag match {
      case true => features.map { case (p, s) => (p, p(First).toShortString + s) }
      case false => features
    }

    val weighted = importance match {
      case Some(imp) => tagged
        .join(imp.keyBy { case c => c.position.asInstanceOf[slice.S] })
        .flatMap { case (p, (s, c)) => c.content.value.asDouble.map { case i => (p, i + " " + s) } }
      case None => tagged
    }

    val examples = labels match {
      case Some(lab) => weighted
        .join(lab.keyBy { case c => c.position.asInstanceOf[slice.S] })
        .flatMap { case (p, (s, c)) => c.content.value.asDouble.map { case l => (p, l + " " + s) } }
      case None => weighted
    }

    examples
      .map { case (p, s) => s }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position3D]]`.
 *
 * @param data `RDD[Cell[Position3D]]`.
 */
class Matrix3D(val data: RDD[Cell[Position3D]]) extends Matrix[Position3D] with ReduceableMatrix[Position3D]
  with ExpandableMatrix[Position3D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position3D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .cartesian(names(Over(Third)).map { case Position1D(c) => c })
      .map { case ((c1, c2), c3) => Position3D(c1, c2, c3) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension](first: D, second: F, third: G)(
    implicit ev1: PosDimDep[Position3D, D], ev2: PosDimDep[Position3D, F], ev3: PosDimDep[Position3D, G],
      ev4: Distinct3[D, F, G]): U[Cell[Position3D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third)), c) }
  }

  /**
   * Persist a `Matrix3D` as sparse matrix file (index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position3D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position3D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .keyBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(Over(Third), file, dictionary, separator))
      .map { case (_, ((c, i, j), k)) => i + separator + j + separator + k + separator + c.content.value.toShortString }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position4D]]`.
 *
 * @param data `RDD[Cell[Position4D]]`.
 */
class Matrix4D(val data: RDD[Cell[Position4D]]) extends Matrix[Position4D] with ReduceableMatrix[Position4D]
  with ExpandableMatrix[Position4D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position4D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .cartesian(names(Over(Third)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fourth)).map { case Position1D(c) => c })
      .map { case (((c1, c2), c3), c4) => Position4D(c1, c2, c3, c4) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   * @param fourth Dimension used for the fourth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension](first: D, second: F, third: G,
    fourth: H)(implicit ev1: PosDimDep[Position4D, D], ev2: PosDimDep[Position4D, F], ev3: PosDimDep[Position4D, G],
      ev4: PosDimDep[Position4D, H], ev5: Distinct4[D, F, G, H]): U[Cell[Position4D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth)), c) }
  }

  /**
   * Persist a `Matrix4D` as sparse matrix file (index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position4D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position4D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .keyBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(Over(Third), file, dictionary, separator))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .keyBy { case (c, i, j, p) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(Over(Fourth), file, dictionary, separator))
      .map {
        case (_, ((c, i, j, k), l)) =>
          i + separator + j + separator + k + separator + l + separator + c.content.value.toShortString
      }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position5D]]`.
 *
 * @param data `RDD[Cell[Position5D]]`.
 */
class Matrix5D(val data: RDD[Cell[Position5D]]) extends Matrix[Position5D] with ReduceableMatrix[Position5D]
  with ExpandableMatrix[Position5D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position5D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .cartesian(names(Over(Third)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fourth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fifth)).map { case Position1D(c) => c })
      .map { case ((((c1, c2), c3), c4), c5) => Position5D(c1, c2, c3, c4, c5) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   * @param fourth Dimension used for the fourth coordinate.
   * @param fifth  Dimension used for the fifth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension](first: D, second: F,
    third: G, fourth: H, fifth: I)(implicit ev1: PosDimDep[Position5D, D], ev2: PosDimDep[Position5D, F],
      ev3: PosDimDep[Position5D, G], ev4: PosDimDep[Position5D, H], ev5: PosDimDep[Position5D, I],
        ev6: Distinct5[D, F, G, H, I]): U[Cell[Position5D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth)), c) }
  }

  /**
   * Persist a `Matrix5D` as sparse matrix file (index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position5D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position5D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .keyBy { case (c, pi, pj) => Position1D(c.position(Third)) }
      .join(saveDictionary(Over(Third), file, dictionary, separator))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .keyBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(Over(Fourth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .keyBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(Over(Fifth), file, dictionary, separator))
      .map {
        case (_, ((c, i, j, k, l), m)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator + c.content.value.toShortString
      }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position6D]]`.
 *
 * @param data `RDD[Cell[Position6D]]`.
 */
class Matrix6D(val data: RDD[Cell[Position6D]]) extends Matrix[Position6D] with ReduceableMatrix[Position6D]
  with ExpandableMatrix[Position6D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position6D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .cartesian(names(Over(Third)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fourth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fifth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Sixth)).map { case Position1D(c) => c })
      .map { case (((((c1, c2), c3), c4), c5), c6) => Position6D(c1, c2, c3, c4, c5, c6) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   * @param fourth Dimension used for the fourth coordinate.
   * @param fifth  Dimension used for the fifth coordinate.
   * @param sixth  Dimension used for the sixth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension](first: D,
    second: F, third: G, fourth: H, fifth: I, sixth: J)(implicit ev1: PosDimDep[Position6D, D],
      ev2: PosDimDep[Position6D, F], ev3: PosDimDep[Position6D, G], ev4: PosDimDep[Position6D, H],
        ev5: PosDimDep[Position6D, I], ev6: PosDimDep[Position6D, J],
          ev7: Distinct6[D, F, G, H, I, J]): U[Cell[Position6D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth)), c) }
  }

  /**
   * Persist a `Matrix6D` as sparse matrix file (index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position6D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position6D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .keyBy { case (c, pi, pj) => Position1D(c.position(Third)) }
      .join(saveDictionary(Over(Third), file, dictionary, separator))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .keyBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(Over(Fourth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .keyBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(Over(Fifth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .keyBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(Over(Sixth), file, dictionary, separator))
      .map {
        case (_, ((c, i, j, k, l, m), n)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + c.content.value.toShortString
      }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position7D]]`.
 *
 * @param data `RDD[Cell[Position7D]]`.
 */
class Matrix7D(val data: RDD[Cell[Position7D]]) extends Matrix[Position7D] with ReduceableMatrix[Position7D]
  with ExpandableMatrix[Position7D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position7D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .cartesian(names(Over(Third)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fourth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fifth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Sixth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Seventh)).map { case Position1D(c) => c })
      .map { case ((((((c1, c2), c3), c4), c5), c6), c7) => Position7D(c1, c2, c3, c4, c5, c6, c7) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first   Dimension used for the first coordinate.
   * @param second  Dimension used for the second coordinate.
   * @param third   Dimension used for the third coordinate.
   * @param fourth  Dimension used for the fourth coordinate.
   * @param fifth   Dimension used for the fifth coordinate.
   * @param sixth   Dimension used for the sixth coordinate.
   * @param seventh Dimension used for the seventh coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension, K <: Dimension](
    first: D, second: F, third: G, fourth: H, fifth: I, sixth: J, seventh: K)(implicit ev1: PosDimDep[Position7D, D],
      ev2: PosDimDep[Position7D, F], ev3: PosDimDep[Position7D, G], ev4: PosDimDep[Position7D, H],
        ev5: PosDimDep[Position7D, I], ev6: PosDimDep[Position7D, J], ev7: PosDimDep[Position7D, K],
          ev8: Distinct7[D, F, G, H, I, J, K]): U[Cell[Position7D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth, seventh)), c) }
  }

  /**
   * Persist a `Matrix7D` as sparse matrix file (index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position7D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position7D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .keyBy { case (c, pi, pj) => Position1D(c.position(Third)) }
      .join(saveDictionary(Over(Third), file, dictionary, separator))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .keyBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(Over(Fourth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .keyBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(Over(Fifth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .keyBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(Over(Sixth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l, m), n)) => (c, i, j, k, l, m, n) }
      .keyBy { case (c, i, j, k, l, m, n) => Position1D(c.position(Seventh)) }
      .join(saveDictionary(Over(Seventh), file, dictionary, separator))
      .map {
        case (_, ((c, i, j, k, l, m, n), o)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + o + separator + c.content.value.toShortString
      }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position8D]]`.
 *
 * @param data `RDD[Cell[Position8D]]`.
 */
class Matrix8D(val data: RDD[Cell[Position8D]]) extends Matrix[Position8D] with ReduceableMatrix[Position8D]
  with ExpandableMatrix[Position8D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position8D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .cartesian(names(Over(Third)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fourth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fifth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Sixth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Seventh)).map { case Position1D(c) => c })
      .cartesian(names(Over(Eighth)).map { case Position1D(c) => c })
      .map { case (((((((c1, c2), c3), c4), c5), c6), c7), c8) => Position8D(c1, c2, c3, c4, c5, c6, c7, c8) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first   Dimension used for the first coordinate.
   * @param second  Dimension used for the second coordinate.
   * @param third   Dimension used for the third coordinate.
   * @param fourth  Dimension used for the fourth coordinate.
   * @param fifth   Dimension used for the fifth coordinate.
   * @param sixth   Dimension used for the sixth coordinate.
   * @param seventh Dimension used for the seventh coordinate.
   * @param eighth  Dimension used for the eighth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension, K <: Dimension, L <: Dimension](
    first: D, second: F, third: G, fourth: H, fifth: I, sixth: J, seventh: K, eighth: L)(
      implicit ev1: PosDimDep[Position8D, D], ev2: PosDimDep[Position8D, F], ev3: PosDimDep[Position8D, G],
        ev4: PosDimDep[Position8D, H], ev5: PosDimDep[Position8D, I], ev6: PosDimDep[Position8D, J],
          ev7: PosDimDep[Position8D, K], ev8: PosDimDep[Position8D, L],
            ev9: Distinct8[D, F, G, H, I, J, K, L]): U[Cell[Position8D]] = {
    data.map {
      case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth, seventh, eighth)), c)
    }
  }

  /**
   * Persist a `Matrix8D` as sparse matrix file (index, index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position8D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position8D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .keyBy { case (c, pi, pj) => Position1D(c.position(Third)) }
      .join(saveDictionary(Over(Third), file, dictionary, separator))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .keyBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(Over(Fourth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .keyBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(Over(Fifth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .keyBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(Over(Sixth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l, m), n)) => (c, i, j, k, l, m, n) }
      .keyBy { case (c, i, j, k, l, m, n) => Position1D(c.position(Seventh)) }
      .join(saveDictionary(Over(Seventh), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l, m, n), o)) => (c, i, j, k, l, m, n, o) }
      .keyBy { case (c, i, j, k, l, m, n, o) => Position1D(c.position(Eighth)) }
      .join(saveDictionary(Over(Eighth), file, dictionary, separator))
      .map {
        case (_, ((c, i, j, k, l, m, n, o), p)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + o + separator + p + separator + c.content.value.toShortString
      }
      .saveAsTextFile(file)

    data
  }
}

/**
 * Rich wrapper around a `RDD[Cell[Position9D]]`.
 *
 * @param data `RDD[Cell[Position9D]]`.
 */
class Matrix9D(val data: RDD[Cell[Position9D]]) extends Matrix[Position9D] with ReduceableMatrix[Position9D] {
  def domain[T <: Tuner](tuner: T = Default())(implicit ev: DomainTuners#V[T]): U[Position9D] = {
    names(Over(First))
      .map { case Position1D(c) => c }
      .cartesian(names(Over(Second)).map { case Position1D(c) => c })
      .cartesian(names(Over(Third)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fourth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Fifth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Sixth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Seventh)).map { case Position1D(c) => c })
      .cartesian(names(Over(Eighth)).map { case Position1D(c) => c })
      .cartesian(names(Over(Ninth)).map { case Position1D(c) => c })
      .map { case ((((((((c1, c2), c3), c4), c5), c6), c7), c8), c9) => Position9D(c1, c2, c3, c4, c5, c6, c7, c8, c9) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first   Dimension used for the first coordinate.
   * @param second  Dimension used for the second coordinate.
   * @param third   Dimension used for the third coordinate.
   * @param fourth  Dimension used for the fourth coordinate.
   * @param fifth   Dimension used for the fifth coordinate.
   * @param sixth   Dimension used for the sixth coordinate.
   * @param seventh Dimension used for the seventh coordinate.
   * @param eighth  Dimension used for the eighth coordinate.
   * @param ninth   Dimension used for the ninth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension, K <: Dimension, L <: Dimension, M <: Dimension](
    first: D, second: F, third: G, fourth: H, fifth: I, sixth: J, seventh: K, eighth: L, ninth: M)(
      implicit ev1: PosDimDep[Position9D, D], ev2: PosDimDep[Position9D, F], ev3: PosDimDep[Position9D, G],
        ev4: PosDimDep[Position9D, H], ev5: PosDimDep[Position9D, I], ev6: PosDimDep[Position9D, J],
          ev7: PosDimDep[Position9D, K], ev8: PosDimDep[Position9D, L], ev9: PosDimDep[Position9D, M],
            ev10: Distinct9[D, F, G, H, I, J, K, L, M]): U[Cell[Position9D]] = {
    data.map {
      case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth, seventh, eighth, ninth)), c)
    }
  }

  /**
   * Persist a `Matrix9D` as sparse matrix file (index, index, index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `RDD[Cell[Position9D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|"): U[Cell[Position9D]] = {
    data
      .keyBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(Over(First), file, dictionary, separator))
      .values
      .keyBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(Over(Second), file, dictionary, separator))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .keyBy { case (c, pi, pj) => Position1D(c.position(Third)) }
      .join(saveDictionary(Over(Third), file, dictionary, separator))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .keyBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(Over(Fourth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .keyBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(Over(Fifth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .keyBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(Over(Sixth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l, m), n)) => (c, i, j, k, l, m, n) }
      .keyBy { case (c, i, j, k, l, m, n) => Position1D(c.position(Seventh)) }
      .join(saveDictionary(Over(Seventh), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l, m, n), o)) => (c, i, j, k, l, m, n, o) }
      .keyBy { case (c, i, j, k, l, m, n, o) => Position1D(c.position(Eighth)) }
      .join(saveDictionary(Over(Eighth), file, dictionary, separator))
      .map { case (_, ((c, i, j, k, l, m, n, o), p)) => (c, i, j, k, l, m, n, o, p) }
      .keyBy { case (c, i, j, k, l, m, n, o, p) => Position1D(c.position(Ninth)) }
      .join(saveDictionary(Over(Ninth), file, dictionary, separator))
      .map {
        case (_, ((c, i, j, k, l, m, n, o, p), q)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + o + separator + p + separator + q + separator + c.content.value.toShortString
      }
      .saveAsTextFile(file)

    data
  }
}

/** Spark companion object for the `Matrixable` type class. */
object Matrixable {
  /** Converts a `RDD[Cell[P]]` into a `RDD[Cell[P]]`; that is, it is a  pass through. */
  implicit def RDDC2RDDM[P <: Position]: BaseMatrixable[RDD[Cell[P]], P, RDD] = {
    new BaseMatrixable[RDD[Cell[P]], P, RDD] { def convert(t: RDD[Cell[P]]): RDD[Cell[P]] = t }
  }

  /** Converts a `List[Cell[P]]` into a `RDD[Cell[P]]`. */
  implicit def LC2RDDM[P <: Position](implicit sc: SparkContext,
    ct: ClassTag[P]): BaseMatrixable[List[Cell[P]], P, RDD] = {
    new BaseMatrixable[List[Cell[P]], P, RDD] { def convert(t: List[Cell[P]]): RDD[Cell[P]] = sc.parallelize(t) }
  }

  /** Converts a `Cell[P]` into a `RDD[Cell[P]]`. */
  implicit def C2RDDM[P <: Position](implicit sc: SparkContext, ct: ClassTag[P]): BaseMatrixable[Cell[P], P, RDD] = {
    new BaseMatrixable[Cell[P], P, RDD] { def convert(t: Cell[P]): RDD[Cell[P]] = sc.parallelize(List(t)) }
  }
}

/** Spark companion object for the `Predicateable` type class. */
object Predicateable {
  /**
   * Converts a `List[(PositionDistributable[I, S, U], Matrix.Predicate[P])]` to a
   * `List[(U[S], BaseMatrix.Predicate[P])]`.
   */
  implicit def PDPT2LTPP[I, P <: Position, S <: Position](implicit ev: PositionDistributable[I, S, RDD]): BasePredicateable[(I, BaseMatrix.Predicate[P]), P, S, RDD] = {
    new BasePredicateable[(I, BaseMatrix.Predicate[P]), P, S, RDD] {
      def convert(t: (I, BaseMatrix.Predicate[P])): List[(RDD[S], BaseMatrix.Predicate[P])] = {
        List((ev.convert(t._1), t._2))
      }
    }
  }

  /**
   * Converts a `(PositionDistributable[I, S, U], Matrix.Predicate[P])` to a `List[(U[S], BaseMatrix.Predicate[P])]`.
   */
  implicit def LPDP2LTPP[I, P <: Position, S <: Position](implicit ev: PositionDistributable[I, S, RDD]): BasePredicateable[List[(I, BaseMatrix.Predicate[P])], P, S, RDD] = {
    new BasePredicateable[List[(I, BaseMatrix.Predicate[P])], P, S, RDD] {
      def convert(t: List[(I, BaseMatrix.Predicate[P])]): List[(RDD[S], BaseMatrix.Predicate[P])] = {
        t.map { case (i, p) => (ev.convert(i), p) }
      }
    }
  }
}
