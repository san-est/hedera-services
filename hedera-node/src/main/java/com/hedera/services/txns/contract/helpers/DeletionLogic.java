package com.hedera.services.txns.contract.helpers;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2022 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.SigImpactHistorian;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.swirlds.merkle.map.MerkleMap;

import javax.inject.Inject;
import java.util.function.Supplier;

import static com.hedera.services.exceptions.ValidationUtils.validateFalse;
import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hedera.services.utils.EntityIdUtils.unaliased;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID;

public class DeletionLogic {
	private final HederaLedger ledger;
	private final AliasManager aliasManager;
	private final OptionValidator validator;
	private final SigImpactHistorian sigImpactHistorian;
	private final Supplier<MerkleMap<EntityNum, MerkleAccount>> contracts;

	@Inject
	public DeletionLogic(
			final HederaLedger ledger,
			final AliasManager aliasManager,
			final OptionValidator validator,
			final SigImpactHistorian sigImpactHistorian,
			final Supplier<MerkleMap<EntityNum, MerkleAccount>> contracts
	) {
		this.ledger = ledger;
		this.contracts = contracts;
		this.validator = validator;
		this.aliasManager = aliasManager;
		this.sigImpactHistorian = sigImpactHistorian;
	}

	public ResponseCodeEnum precheckValidity(final ContractDeleteTransactionBody op) {
		final var id = unaliased(op.getContractID(), aliasManager);
		return validator.queryableContractStatus(id, contracts.get());
	}

	public ContractID performFor(final ContractDeleteTransactionBody op) {
		final var id = unaliased(op.getContractID(), aliasManager);
		final var tbd = id.toGrpcAccountId();
		final var obtainer = obtainerOf(op);

		validateFalse(tbd.equals(obtainer), OBTAINER_SAME_CONTRACT_ID);
		validateTrue(ledger.exists(obtainer), OBTAINER_DOES_NOT_EXIST);
		validateFalse(ledger.isDeleted(obtainer), OBTAINER_DOES_NOT_EXIST);

		ledger.delete(tbd, obtainer);
		sigImpactHistorian.markEntityChanged(id.longValue());
		final var aliasIfAny = ledger.alias(tbd);
		if (!aliasIfAny.isEmpty()) {
			ledger.clearAlias(tbd);
			aliasManager.unlink(aliasIfAny);
			sigImpactHistorian.markAliasChanged(aliasIfAny);
		}

		return id.toGrpcContractID();
	}

	private AccountID obtainerOf(final ContractDeleteTransactionBody op) {
		validateTrue(op.hasTransferAccountID() || op.hasTransferContractID(), OBTAINER_REQUIRED);
		if (op.hasTransferAccountID()) {
			final var obtainer = op.getTransferAccountID();
			validateFalse(ledger.exists(obtainer) && ledger.isDetached(obtainer), ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
			return op.getTransferAccountID();
		} else {
			return unaliased(op.getTransferContractID(), aliasManager).toGrpcAccountId();
		}
	}
}
