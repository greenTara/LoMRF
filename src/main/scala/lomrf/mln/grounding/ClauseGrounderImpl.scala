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

package lomrf.mln.grounding

import java.{util => jutil}

import akka.actor.ActorRef
import auxlib.log.Logging
import gnu.trove.set.TIntSet
import lomrf.logic._
import lomrf.mln.model.MLN
import lomrf.util.AtomIdentityFunction.IDENTITY_NOT_EXIST
import lomrf.util.{AtomIdentityFunction, Cartesian}

import scala.collection._
import scala.language.postfixOps
import scalaxy.loops._

/**
 * @author Anastasios Skarlatidis
 */
class ClauseGrounderImpl(
                          val clause: Clause,
                          clauseIndex: Int,
                          mln: MLN,
                          cliqueRegisters: Array[ActorRef],
                          atomSignatures: Set[AtomSignature],
                          atomsDB: Array[TIntSet],
                          noNegWeights: Boolean = false,
                          eliminateNegatedUnit: Boolean = false) extends ClauseGrounder with Logging{

  require(!clause.weight.isNaN, "Found a clause with not a valid weight value (NaN).")

  private val cliqueBatches = cliqueRegisters.length
  private val atomsDBBatches = atomsDB.length

  private val variableDomains: Map[Variable, Iterable[String]] = {
    if (clause.isGround) Map.empty[Variable, Iterable[String]]
    else (for (v <- clause.variables) yield v -> mln.constants(v.domain))(breakOut)
  }

  private val groundIterator =
    try {
      Cartesian.CartesianIterator(variableDomains)
    } catch {
      case ex: NoSuchElementException =>
        fatal("Failed to initialise CartesianIterator for clause: " +
          clause.toString + " --- domain = " +
          variableDomains)
    }


  private val identities: Map[AtomSignature, AtomIdentityFunction] =
    (for (literal <- clause.literals if !mln.isDynamicAtom(literal.sentence.signature))
    yield literal.sentence.signature -> mln.identityFunctions(literal.sentence.signature))(breakOut)



  private val orderedLiterals =
    clause.literals.view.map(lit =>
      (lit, identities.getOrElse(lit.sentence.signature, null))).toArray.sortBy(entry => entry._1)(ClauseLiteralsOrdering(mln))

  private val owaLiterals = orderedLiterals.view.map(_._1).filter(literal => mln.isTriState(literal.sentence.signature))

  // Collect dynamic atoms
  private val dynamicAtoms: Map[Int, (Vector[String] => Boolean)] =
    (for (i <- 0 until orderedLiterals.length; sentence = orderedLiterals(i)._1.sentence; if sentence.isDynamic)
    yield i -> mln.dynamicPredicates(sentence.signature))(breakOut)


  private val length = clause.literals.count(l => mln.isTriState(l.sentence.signature))

  def collectedSignatures = clause.literals.map(_.sentence.signature) -- atomSignatures

  def getVariableDomains = variableDomains

  def computeGroundings() {

    debug("The ordering of literals in clause: " + clause + "\n\t" +
      "changed to: " + orderedLiterals.map(_.toString()).reduceLeft(_ + " v " + _))


    def performGrounding(theta: Map[Variable, String] = Map.empty[Variable, String]): Int = {
      var sat = 0
      var counter = 0

      // an array of integer literals, indicating the current ground clause's literals
      val currentVariables = new Array[Int](length)

      // partial function for substituting terms w.r.t the given theta
      val substitution = substituteTerm(theta) _
      var idx = 0 //literal position index in the currentVariables array
      var flagDrop = false //utility flag to indicate whether to keep or not the current ground clause
      val literalsIterator = orderedLiterals.iterator // literals iterator, that gives first all evidence literals

      while (!flagDrop && literalsIterator.hasNext) {
        val (literal, idf) = literalsIterator.next()
        // When the literal is a dynamic atom, then invoke its truth state dynamically
        if (literal.sentence.isDynamic) {
          //if (literal.isPositive == dynamicAtoms(idx)(literal.sentence.terms.map(substitution))) flagDrop = true
          flagDrop = literal.isPositive == dynamicAtoms(idx)(literal.sentence.terms.map(substitution))
        }
        else {
          // Otherwise, invoke its state from the evidence
          val atomID = idf.encode(literal.sentence, substitution)

          if (atomID == IDENTITY_NOT_EXIST) {
            // Due to closed-world assumption in the evidence atoms or in the function mappings,
            // the identity of the atom cannot be determined and in that case the current clause grounding
            // will be omitted from the MRF
            flagDrop = true
          } else {
            // Otherwise, the atomID has a valid id number and the following pattern matching procedure
            // investigates whether the current literal satisfies the ground clause. If it does, the clause
            // is omitted from the MRF, since it is always satisfied from that literal.
            val state = mln.atomStateDB(literal.sentence.signature).get(atomID).value
            if ((literal.isNegative && (state == FALSE.value)) || (literal.isPositive && (state == TRUE.value))) {
              // the clause is always satisfied from that literal
              sat += 1
              flagDrop = true //we don't need to keep that ground clause
            }
            else if (state == UNKNOWN.value) {
              // The state of the literal is unknown, thus the literal will be stored to the currentVariables
              currentVariables(idx) = atomID
              idx += 1
            }
          }
        }
      } //end:  while (literalsIterator.hasNext && !flagDrop)

      if (!flagDrop) {
        // So far the ground clause is produced, but we have to
        // examine whether we will keep it or not. If the
        // ground clause contains any literal that is included in the
        // atomsDB, then it will be stored (and later will be send to clique registers),
        // otherwise it will not be stored and omitted.

        var canSend = false //utility flag

        var owaIdx = 0
        val cliqueVariables = new Array[Int](idx)

        for (i <- (0 until idx).optimized) {
          //val currentLiteral = iterator.next()
          val currentAtomID = currentVariables(i)
          cliqueVariables(i) = if (owaLiterals(owaIdx).isPositive) currentAtomID else -currentAtomID

          // Examine whether the current literal is included to the atomsDB. If it isn't,
          // the current clause will be omitted from the MRF
          val atomsDBSegment = atomsDB(currentAtomID % atomsDBBatches)
          if (!canSend && (atomsDBSegment ne null)) canSend = atomsDBSegment.contains(currentAtomID)
          else if (atomsDBSegment eq null) canSend = true // this case happens only for Query literals

          owaIdx += 1
        }

        if (canSend) {
          // Finally, the current ground clause will be included in the MRF.
          // However, if the weight of the clause is a negative number, then
          // the ground clause will be negated and broke up into several
          // unit ground clauses with positive weight literals.

          if (noNegWeights && clause.weight < 0) {
            if (cliqueVariables.length == 1) {
              // If the clause is unit and its weight value is negative
              // negate this clause (e.g. the clause "-w A" will be converted into w !A)
              cliqueVariables(0) = -cliqueVariables(0)
              store(-clause.weight, cliqueVariables, -1)
              counter += 1
            } else {
              val posWeight = -clause.weight / cliqueVariables.length
              for (groundLiteral <- cliqueVariables) {
                store(posWeight, Array(-groundLiteral), -1)
                counter += 1
              }
            }
          }
          else {
            if (cliqueVariables.length > 1) {
              jutil.Arrays.sort(cliqueVariables)
              store(clause.weight, cliqueVariables, 1)
            }
            else if(eliminateNegatedUnit && cliqueVariables.length == 1 && cliqueVariables(0) < 0){
              cliqueVariables(0) = -cliqueVariables(0)
              store(-clause.weight, cliqueVariables, -1)
            }

            
            counter += 1
          }
          counter = 1
        } // end: if (canSend)
      }

      counter
    }

     if (clause.isGround) performGrounding()
    else while (groundIterator.hasNext) performGrounding(theta = groundIterator.next())

  }

  private def substituteTerm(theta: collection.Map[Variable, String])(term: Term): String = term match {
    case c: Constant => c.symbol
    case v: Variable => theta(v)
    case f: TermFunction =>
      mln.functionMappers.get(f.signature) match {
        case Some(m) => m(f.terms.map(a => substituteTerm(theta)(a)))
        case None => fatal("Cannot apply substitution using theta: " + theta + " in function " + f.signature)
      }
  }

  /**
   *
   * @param weight the clause weight
   * @param variables the ground clause literals (where negative values indicate negated atoms)
   * @param freq: -1 when the clause weight is been inverted, +1 otherwise.
   */
  private def store(weight: Double, variables: Array[Int], freq: Int) {
    var hashKey = jutil.Arrays.hashCode(variables)
    if (hashKey == 0) hashKey += 1 //required for trove collections, since zero represents the key-not-found value

    cliqueRegisters(math.abs(hashKey % cliqueBatches)) ! CliqueEntry(hashKey, weight, variables, clauseIndex, freq )
  }

}