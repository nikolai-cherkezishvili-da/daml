// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.stores.ledger

import java.time.Instant

import com.daml.ledger.participant.state.v1.{ParticipantId, SubmissionId}
import com.digitalasset.ledger.api.domain.PartyDetails

sealed abstract class PartyLedgerEntry() extends Product with Serializable {
  val submissionIdOpt: Option[SubmissionId]
  val recordTime: Instant
}

object PartyLedgerEntry {

  final case class AllocationAccepted(
      submissionIdOpt: Option[SubmissionId],
      participantId: ParticipantId,
      recordTime: Instant,
      partyDetails: PartyDetails
  ) extends PartyLedgerEntry

  final case class AllocationRejected(
      submissionId: SubmissionId,
      participantId: ParticipantId,
      recordTime: Instant,
      reason: String
  ) extends PartyLedgerEntry {
    override val submissionIdOpt: Option[SubmissionId] = Some(submissionId)
  }
}
