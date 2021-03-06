// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.services

import java.util.UUID

import com.digitalasset.ledger.api.v1.command_submission_service.{
  CommandSubmissionServiceGrpc,
  SubmitRequest
}
import com.digitalasset.platform.sandbox.auth.ServiceCallWithMainActorAuthTests
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

trait SubmitDummyCommand extends TestCommands { self: ServiceCallWithMainActorAuthTests =>

  protected def issueCommand(): Future[Empty] =
    submit(Option(toHeader(readWriteToken(mainActor))))

  protected def dummySubmitRequest: SubmitRequest =
    SubmitRequest(
      dummyCommands(wrappedLedgerId, s"$serviceCallName-${UUID.randomUUID}", mainActor)
        .update(_.commands.applicationId := serviceCallName, _.commands.party := mainActor)
        .commands)

  protected def submit(token: Option[String]): Future[Empty] =
    stub(CommandSubmissionServiceGrpc.stub(channel), token).submit(dummySubmitRequest)

  protected lazy val command = issueCommand()

}
