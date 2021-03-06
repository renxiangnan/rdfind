package de.hpi.isg.sodap.rdfind.operators.candidate_extraction

import com.google.common.hash.BloomFilter
import de.hpi.isg.sodap.rdfind.data.{CindSet, Condition, JoinLine}
import de.hpi.isg.sodap.rdfind.operators.CreateDependencyCandidates
import de.hpi.isg.sodap.rdfind.util.ConditionCodes._
import org.apache.flink.configuration.Configuration
import org.apache.flink.util.Collector

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * @author sebastian.kruse 
 * @since 01.06.2015
 */
class CreateAllCindCandidates(isUseFrequentConditionsFilter: Boolean = false,
                              isUseAssociationRules: Boolean = false,
                              splitStrategy: Int = 1,
                              isUsingFrequentCapturesBloomFilter: Boolean)
  extends CreateDependencyCandidates[CindSet, Condition, Condition](true, true, isUseAssociationRules,
    isUsingFrequentCapturesBloomFilter = isUsingFrequentCapturesBloomFilter) {

  private var frequentBinaryConditions: Map[Int, BloomFilter[Condition]] = _

  private lazy val refConditions = new ArrayBuffer[Condition]()

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)

    if (this.isUseFrequentConditionsFilter) {
      this.frequentBinaryConditions = this.getRuntimeContext
        .getBroadcastVariable[(Int, BloomFilter[Condition])](CreateAllCindCandidates.FREQUENT_BINARY_CONDITIONS_BROADCAST)
        .toMap
    }
  }

  override protected def createUnaryConditions: mutable.Set[Condition] = mutable.SortedSet()

  override protected def createBinaryConditions: mutable.Set[Condition] = mutable.SortedSet()

  override protected def collectUnaryCapture(collector: mutable.Set[Condition], condition: Condition): Unit =
    if (isFrequentCapture(condition)) collector += condition

  override def splitAndCollectUnaryCaptures(collector: mutable.Set[Condition], condition: Condition): Unit = {
    val conditions = decodeConditionCode(condition.conditionType, isRequireDoubleCode = true)

    val newConditionCode1 = createConditionCode(conditions._1, secondaryCondition = conditions._3)
    var capture = Condition(condition.conditionValue1, null, newConditionCode1)
    if (isFrequentCapture(condition)) collector += capture

    val newConditionCode2 = createConditionCode(conditions._2, secondaryCondition = conditions._3)
    capture = Condition(condition.conditionValue2, null, newConditionCode2)
    if (isFrequentCapture(condition)) collector += capture
  }

  override def collectBinaryCaptures(collector: mutable.Set[Condition], condition: Condition): Unit =
    if ((!this.isUseFrequentConditionsFilter || {
      val filter = this.frequentBinaryConditions(condition.conditionType)
      filter.mightContain(condition)
    }) && isFrequentCapture(condition)) {
      collector += condition
    }


  override def collectDependencyCandidates(unaryConditions: mutable.Set[Condition],
                                           binaryConditions: mutable.Set[Condition],
                                           out: Collector[CindSet]): Unit = ???

  override def collectDependencyCandidates(unaryConditions: mutable.Set[Condition],
                                           binaryConditions: mutable.Set[Condition],
                                           joinLine: JoinLine,
                                           out: Collector[CindSet]): Unit = {

    val allConditions = unaryConditions ++ binaryConditions
    splitStrategy match {

      case 1 => {
        // Hash-based partition of join line.
        allConditions.foreach { dependentCondition =>
          if (shouldProcess(joinLine, dependentCondition)) {
            processDependentCondition(allConditions, dependentCondition, out)
          }
        }
      }

      case 2 => {
        // Range-based parition of join line.
        val relevantRange = determineRelevantRange(joinLine, allConditions)
        allConditions.slice(from = relevantRange._1, until = relevantRange._2).foreach { dependentCondition =>
          processDependentCondition(allConditions, dependentCondition, out)
        }
      }

      case _ => throw new IllegalStateException(s"Unsupported split strategy: $splitStrategy")
    }


  }

  /**
   * Creates the CIND set for the dependent condition.
   */
  @inline
  private def processDependentCondition(allConditions: mutable.Set[Condition], dependentCondition: Condition, out: Collector[CindSet]): Unit = {
    // Find AR implied condition (if any).
    val arImpliedCondition = findImpliedCondition(dependentCondition)

    // Gather all potential referenced captures for the current capture.
    this.refConditions.clear()
    allConditions.foreach { referencedCondition =>
      if (!dependentCondition.implies(referencedCondition) && referencedCondition != arImpliedCondition) {
        this.refConditions += referencedCondition
      }
    }
    // Otherwise the CIND candidates.
    out.collect(CindSet(dependentCondition.conditionType,
      dependentCondition.conditionValue1NotNull, dependentCondition.conditionValue2NotNull,
      1, this.refConditions.toArray))
  }
}

object CreateAllCindCandidates {

  val FREQUENT_BINARY_CONDITIONS_BROADCAST = "frequent-binary-conditions"

}
