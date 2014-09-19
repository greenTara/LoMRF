/*
 * o                        o     o   o         o
 * |             o          |     |\ /|         | /
 * |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
 * |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
 * O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
 *             |
 *          o--o
 * o--o              o               o--o       o    o
 * |   |             |               |    o     |    |
 * O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
 * |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
 * o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o
 *
 * Logical Markov Random Fields.
 *
 * Copyright (C) 2012  Anastasios Skarlatidis.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package lomrf.mln.model.mrf

/**
 * This class contains the information of a ground atom in the generated MRF.
 *
 * @param id the atom id in the generated MRF
 * @param weightHard the weight of a hard-constrained clause
 */
final class GroundAtom(val id: Int, weightHard: Double) {

  // ----------------------------------------------------------------
  // Mutable information: accessible only from classes of inference package.
  // ----------------------------------------------------------------

  // Keeps track the last time that this atom is flipped
  private[mln] var lastFlip = 0

  // Determines whether the state is fixed to some value (1 = true, -1 false) or not (=0)
  private[mln] var fixedValue = 0

  // The current truth state of this particular ground atom
  private[mln] var state = false

  /**
   * The truth state of this particular ground atom in a
   * previously generated world with the lowest cost so far.
   */
  private[mln] var lowState = false

  // Keep track how many times this atom had true state
  private[mln] var truesCounter = 0

  // The cost that will increase after flipping
  private[mln] var brakeCost = 0.0

  // The cost that will decrease after flipping
  private[mln] var makeCost = 0.0

  // ----------------------------------------------------------------
  // Publicly accessible functions
  // ----------------------------------------------------------------

  def isFixed: Boolean = fixedValue != 0

  /**
   * @return true if the current state is true; otherwise false
   */
  def getState: Boolean = state

  /**
   * @return true when it breaks at least one hard-constrained clause
   */
  def breaksHardConstraint = brakeCost >= weightHard // TODO In mode sampleSAT is problematic

  /**
   * This atom is either fixed or it will break at least one hard-constraint if it is flipped
   */
  def isCritical: Boolean = (fixedValue != 0) || (brakeCost >= weightHard)

  /**
   * @return the cost when this atom is flipped.
   */
  def delta = brakeCost - makeCost

  /**
   * @return the number of times that this atom have been appeared as true in the generated samples
   */
  def getTruesCount: Int = truesCounter

  override def hashCode() = id

  override def equals(obj: Any) =
    if (obj.isInstanceOf[GroundAtom]) obj.hashCode() == id else false


  // ----------------------------------------------------------------
  // Functions that are accessible only from classes of model package.
  // ----------------------------------------------------------------

  /**
   * Flips the state of this atom, swaps make cost with brake cost and returns the delta cost
   *
   * @return delta cost
   */
  private[mln] def flip: Double = {
    state = !state
    // invert delta:
    val tmp = brakeCost
    brakeCost = makeCost
    makeCost = tmp

    //its the invert of "delta", because the make cost and brake costs are swapped
    makeCost - brakeCost
  }

  private[mln] def saveAsLowState() {
    lowState = state
  }

  private[mln] def restoreLowState() {
    state = lowState
  }

  private[mln] def resetDelta() {
    brakeCost = 0.0
    makeCost = 0.0
  }

  /**
   * The given constraint will become satisfied when this atom is flipped (UNSAT -> SAT).
   */
  private[mln] def assignSatPotential(constraint: Constraint) {
    if(constraint.mode == 0) {
      if (constraint.isPositive) makeCost += constraint.weight
      else brakeCost -= constraint.weight
    }
    else {
      val v = if(constraint.weight > 0) 1 else -1
      if (constraint.isPositive) makeCost += v
      else brakeCost -= v
    }
  }

  /**
   * The given constraint will become unsatisfied when this atom is flipped (SAT -> UNSAT).
   */
  private[mln] def assignUnsatPotential(constraint: Constraint) {
    if(constraint.mode == 0) {
      if (constraint.isPositive) brakeCost += constraint.weight
      else makeCost -= constraint.weight
    }
    else {
      val v = if(constraint.weight > 0) 1 else -1
      if (constraint.isPositive) brakeCost += v
      else makeCost -= v
    }
  }

  /**
   * The given constraint will no longer becomes unsatisfied when this atom is flipped.
   */
  private[mln] def revokeSatPotential(constraint: Constraint) {
    if(constraint.mode == 0) {
      if (constraint.isPositive) makeCost -= constraint.weight
      else brakeCost += constraint.weight
    }
    else {
      val v = if(constraint.weight > 0) 1 else -1
      if (constraint.isPositive) makeCost -= v
      else brakeCost += v
    }
  }

  /**
   * The given constraint will no longer becomes satisfied when this atom is flipped.
   */
  private[mln] def revokeUnsatPotential(constraint: Constraint) {
    if(constraint.mode == 0) {
      if (constraint.isPositive) brakeCost -= constraint.weight
      else brakeCost += constraint.weight
    }
    else {
      val v = if(constraint.weight > 0) 1 else -1
      if (constraint.isPositive) brakeCost -= v
      else brakeCost += v
    }
  }

}
