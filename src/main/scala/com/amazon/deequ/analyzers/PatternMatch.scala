/**
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not
 * use this file except in compliance with the License. A copy of the License
 * is located at
 *
 *     http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.amazon.deequ.analyzers

import com.amazon.deequ.analyzers.Analyzers._
import com.amazon.deequ.analyzers.Preconditions.{hasColumn, isString}
import com.google.common.annotations.VisibleForTesting
import org.apache.spark.sql.functions.expr
import org.apache.spark.sql.functions.not
import org.apache.spark.sql.{Column, Row}
import org.apache.spark.sql.functions.{col, lit, regexp_extract, sum, when}
import org.apache.spark.sql.types.{BooleanType, IntegerType, StructType}

import scala.util.matching.Regex

/**
  * PatternMatch is a measure of the fraction of rows that complies with a given
  * column regex constraint. E.g if the constraint is Patterns.CREDITCARD and the
  * data frame has 5 rows which contain a credit card number in a certain column
  * according to the regex and and 10 rows that do not, a DoubleMetric would be
  * returned with 0.33 as value
  *
  * @param column     Column to do the pattern match analysis on
  * @param pattern    The regular expression to check for
  * @param where      Additional filter to apply before the analyzer is run.
  */
case class PatternMatch(column: String, pattern: Regex, where: Option[String] = None,
                        analyzerOptions: Option[AnalyzerOptions] = None)
  extends StandardScanShareableAnalyzer[NumMatchesAndCount]("PatternMatch", column)
  with FilterableAnalyzer {

  override def fromAggregationResult(result: Row, offset: Int): Option[NumMatchesAndCount] = {
    ifNoNullsIn(result, offset, howMany = 2) { _ =>
      NumMatchesAndCount(result.getLong(offset), result.getLong(offset + 1), Some(rowLevelResults.cast(BooleanType)))
    }
  }

  override def aggregationFunctions(): Seq[Column] = {

    val summation = sum(criterion)

    summation :: conditionalCount(where) :: Nil
  }

  override def filterCondition: Option[String] = where

  override protected def additionalPreconditions(): Seq[StructType => Unit] = {
    hasColumn(column) :: isString(column) :: Nil
  }

  // PatternMatch hasCode is different with the same-parameter objects
  // because Regex compares by address
  // fix this by tuple with pattern string
  private val internalObj = (column, pattern.toString(), where)

  override def hashCode(): Int = {
    internalObj.hashCode()
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case o: PatternMatch => internalObj.equals(o.asInstanceOf[PatternMatch].internalObj)
      case _ => false
    }
  }

  @VisibleForTesting // required by some tests that compare analyzer results to an expected state
  private[deequ] def criterion: Column = {
    conditionalSelection(getPatternMatchExpression, where).cast(IntegerType)
  }

  private[deequ] def rowLevelResults: Column = {
    val filteredRowOutcome = getRowLevelFilterTreatment(analyzerOptions)
    val whereNotCondition = where.map { expression => not(expr(expression)) }

    filteredRowOutcome match {
      case FilteredRowOutcome.TRUE =>
        conditionSelectionGivenColumn(getPatternMatchExpression, whereNotCondition, replaceWith = 1).cast(IntegerType)
      case _ =>
        // The default behavior when using filtering for rows is to treat them as nulls. No special treatment needed.
        criterion
    }
  }

  private def getPatternMatchExpression: Column = {
    when(regexp_extract(col(column), pattern.toString(), 0) =!= lit(""), 1).otherwise(0)
  }
}

object Patterns {

  // scalastyle:off
  // http://emailregex.com
  val EMAIL: Regex = """(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|"(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21\x23-\x5b\x5d-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])*")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?|[a-z0-9-]*[a-z0-9]:(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21-\x5a\x53-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])+)\])""".r

  // https://mathiasbynens.be/demo/url-regex stephenhay
  val URL: Regex = """(https?|ftp)://[^\s/$.?#].[^\s]*""".r

  val SOCIAL_SECURITY_NUMBER_US: Regex = """((?!219-09-9999|078-05-1120)(?!666|000|9\d{2})\d{3}-(?!00)\d{2}-(?!0{4})\d{4})|((?!219 09 9999|078 05 1120)(?!666|000|9\d{2})\d{3} (?!00)\d{2} (?!0{4})\d{4})|((?!219099999|078051120)(?!666|000|9\d{2})\d{3}(?!00)\d{2}(?!0{4})\d{4})""".r

  // Format: Five digit U.S. Zip code and an optional four digit code separated by a hyphen (-).
  val POSTAL_CODE_US: Regex = """\b\d{5}(?:-\d{4})?\b""".r
  
  // Format: U.S. phone number with optional country code, area code, and extension.
  val PHONE_NUMBER_US: Regex = """^(?:\+1\s?)?(?:\(?[2-9][0-9]{2}\)?[\s-]?)?[2-9][0-9]{2}[\s-]?[0-9]{4}(?:\s?(?:ext|x|extension)\s?[0-9]{4})?$""".r

  // Visa, MasterCard, AMEX, Diners Club
  // http://www.richardsramblings.com/regex/credit-card-numbers/
  val CREDITCARD: Regex = """\b(?:3[47]\d{2}([\ \-]?)\d{6}\1\d|(?:(?:4\d|5[1-5]|65)\d{2}|6011)([\ \-]?)\d{4}\2\d{4}\2)\d{4}\b""".r
  // scalastyle:on
}
