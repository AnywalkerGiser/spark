/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources

import org.apache.spark.sql.catalyst.catalog.HiveTableRelation
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern.{BINARY_COMPARISON, IN}
import org.apache.spark.sql.catalyst.util.CharVarcharUtils
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.types.{CharType, Metadata, StringType}
import org.apache.spark.unsafe.types.UTF8String

/**
 * This rule performs string padding for char type.
 *
 * When reading values from column/field of type CHAR(N), right-pad the values to length N, if the
 * read-side padding config is turned on.
 *
 * When comparing char type column/field with string literal or char type column/field,
 * right-pad the shorter one to the longer length.
 */
object ApplyCharTypePadding extends Rule[LogicalPlan] {

  object AttrOrOuterRef {
    def unapply(e: Expression): Option[Attribute] = e match {
      case a: Attribute => Some(a)
      case OuterReference(a: Attribute) => Some(a)
      case _ => None
    }
  }

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (conf.charVarcharAsString) {
      return plan
    }

    if (conf.readSideCharPadding) {
      val newPlan = plan.resolveOperatorsUpWithNewOutput {
        case r: LogicalRelation =>
          readSidePadding(r, () =>
            r.copy(output = r.output.map(CharVarcharUtils.cleanAttrMetadata)))
        case r: DataSourceV2Relation =>
          readSidePadding(r, () =>
            r.copy(output = r.output.map(CharVarcharUtils.cleanAttrMetadata)))
        case r: HiveTableRelation =>
          readSidePadding(r, () => {
            val cleanedDataCols = r.dataCols.map(CharVarcharUtils.cleanAttrMetadata)
            val cleanedPartCols = r.partitionCols.map(CharVarcharUtils.cleanAttrMetadata)
            r.copy(dataCols = cleanedDataCols, partitionCols = cleanedPartCols)
          })
      }
      paddingForStringComparison(newPlan)
    } else {
      paddingForStringComparison(plan)
    }
  }

  private def readSidePadding(
      relation: LogicalPlan,
      cleanedRelation: () => LogicalPlan)
  : (LogicalPlan, Seq[(Attribute, Attribute)]) = {
    val projectList = relation.output.map { attr =>
      CharVarcharUtils.addPaddingForScan(attr) match {
        case ne: NamedExpression => ne
        case other => Alias(other, attr.name)(explicitMetadata = Some(attr.metadata))
      }
    }
    if (projectList == relation.output) {
      relation -> Nil
    } else {
      val newPlan = Project(projectList, cleanedRelation())
      newPlan -> relation.output.zip(newPlan.output)
    }
  }

  private def paddingForStringComparison(plan: LogicalPlan): LogicalPlan = {
    plan.resolveOperatorsUpWithPruning(_.containsAnyPattern(BINARY_COMPARISON, IN)) {
      case operator => operator.transformExpressionsUpWithPruning(
        _.containsAnyPattern(BINARY_COMPARISON, IN)) {
        case e if !e.childrenResolved => e

        // String literal is treated as char type when it's compared to a char type column.
        // We should pad the shorter one to the longer length.
        case b @ BinaryComparison(e @ AttrOrOuterRef(attr), lit) if lit.foldable =>
          padAttrLitCmp(e, attr.metadata, lit).map { newChildren =>
            b.withNewChildren(newChildren)
          }.getOrElse(b)

        case b @ BinaryComparison(lit, e @ AttrOrOuterRef(attr)) if lit.foldable =>
          padAttrLitCmp(e, attr.metadata, lit).map { newChildren =>
            b.withNewChildren(newChildren.reverse)
          }.getOrElse(b)

        case i @ In(e @ AttrOrOuterRef(attr), list)
          if attr.dataType == StringType && list.forall(_.foldable) =>
          CharVarcharUtils.getRawType(attr.metadata).flatMap {
            case CharType(length) =>
              val (nulls, literalChars) =
                list.map(_.eval().asInstanceOf[UTF8String]).partition(_ == null)
              val literalCharLengths = literalChars.map(_.numChars())
              val targetLen = (length +: literalCharLengths).max
              Some(i.copy(
                value = addPadding(e, length, targetLen),
                list = list.zip(literalCharLengths).map {
                  case (lit, charLength) => addPadding(lit, charLength, targetLen)
                } ++ nulls.map(Literal.create(_, StringType))))
            case _ => None
          }.getOrElse(i)

        // For char type column or inner field comparison, pad the shorter one to the longer length.
        case b @ BinaryComparison(e1 @ AttrOrOuterRef(left), e2 @ AttrOrOuterRef(right))
          // For the same attribute, they must be the same length and no padding is needed.
          if !left.semanticEquals(right) =>
          val outerRefs = (e1, e2) match {
            case (_: OuterReference, _: OuterReference) => Seq(left, right)
            case (_: OuterReference, _) => Seq(left)
            case (_, _: OuterReference) => Seq(right)
            case _ => Nil
          }
          val newChildren = CharVarcharUtils.addPaddingInStringComparison(Seq(left, right))
          if (outerRefs.nonEmpty) {
            b.withNewChildren(newChildren.map(_.transform {
              case a: Attribute if outerRefs.exists(_.semanticEquals(a)) => OuterReference(a)
            }))
          } else {
            b.withNewChildren(newChildren)
          }

        case i @ In(e @ AttrOrOuterRef(attr), list) if list.forall(_.isInstanceOf[Attribute]) =>
          val newChildren = CharVarcharUtils.addPaddingInStringComparison(
            attr +: list.map(_.asInstanceOf[Attribute]))
          if (e.isInstanceOf[OuterReference]) {
            i.copy(
              value = newChildren.head.transform {
                case a: Attribute if a.semanticEquals(attr) => OuterReference(a)
              },
              list = newChildren.tail)
          } else {
            i.copy(value = newChildren.head, list = newChildren.tail)
          }
      }
    }
  }

  private def padAttrLitCmp(
      expr: Expression,
      metadata: Metadata,
      lit: Expression): Option[Seq[Expression]] = {
    if (expr.dataType == StringType) {
      CharVarcharUtils.getRawType(metadata).flatMap {
        case CharType(length) =>
          val str = lit.eval().asInstanceOf[UTF8String]
          if (str == null) {
            None
          } else {
            val stringLitLen = str.numChars()
            if (length < stringLitLen) {
              Some(Seq(StringRPad(expr, Literal(stringLitLen)), lit))
            } else if (length > stringLitLen) {
              Some(Seq(expr, StringRPad(lit, Literal(length))))
            } else {
              None
            }
          }
        case _ => None
      }
    } else {
      None
    }
  }

  private def addPadding(expr: Expression, charLength: Int, targetLength: Int): Expression = {
    if (targetLength > charLength) StringRPad(expr, Literal(targetLength)) else expr
  }
}
