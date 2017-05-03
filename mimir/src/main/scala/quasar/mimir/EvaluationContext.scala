/*
 *  ____    ____    _____    ____    ___     ____
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package quasar.mimir

import quasar.yggdrasil._
import blueeyes._, json._, serialization._
import IsoSerialization._, Iso8601Serialization._, Versioned._
import com.precog.common._, security._, accounts._

final case class EvaluationContext(apiKey: APIKey, account: AccountDetails, basePath: Path, scriptPath: Path, startTime: DateTime)

object EvaluationContext {
  val schemaV1 = "apiKey" :: "account" :: "basePath" :: "scriptPath" :: "startTime" :: HNil

  implicit val decomposer: Decomposer[EvaluationContext] = decomposerV(schemaV1, Some("1.0".v))
  implicit val extractor: Extractor[EvaluationContext]   = extractorV(schemaV1, Some("1.0".v))
}