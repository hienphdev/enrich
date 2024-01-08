/*
 * Copyright (c) 2022-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.0
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */

package com.snowplowanalytics.snowplow.enrich.kafka

import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.ExecutionContext
import cats.{Applicative, Parallel}
import cats.implicits._
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource, SyncIO}
import fs2.kafka.CommittableConsumerRecord
import com.snowplowanalytics.snowplow.enrich.common.fs2.Run
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{BlobStorageClients => BlobStorageClientsConfig}
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients.Client
import com.snowplowanalytics.snowplow.enrich.aws.S3Client
import com.snowplowanalytics.snowplow.enrich.gcp.GcsClient
import com.snowplowanalytics.snowplow.enrich.azure.AzureStorageClient
import com.snowplowanalytics.snowplow.enrich.kafka.generated.BuildInfo

object Main extends IOApp.WithContext {

  // Kafka records must not exceed 1MB
  private val MaxRecordSize = 1000000

  /**
   * An execution context matching the cats effect IOApp default. We create it explicitly so we can
   * also use it for our Blaze client.
   */
  override protected val executionContextResource: Resource[SyncIO, ExecutionContext] = {
    val poolSize = math.max(2, Runtime.getRuntime.availableProcessors())
    Resource
      .make(SyncIO(Executors.newFixedThreadPool(poolSize)))(pool =>
        SyncIO {
          pool.shutdown()
          pool.awaitTermination(10, TimeUnit.SECONDS)
          ()
        }
      )
      .map(ExecutionContext.fromExecutorService)
  }

  def run(args: List[String]): IO[ExitCode] =
    Run.run[IO, CommittableConsumerRecord[IO, String, Array[Byte]]](
      args,
      BuildInfo.name,
      BuildInfo.version,
      BuildInfo.description,
      executionContext,
      (_, cliConfig) => IO(cliConfig),
      (_, input, _) => Source.init[IO](input),
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.init(blocker, out),
      checkpoint,
      createBlobStorageClient,
      _.record.value,
      MaxRecordSize,
      None,
      None
    )

  private def checkpoint[F[_]: Applicative: Parallel](records: List[CommittableConsumerRecord[F, String, Array[Byte]]]): F[Unit] =
    if (records.isEmpty) Applicative[F].unit
    else
      records
        .groupBy(_.record.partition)
        .mapValues(_.maxBy(_.record.offset))
        .values
        .toList
        .parTraverse_(_.offset.commit)

  private def createBlobStorageClient(conf: BlobStorageClientsConfig): List[Blocker => Resource[IO, Client[IO]]] = {
    val gcs = if (conf.gcs) Some((b: Blocker) => Resource.eval(GcsClient.mk[IO](b))) else None
    val aws = if (conf.s3) Some((_: Blocker) => S3Client.mk[IO]) else None
    val azure = conf.azureStorage.map(s => (_: Blocker) => AzureStorageClient.mk[IO](s))
    List(gcs, aws, azure).flatten
  }
}
