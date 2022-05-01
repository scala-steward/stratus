/*
 * Copyright 2022 Arman Bilge
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

package stratus

import algebra.ring.Rig
import algebra.ring.Semifield
import cats.Monad
import cats.data.OptionT
import cats.data.StateT
import cats.instances.stream
import cats.kernel.Eq
import cats.kernel.Order
import cats.syntax.all.*
import schrodinger.kernel.Categorical
import schrodinger.kernel.Uniform
import schrodinger.kernel.UniformRange
import schrodinger.math.Monus
import schrodinger.math.syntax.*
import schrodinger.montecarlo.Weighted

trait Resampler[F[_], W, A]:
  def resample(eagle: Eagle[W]): StateT[F, Vector[Weighted[W, A]], Option[Weighted[W, A]]]

object Resampler:
  def identity[F[_]: Monad: UniformRange, W, A]: Resampler[F, W, A] = eagle =>
    StateT
      .get
      .flatMapF((v: Vector[Weighted[W, A]]) => Uniform(v.indices))
      .flatMap(pop)
      .map(Some(_))

  def targetMeanWeight[F[_]: Monad: UniformRange, W: Semifield: Monus: Order, A](
      using Categorical[List[(Option[Weighted[W, A]], W)], Option[Weighted[W, A]]][F]
  ): Resampler[F, W, A] = targetWeight(_.meanWeight.pure)

  def targetWeight[F[_]: Monad: UniformRange, W: Monus: Order, A](
      computeTarget: Eagle[W] => F[W])(
      using W: Semifield[W],
      cat: Categorical[List[(Option[Weighted[W, A]], W)], Option[Weighted[W, A]]][F])
      : Resampler[F, W, A] = eagle =>
    StateT.liftF(computeTarget(eagle)).flatMap { target =>
      if W.isZero(target) then none.pure
      else
        Monad[StateT[F, Vector[Weighted[W, A]], _]]
          .tailRecM((List.empty[(Some[Weighted[W, A]], W)], W.zero)) { (chosen, sum) =>
            StateT
              .inspect[F, Vector[Weighted[W, A]], Boolean](_.nonEmpty & sum < target)
              .ifM(
                StateT
                  .inspect[F, Vector[Weighted[W, A]], Range](_.indices)
                  .flatMapF(Uniform(_))
                  .flatMap(pop(_))
                  .flatMap { sample =>
                    val newSum = sum + sample.weight
                    if newSum < target then
                      (Some(sample) -> sample.weight :: chosen, newSum).asLeft.pure
                    else
                      val (choose, rtn) = split(sample, target ∸ newSum)
                      StateT
                        .modify[F, Vector[Weighted[W, A]]](_.appended(rtn))
                        .as((Some(sample) -> sample.weight :: chosen, target).asRight)
                  },
                (chosen, sum).asRight.pure
              )
          }
          .flatMapF { (chosen, sum) =>
            if chosen.isEmpty then none.pure
            else
              val samples = (none -> (target ∸ sum)) :: chosen
              val sampled = OptionT(Categorical(samples)).map {
                case Weighted.Heavy(_, density, a) => Weighted.Heavy(target, density, a)
                case weightless => weightless
              }
              sampled.value
          }
    }

  // wp <= wa.weight
  private[stratus] def split[W: Rig: Monus: Eq, A](
      wa: Weighted[W, A],
      wp: W
  ): (Weighted[W, A], Weighted[W, A]) = wa match
    case Weighted.Heavy(w, d, a) => (Weighted(wp, d, a), Weighted(w ∸ wp, d, a))
    case weightless @ Weighted.Weightless(_) => (weightless, weightless)

  private[stratus] def pop[F[_]: Monad, A](i: Int): StateT[F, Vector[A], A] =
    for
      v <- StateT.get
      _ <- StateT.set {
        if i < v.length - 1 then v.init.updated(i, v.last)
        else v.init
      }
    yield v(i)
