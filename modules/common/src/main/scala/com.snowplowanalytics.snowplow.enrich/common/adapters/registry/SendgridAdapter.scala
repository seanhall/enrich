/*
 * Copyright (c) 2015-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common
package adapters
package registry

import javax.mail.internet.ContentType

import scala.util.Try

import cats.syntax.either._
import com.snowplowanalytics.iglu.client.{Resolver, SchemaKey}
import io.circe.parser._
import scalaz._
import Scalaz._

import loaders.CollectorPayload

/**
 * Transforms a collector payload which conforms to a known version of the Sendgrid Tracking webhook
 * into raw events.
 */
object SendgridAdapter extends Adapter {
  // Vendor name for Failure Message
  private val VendorName = "Sendgrid"

  // Expected content type for a request body
  private val ContentType = "application/json"

  // Tracker version for a Sendgrid Tracking webhook
  private val TrackerVersion = "com.sendgrid-v3"

  // Schemas for reverse-engineering a Snowplow unstructured event
  private val EventSchemaMap = Map(
    "processed" -> SchemaKey("com.sendgrid", "processed", "jsonschema", "2-0-0").toSchemaUri,
    "dropped" -> SchemaKey("com.sendgrid", "dropped", "jsonschema", "2-0-0").toSchemaUri,
    "delivered" -> SchemaKey("com.sendgrid", "delivered", "jsonschema", "2-0-0").toSchemaUri,
    "deferred" -> SchemaKey("com.sendgrid", "deferred", "jsonschema", "2-0-0").toSchemaUri,
    "bounce" -> SchemaKey("com.sendgrid", "bounce", "jsonschema", "2-0-0").toSchemaUri,
    "open" -> SchemaKey("com.sendgrid", "open", "jsonschema", "2-0-0").toSchemaUri,
    "click" -> SchemaKey("com.sendgrid", "click", "jsonschema", "2-0-0").toSchemaUri,
    "spamreport" -> SchemaKey("com.sendgrid", "spamreport", "jsonschema", "2-0-0").toSchemaUri,
    "unsubscribe" -> SchemaKey("com.sendgrid", "unsubscribe", "jsonschema", "2-0-0").toSchemaUri,
    "group_unsubscribe" -> SchemaKey("com.sendgrid", "group_unsubscribe", "jsonschema", "2-0-0").toSchemaUri,
    "group_resubscribe" -> SchemaKey("com.sendgrid", "group_resubscribe", "jsonschema", "2-0-0").toSchemaUri
  )

  /**
   * Converts a payload into a list of validated events. Expects a valid json - returns a single
   * failure if one is not present
   * @param body json payload as POST'd by sendgrid
   * @param payload the rest of the payload details
   * @return a list of validated events, successes will be the corresponding raw events failures
   * will contain a non empty list of the reason(s) for the particular event failing
   */
  private def payloadBodyToEvents(
    body: String,
    payload: CollectorPayload
  ): List[Validated[RawEvent]] =
    parse(body) match {
      case Right(json) =>
        json.asArray match {
          case Some(array) =>
            array.toList.zipWithIndex.map {
              case (item, index) =>
                val eventType = item.hcursor.downField("event").as[String].toOption
                val queryString = toMap(payload.querystring)
                lookupSchema(eventType, VendorName, index, EventSchemaMap).map { schema =>
                  RawEvent(
                    api = payload.api,
                    parameters = toUnstructEventParams(
                      TrackerVersion,
                      queryString,
                      schema,
                      cleanupJsonEventValues(item, eventType.map(("event", _)), List("timestamp")),
                      "srv"
                    ),
                    contentType = payload.contentType,
                    source = payload.source,
                    context = payload.context
                  )
                }
            }
          case None => List(s"$VendorName event is not an array".failNel)
        }
      case Left(e) =>
        List(s"$VendorName event failed to parse into JSON: [${e.getMessage}]".failNel)
    }

  /**
   * Converts a CollectorPayload instance into raw events. A Sendgrid Tracking payload only contains
   * a single event. We expect the name parameter to be 1 of 6 options otherwise we have an
   * unsupported event type.
   * @param payload The CollectorPayload containing one or more raw events
   * @param resolver (implicit) The Iglu resolver used for schema lookup and validation. Not used
   * @return a Validation boxing either a NEL of RawEvents on Success, or a NEL of Failure Strings
   */
  def toRawEvents(payload: CollectorPayload)(implicit resolver: Resolver): ValidatedRawEvents =
    (payload.body, payload.contentType) match {
      case (None, _) => s"Request body is empty: no $VendorName event to process".failNel
      case (_, None) =>
        s"Request body provided but content type empty, expected $ContentType for $VendorName".failNel
      case (_, Some(ct)) if Try(new ContentType(ct).getBaseType).getOrElse(ct) != ContentType =>
        s"Content type of $ct provided, expected $ContentType for $VendorName".failNel
      case (Some(body), _) =>
        val events = payloadBodyToEvents(body, payload)
        rawEventsListProcessor(events)
    }

}
